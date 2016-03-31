(ns app.dispatch
  (:require [common.db :refer [conn !select !insert !update]]
            [common.config :as config]
            [common.util :refer [! cents->dollars-str five-digit-zip-code
                                 get-event-time in? minute-of-day->hmma
                                 only-prod segment-client send-sms
                                 split-on-comma unix->minute-of-day]]
            [common.orders :as orders]
            [common.users :as users]
            [common.zones :refer [get-fuel-prices get-service-fees
                                  get-service-time-bracket
                                  get-zone-by-zip-code order->zone-id
                                  zip-in-zones?]]
            [ardoq.analytics-clj :as segment]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [overtone.at-at :as at-at]
            [app.couriers :as couriers]
            [app.orders :refer [get-all-current]]
            [app.users :refer [call-user]]
            [opt.planner :refer [compute-suggestion]]))

(def job-pool (at-at/mk-pool))

(defn get-gas-prices
  "Given a zip-code, return the gas prices."
  [zip-code]
  (if (zip-in-zones? zip-code)
    {:success true
     :gas_prices (get-fuel-prices zip-code)}
    {:success true
     :gas_prices (get-fuel-prices "90210")}))

(defn delivery-times-map
  "Given a service fee, create the delivery-times map."
  [service-fees]
  (let [fee #(if (= % 0) "free" (str "$" (cents->dollars-str %)))]
    {180 {:service_fee (:180 service-fees)
          :text (str "within 3 hours (" (fee (:180 service-fees)) ")")
          :order 0}
     60 {:service_fee (:60 service-fees)
         :text (str "within 1 hour (" (fee (:60 service-fees)) ")")
         :order 1}}))

;; TODO this function should consider if a zone is actually "active"
(defn available
  [good-time?-fn zip-code octane]
  (let [service-fees (get-service-fees zip-code)
        delivery-times (delivery-times-map service-fees)
        good-times (filter #(and (zip-in-zones? zip-code) (good-time?-fn %))
                           (keys delivery-times))]
    {:octane octane
     :gallon_choices config/gallon_choices
     :gallons 15 ;; keep this for legacy app version < 1.2.2
     :price_per_gallon ((keyword octane) (get-fuel-prices zip-code))
     :times (into {} (map (juxt identity delivery-times) good-times))}))

(defn availability
  "Get courier availability for given constraints."
  [db-conn zip-code user-id]
  (let [user (users/get-user-by-id db-conn user-id)]
    (segment/track segment-client user-id "Availability Check"
                   {:address_zip (five-digit-zip-code zip-code)})
    (if (and (zip-in-zones? zip-code)
             (:active (get-zone-by-zip-code zip-code)))
      ;; good ZIP, but let's check if good time
      (let [opening-minute (first (get-service-time-bracket zip-code))
            closing-minute (last  (get-service-time-bracket zip-code))
            current-minute (unix->minute-of-day (quot (System/currentTimeMillis)
                                                      1000))
            during-holiday? (< 1451700047 ;; 6pm Jan 1st
                               (quot (System/currentTimeMillis) 1000)
                               1451725247) ;; Jan 2nd
            good-time?-fn
            (fn [minutes-needed]
              (and (not during-holiday?)
                   (<= opening-minute
                       current-minute
                       ;;(- closing-minute minutes-needed)
                       ;; removed the check for enough time
                       ;; because our end time just means we accept orders
                       ;; until then (regardless of deadline)
                       closing-minute)))]
        {:success true
         :availabilities (map (partial available good-time?-fn zip-code)
                              ["87" "91"])
         ;; if unavailable (as the client will determine from :availabilities)
         :unavailable-reason
         (if during-holiday?
           "Sorry, we're closed for the holiday. We'll be back January 2nd!"
           (str "Sorry, the service hours for this ZIP code are "
                (minute-of-day->hmma opening-minute)
                " to "
                (minute-of-day->hmma closing-minute)
                " every day."))
         :user (select-keys user [:referral_gallons :referral_code])
         ;; LEGACY: we're still sending this for old versions of the app
         :availability [{:octane "87"
                         :gallons (if (zip-in-zones? zip-code)
                                    15 0) ;; just assume 15 gallons
                         :time [1 3]
                         :price_per_gallon (:87 (get-fuel-prices zip-code))
                         :service_fee [100 0]}
                        {:octane "91"
                         :gallons (if (zip-in-zones? zip-code)
                                    15 0)
                         :time [1 3]
                         :price_per_gallon (:91 (get-fuel-prices zip-code))
                         :service_fee [100 0]}]})
      ;; bad ZIP, we don't service there yet
      {:success true
       :user (select-keys user [:referral_gallons :referral_code])
       :availabilities [{:octane "87"
                         :gallons 15
                         :times {}
                         :price_per_gallon 0}
                        {:octane "91"
                         :gallons 15
                         :times {}
                         :price_per_gallon 0}]
       :availability [{:octane "87"
                       :gallons 0
                       :time [1 3]
                       :price_per_gallon 0
                       :service_fee [100 0]}
                      {:octane "91"
                       :gallons 0
                       :time [1 3]
                       :price_per_gallon 0
                       :service_fee [100 0]}]
       :unavailable-reason
       (str "Sorry, we are unable to deliver gas to your "
            "location. We are rapidly expanding our service "
            "area and hope to offer service to your "
            "location very soon.")})))

(! (def process-db-conn (conn))) ;; ok to use same conn forever? have to test..

(defn update-courier-state
  "Marks couriers as disconnected as needed."
  [db-conn]
  (let [expired-couriers (->> (couriers/get-all-expired db-conn)
                              (users/include-user-data db-conn))]
    (when-not (empty? expired-couriers)
      (only-prod (run!
                  #(send-sms
                    (:phone_number %)
                    "You have just disconnected from the Purple Courier App.")
                  expired-couriers))
      (sql/with-connection db-conn
        (sql/update-values
         "couriers"
         [(str "id IN (\""
               (s/join "\",\"" (map :id expired-couriers))
               "\")")]
         {:connected 0})))))

(defn remind-couriers
  "Notifies couriers if they have not Accepted an order that's assign to them."
  [db-conn]
  (let [assigned-orders (!select db-conn "orders" ["*"] {:status "assigned"})
        tardy? (fn [time-assigned]
                 (<= (+ time-assigned config/courier-reminder-time)
                     (quot (System/currentTimeMillis) 1000)
                     (+ time-assigned config/courier-reminder-time
                        (- (quot config/process-interval 1000) 1))))
        twilio-url (str config/base-url "twiml/courier-new-order")
        f #(when (tardy? (get-event-time (:event_log %) "assigned"))
             (call-user db-conn (:courier_id %) twilio-url))]
    (run! f assigned-orders)))

(defn new-assignments
  [os cs]
  (let [new-and-first #(and (:new_assignment %)
                            (= 1 (:courier_pos %)))]
    (filter
     (comp new-and-first val)
     (fmap (comp keywordize-keys (partial into {}))
           (compute-suggestion
            {"orders" (->> os
                           (map #(assoc %
                                        :status_times
                                        (-> (:event_log %)
                                            (s/split #"\||\s")
                                            (->> (remove s/blank?)
                                                 (apply hash-map)
                                                 (fmap read-string)))
                                        :zone (order->zone-id %)))
                           (map (juxt :id stringify-keys))
                           (into {}))
             "couriers" (->> cs
                             (map #(assoc % :zones (apply list (:zones %))))
                             (map (juxt :id stringify-keys))
                             (into {}))})))))

;; We start with a prev-state of blank; so that auto-assign is called when
;; server is booted.
(def prev-state (atom {:current-orders []
                       :on-duty-couriers []}))

;; If you added to the select-keys for 'cs' and include :last_ping
;; or :lat and :lng, then auto-assign would run every time courier changes
;; position; which may or may not be desirable.
(defn get-state
  [os cs]
  {:current-orders (map #(select-keys % [:id :status :courier_id]) os)
   :on-duty-couriers (map #(select-keys % [:id :active :on_duty :connected
                                           :busy :zones]) cs)})

(defn diff-state?
  "Has state changed significantly to trigger an auto-assign call?"
  [os cs]
  {:post [(reset! prev-state (get-state os cs))]}
  (not= @prev-state (get-state os cs)))

(defn auto-assign
  [db-conn]
  (let [os (get-all-current db-conn)
        cs (couriers/get-all-on-duty db-conn)]
    (!insert
     db-conn
     "state_log"
     {:data
      (str {:current-orders (map #(select-keys % [:id :status :courier_id]) os)
            :on-duty-couriers (map #(select-keys % [:id :active :on_duty
                                                    :connected :busy :zones
                                                    :gallons_87 :gallons_91
                                                    :lat :lng :last_ping]) cs)})
      })
    (when (diff-state? os cs)
      (run! #(orders/assign db-conn (key %) (:courier_id (val %))
                            :no-reassigns true)
            (new-assignments os cs)))))

(defn process
  "Does a few periodic tasks."
  []
  ((juxt update-courier-state remind-couriers auto-assign) process-db-conn))

(! (def process-job (at-at/every config/process-interval process job-pool)))

(defn courier-ping
  "The courier app periodically pings us with courier status details."
  [db-conn user-id lat lng gallons_87 gallons_91 set-on-duty]
  (merge (!update db-conn
                  "couriers"
                  (merge {:lat lat
                          :lng lng
                          :gallons_87 gallons_87
                          :gallons_91 gallons_91
                          :connected 1
                          :last_ping (quot (System/currentTimeMillis) 1000)}
                         (when (not (nil? set-on-duty))
                           {:on_duty set-on-duty}))
                  {:id user-id})
         {:on_duty (couriers/on-duty? db-conn user-id)}))
