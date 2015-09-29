(ns purple.test.orders
  (:require [purple.orders :as orders]
            [purple.db :refer [!select conn]]
            [purple.dispatch :as dispatch]
            [purple.test.db :refer [db-config]]
            [clojure.test :refer [use-fixtures deftest is test-ns testing]]))

(def test-order1
   (let [test-user (first (!select db-config "users" ["*"]
                                  {:email "test@test.com"}))
        user-id   (:id test-user)
        vehicle-id (:id
                    (first
                     (sort-by :timestamp_created
                              (!select db-config "vehicles" ["*"]
                                       {:user_id user-id
                                        :active 1}))))
        zip "90210"
        octane "87"
        gallons 10
        service-fee 0
        gas-price (:87 (:gas_prices (dispatch/get-gas-prices zip)))
        total-price (* gallons gas-price)
        order {:time "180"
               :vehicle_id vehicle-id
               :address_street "383-399 Civic Center Dr"
               :special_instructions ""
               :service_fee service-fee
               :total_price total-price
               :coupon_code ""
               :gas_price gas-price
               :gallons gallons
               :gas_type "87"
               :lat "34.074606839269514"
               :lng "-118.39825344813232"
               :address_zip zip
               :user_id user-id}]
     order))

(defn add-order
  "Add an order to the database"
  [order]
  (orders/add db-config (:user_id order) order))