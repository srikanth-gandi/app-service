(ns app.orders
  (:require [common.config :as config]
            [common.db :refer [!select !insert !update]]
            [common.util :refer [cents->dollars-str in? log-error
                                 gallons->display-str
                                 minute-of-day->hmma
                                 rand-str-alpha-num coerce-double
                                 segment-client send-email send-sms
                                 unless-p only-prod only-prod-or-dev now-unix
                                 unix->fuller unix->full unix->minute-of-day
                                 geocode]]
            [common.users :refer [details get-user-by-id include-user-data
                                  is-managed-account? charge-user]]
            [common.orders :refer [accept assign begin-route complete get-by-id
                                   next-status segment-props service
                                   unpaid-balance]]
            [common.coupons :refer [format-coupon-code
                                    mark-code-as-used
                                    mark-gallons-as-used]]
            [common.subscriptions :as subscriptions]
            [common.zones :refer [get-zip-def is-open? order->zones]]
            [app.coupons :as coupons]
            [app.couriers :as couriers]
            [app.sift :as sift]
            [ardoq.analytics-clj :as segment]
            [clojure.string :as s]
            [cheshire.core :refer [generate-string]]
            [clj-http.client :as client]))

;; Order status definitions
;; unassigned - not assigned to any courier yet
;; assigned   - assigned to a courier (usually we are skipping over this status)
;; accepted   - courier accepts this order as their current task (can be forced)
;; enroute    - courier has begun task (can't be forced; always done by courier)
;; servicing  - car is being serviced by courier (e.g., pumping gas)
;; complete   - order has been fulfilled
;; cancelled  - order has been cancelled (either by customer or system)

(defn get-all
  [db-conn]
  (!select db-conn
           "orders"
           ["*"]
           {}
           :append "ORDER BY target_time_start DESC"))

(defn get-all-unassigned
  [db-conn]
  (!select db-conn
           "orders"
           ["*"]
           {:status "unassigned"}
           :append "ORDER BY target_time_start DESC"))

(defn get-all-pre-servicing
  "All orders in a status chronologically before the Servicing status."
  [db-conn]
  (!select db-conn
           "orders"
           ["*"]
           {}
           :append (str "AND status IN ("
                        "'unassigned',"
                        "'assigned',"
                        "'accepted',"
                        "'enroute'"
                        ") ORDER BY target_time_start DESC")))

(defn get-all-current
  "Unassigned or in process."
  [db-conn]
  (!select db-conn
           "orders"
           ["*"]
           {}
           :append (str "AND status IN ("
                        "'unassigned',"
                        "'assigned',"
                        "'accepted',"
                        "'enroute',"
                        "'servicing'"
                        ") ORDER BY target_time_start DESC")))

(defn gen-charge-description
  "Generate a description of the order (e.g., for including on a receipt)."
  [db-conn order]
  (let [vehicle (first (!select db-conn "vehicles" ["*"] {:id (:vehicle_id order)}))]
    (str "Reliability service for up to "
         (gallons->display-str (:gallons order)) " Gallons of Fuel"
         " (" (:gas_type vehicle) " Octane)" ; assumes you're calling this when order is place (i.e., it could change)
         (when (:tire_pressure_check order) "\n+ Tire Pressure Fill-up")
         "\nVehicle: " (:year vehicle) " " (:make vehicle) " " (:model vehicle)
         " (" (:license_plate order) ")"
         "\nWhere: " (:address_street order)
         "\n" "When: " (unix->fuller (quot (System/currentTimeMillis) 1000)))))

(defn auth-charge-order
  [db-conn order]
  (charge-user db-conn
               (:user_id order)
               (:total_price order)
               (gen-charge-description db-conn order)
               (:id order)
               :metadata {:order_id (:id order)}
               :just-auth true))

(defn stamp-with-charge
  "Give it a charge object from Stripe."
  [db-conn order-id charge]
  (!update db-conn
           "orders"
           {:paid (:captured charge) ;; NOT THE SAME as (:paid charge)
            :stripe_charge_id (:id charge)
            :stripe_customer_id_charged (:customer charge)
            :stripe_balance_transaction_id (:balance_transaction charge)
            :time_paid (:created charge)
            :payment_info (-> charge
                              :source
                              (select-keys
                               [:id :brand :exp_month :exp_year :last4])
                              generate-string)}
           {:id order-id}))

(defn calculate-cost
  "Calculate cost of order based on current prices. Returns cost in cents."
  [db-conn               ;; Database Connection
   user                  ;; 'user' map
   zip-def
   octane                ;; String
   gallons               ;; Double
   time                  ;; Integer, minutes
   tire-pressure-check   ;; Boolean
   coupon-code           ;; String
   vehicle-id            ;; String
   referral-gallons-used ;; Double
   zip-code              ;; String
   & {:keys [bypass-zip-code-check]}]
  ((comp (partial max 0) int #(Math/ceil %))
   (+ (* (get (:gas-price zip-def) octane) ; cents/gallon
         ;; number of gallons they need to pay for
         (- gallons (min gallons referral-gallons-used)))
      ;; add delivery fee (w/ consideration of subscription)
      (let [sub (subscriptions/get-with-usage db-conn user)
            delivery-fee (get (:delivery-fee zip-def) time)]
        (if sub
          (let [[num-free num-free-used sub-discount]
                (case time
                  60  [(:num_free_one_hour sub)
                       (:num_free_one_hour_used sub)
                       (:discount_one_hour sub)]
                  180 [(:num_free_three_hour sub)
                       (:num_free_three_hour_used sub)
                       (:discount_three_hour sub)]
                  300 [(:num_free_five_hour sub)
                       (:num_free_five_hour_used sub)
                       (:discount_five_hour sub)])]
            (if (pos? (- num-free num-free-used))
              0
              (max 0 (+ delivery-fee sub-discount))))
          delivery-fee))
      ;; add cost of tire pressure check if applicable
      (if tire-pressure-check
        config/tire-pressure-check-price
        0)
      ;; apply value of coupon code 
      (if-not (s/blank? coupon-code)
        (:value (coupons/code->value
                 db-conn
                 coupon-code
                 vehicle-id
                 (:id user)
                 zip-code
                 :bypass-zip-code-check bypass-zip-code-check))
        0))))

(defn valid-price?
  "Is the stated 'total_price' accurate?"
  [db-conn user zip-def o & {:keys [bypass-zip-code-check]}]
  (= (:total_price o)
     (calculate-cost db-conn
                     user
                     zip-def
                     (:gas_type o)
                     (:gallons o)
                     (:time-limit o)
                     (:tire_pressure_check o)
                     (:coupon_code o)
                     (:vehicle_id o)
                     (:referral_gallons_used o)
                     (:address_zip o)
                     :bypass-zip-code-check bypass-zip-code-check)))

(defn orders-in-zone
  [db-conn zone-id os]
  (filter #(in? (order->zones db-conn %) zone-id) os))

(defn valid-time-choice?
  "Is that Time choice (e.g., 1 hour / 3 hour) truly available?"
  [db-conn zip-def o]
  (or
   ;; "Premium" members can bypass
   (or (and (= 2 (:subscription_id o)) 
            (= (:time-limit o) 60))
       (and (= 2 (:subscription_id o)) 
            (= (:time-limit o) 180)))
   ;; "Standard" members can bypass
   (and (= 1 (:subscription_id o)) (= (:time-limit o) 180))
   ;; "Unlimited" members can bypass all
   (= 3 (:subscription_id o))
   ;; otherwise, is it offered?
   (and (in? (vals (:time-choices zip-def)) (:time-limit o))
        (or (>= (:time-limit o) 180)
            (not (:one-hour-constraining-zone-id zip-def))
            ;; Are there less one-hour orders in this zone
            ;; than connected couriers who are assigned to this zone?
            (< (->> (get-all-pre-servicing db-conn)
                    (orders-in-zone db-conn (:one-hour-constraining-zone-id zip-def))
                    (filter #(= (* 60 60) ;; only one-hour orders
                                (- (:target_time_end %)
                                   (:target_time_start %))))
                    count)
               (->> (couriers/get-all-connected db-conn)
                    (couriers/filter-by-zone
                     (:one-hour-constraining-zone-id zip-def))
                    count))))))

(defn new-order-text
  [db-conn o charge-authorized?]
  (str "New order:"
       (let [unpaid-balance (unpaid-balance db-conn (:user_id o))]
         (when (pos? unpaid-balance)
           (str "\n!UNPAID BALANCE: $" (cents->dollars-str unpaid-balance))))
       "\nDue: " (unix->full (:target_time_end o))
       "\n" (:address_street o) ", " (:address_zip o)
       (when (:tire_pressure_check o) "\n+ TIRE PRESSURE CHECK")
       "\n" (:gallons o) " Gallons of " (:gas_type o)))

(defn add
  "The user-id given is assumed to have been auth'd already."
  [db-conn user-id order & {:keys [bypass-zip-code-check]}]
  (let [time-limit (Integer. (:time order))
        vehicle (first (!select db-conn "vehicles" ["*"] {:id (:vehicle_id order)}))
        user (get-user-by-id db-conn user-id)
        referral-gallons-available (:referral_gallons user)
        curr-time-secs (quot (System/currentTimeMillis) 1000)
        zip-def (get-zip-def db-conn (:address_zip order))
        ;; bad-coords? true means that geocoding failed client-side
        bad-coords? (zero? (coerce-double (:lat order)))
        good-coords (when bad-coords?
                      (or (let [geocode-result (geocode (:address_street order)
                                                        (:address_zip order))]
                            geocode-result)
                          {:lat 0 :lng 0}))
        [lat lng] (if bad-coords?
                    [(:lat good-coords) (:lng good-coords)]
                    [(coerce-double (:lat order)) (coerce-double (:lng order))])
        o (assoc (select-keys order [:vehicle_id :special_instructions
                                     :address_street :address_city
                                     :address_state :address_zip :gas_price
                                     :service_fee :total_price])
                 :id (rand-str-alpha-num 20)
                 :user_id user-id
                 :status "unassigned"
                 :target_time_start curr-time-secs
                 :target_time_end (+ curr-time-secs (* 60 time-limit))
                 :time-limit time-limit
                 :gallons (coerce-double (:gallons order))
                 :gas_type (if (:gas_type order)
                             (:gas_type order)
                             (throw (Exception. "Outdated app version.")))
                 :is_top_tier (:only_top_tier vehicle)
                 :lat lat
                 :lng lng
                 :license_plate (:license_plate vehicle)
                 ;; we'll use as many referral gallons as available
                 :referral_gallons_used (min (coerce-double (:gallons order))
                                             referral-gallons-available)
                 :coupon_code (format-coupon-code (or (:coupon_code order) ""))
                 :subscription_id (if (subscriptions/valid? user)
                                    (:subscription_id user)
                                    0)
                 :tire_pressure_check (or (:tire_pressure_check order) false))]

    (cond
      (and bad-coords? (not= (:resolved-zip good-coords) (:address_zip o)))
      ;; it seems that there Saved Locations are bad, and are out of sync with
      ;; their saved_locations in the database. This is a fix for a temporary bug.
      (do (log-error (str "Bad Coords on Order: " o
                          "\n\nGeocode Result: " good-coords))
          {:success false
           :message (str "There was a problem with your Saved Location. "
                         "Please try editing it and re-entering the address.")
           :message_title "Sorry"})
      
      (not (valid-price? db-conn user zip-def o :bypass-zip-code-check bypass-zip-code-check))
      (do (only-prod-or-dev
           (segment/track segment-client (:user_id o) "Request Order Failed"
                          (assoc (segment-props o :zip-def zip-def)
                                 :reason "price-changed-during-review")))
          {:success false
           :message (str "The price changed while you were creating your "
                         "order. Please press the back button TWICE to go back "
                         "to the map and start over.")
           :message_title "Sorry"})

      (not (valid-time-choice? db-conn zip-def o))
      (do (only-prod-or-dev
           (segment/track segment-client (:user_id o) "Request Order Failed"
                          (assoc (segment-props o :zip-def zip-def)
                                 :reason "high-demand")))
          {:success false
           :message (str "We currently are experiencing high demand and "
                         "can't promise a delivery within that time limit. Please "
                         "go back and choose a different Time option.")
           :message_title "Sorry"})

      (not (is-open? zip-def (:target_time_start o)))
      (do (only-prod-or-dev
           (segment/track segment-client (:user_id o) "Request Order Failed"
                          (assoc (segment-props o :zip-def zip-def)
                                 :reason "outside-service-hours")))
          {:success false
           :message (:closed-message zip-def)})
      
      :else
      (let [auth-charge-result (if (or (zero? (:total_price o)) ; nothing to charge
                                       (is-managed-account? user)) ; managed account (will be charged later)
                                 {:success true}
                                 (auth-charge-order db-conn o))
            charge-authorized? (:success auth-charge-result)]
        (if (not charge-authorized?)
          (do ;; payment failed, do not allow order to be placed
            (only-prod-or-dev
             (segment/track segment-client (:user_id o) "Request Order Failed"
                            (assoc (segment-props o :zip-def zip-def)
                                   :charge-authorized charge-authorized? ;; false
                                   :reason "failed-charge")))
            ;; TODO send notification to us? (async?)
            {:success false
             :message (str "Sorry, we were unable to charge your credit card. "
                           "Please go to the \"Account\" page and tap on "
                           "\"Payment Method\" to add a new card. Also, "
                           "ensure your email address is valid.")
             :message_title "Unable to Charge Card"})
          (do ;; successful payment (or free order), place order...
            (!insert db-conn "orders" (select-keys o [:id :user_id :vehicle_id
                                                      :status :target_time_start
                                                      :target_time_end
                                                      :gallons :gas_type :is_top_tier
                                                      :special_instructions
                                                      :lat :lng :address_street
                                                      :address_city :address_state
                                                      :address_zip :gas_price
                                                      :service_fee :total_price
                                                      :license_plate :coupon_code
                                                      :referral_gallons_used
                                                      :subscription_id
                                                      :tire_pressure_check]))
            (when-not (zero? (:referral_gallons_used o))
              (mark-gallons-as-used db-conn
                                    (:user_id o)
                                    (:referral_gallons_used o)))
            (when-not (s/blank? (:coupon_code o))
              (mark-code-as-used db-conn
                                 (:coupon_code o)
                                 (:license_plate o)
                                 (:user_id o)))
            (future ;; we can process the rest of this asynchronously
              (when (and charge-authorized? (not (zero? (:total_price o))))
                (stamp-with-charge db-conn (:id o) (:charge auth-charge-result)))

              ;; fraud detection
              (when (not (zero? (:total_price o)))
                (let [c (:charge auth-charge-result)]
                  (sift/charge-authorization
                   o user
                   (if charge-authorized?
                     {:stripe-charge-id (:id c)
                      :successful? true
                      :card-last4 (:last4 (:card c))
                      :stripe-cvc-check (:cvc_check (:card c))
                      :stripe-funding (:funding (:card c))
                      :stripe-brand (:brand (:card c))
                      :stripe-customer-id (:customer c)}
                     {:stripe-charge-id (:charge (:error c))
                      :successful? false
                      :decline-reason-code (:decline_code (:error c))}))))
              
              (only-prod
               (let [order-text-info (new-order-text db-conn o charge-authorized?)]
                 (client/post "https://hooks.slack.com/services/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"
                              {:throw-exceptions false
                               :content-type :json
                               :form-params {:text (str order-text-info
                                                        ;; TODO
                                                        ;; "\n<https://NEED_ORDER_PAGE_LINK_HERE|View on Dashboard>"
                                                        )
                                             :icon_emoji ":fuelpump:"
                                             :username "New Order"}})
                 (run! #(send-sms % order-text-info)
                       [ ; no phone number in here atm
                        ])))
              
              (only-prod-or-dev
               (segment/track segment-client (:user_id o) "Request Order"
                              (assoc (segment-props o :zip-def zip-def)
                                     :charge-authorized charge-authorized?))
               ;; used by mailchimp
               (segment/identify segment-client (:user_id o)
                                 {:email (:email user) ;; required every time
                                  :HASORDERED 1})))
            {:success true
             :message (str "Your order has been accepted, and a courier will be "
                           "on the way soon! Please ensure that the fueling door "
                           "on your gas tank is unlocked.")
             :message_title "Order Accepted"}))))))

(def busy-statuses ["assigned" "accepted" "enroute" "servicing"])

(defn courier-busy?
  "Is courier currently working on an order?"
  [db-conn courier-id]
  (let [orders (!select db-conn
                        "orders"
                        [:id :status]
                        {:courier_id courier-id})]
    (boolean (some #(in? busy-statuses (:status %)) orders))))

(defn set-courier-busy
  [db-conn courier-id busy]
  (!update db-conn
           "couriers"
           {:busy busy}
           {:id courier-id}))

(defn update-courier-busy [db-conn courier-id]
  "Determine if courier-id is busy and toggle the appropriate state. A courier
is considered busy if there are orders that have not been completed or cancelled
and their id matches the order's courier_id"
  (let [busy? (courier-busy? db-conn courier-id)]
    (set-courier-busy db-conn courier-id busy?)))

(defn update-status-by-courier
  [db-conn user-id order-id status]
  (if-let [order (get-by-id db-conn order-id)]
    (if (not= "cancelled" (:status order))
      (if (not= status (next-status (:status order)))
        {:success false
         :message
         (if (= status "assigned")
           (str "Oops... it looks like another courier accepted that order "
                "before you.")
           (str "Your app seems to be out of sync. Try going back to the "
                "Orders list and pulling down to refresh it. Or, you might "
                "need to close the app completely and restart it."))}
        (let [update-result
              (case status
                "assigned" (if (couriers/on-duty? db-conn user-id) ;; security
                             (assign db-conn order-id user-id)
                             {:success false
                              :message (str "Your app may have gotten "
                                            "disconnected. Try closing the "
                                            "app completely and restarting it. "
                                            "Then wait 10 seconds.")})
                "accepted" (if (= user-id (:courier_id order))
                             (accept db-conn order-id)
                             {:success false
                              :message "Permission denied."})
                "enroute" (if (= user-id (:courier_id order))
                            (begin-route db-conn order)
                            {:success false
                             :message "Permission denied."})
                "servicing" (if (= user-id (:courier_id order))
                              (service db-conn order)
                              {:success false
                               :message "Permission denied."})
                "complete" (if (= user-id (:courier_id order))
                             (complete db-conn order)
                             {:success false
                              :message "Permission denied."})
                {:success false
                 :message "Invalid status."})]
          ;; send back error message or user details if successful
          (if (:success update-result)
            (details db-conn user-id)
            update-result)))
      {:success false
       :message "That order was cancelled."})
    {:success false
     :message "An order with that ID could not be found."}))

(defn update-rating
  "Assumed to have been auth'd properly already."
  [db-conn order-id number-rating text-rating]
  (!update db-conn
           "orders"
           {:number_rating number-rating
            :text_rating text-rating}
           {:id order-id}))

(defn rate
  [db-conn user-id order-id rating]
  (do (update-rating db-conn
                     order-id
                     (:number_rating rating)
                     (:text_rating rating))
      (only-prod-or-dev
       (segment/track segment-client user-id "Rate Order"
                      {:order_id order-id
                       :number_rating (:number_rating rating)}))
      (details db-conn user-id)))
