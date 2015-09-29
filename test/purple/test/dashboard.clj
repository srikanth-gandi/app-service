(ns purple.test.dashboard
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [purple.handler :refer [app]]
            [purple.db :refer [!select conn]]
            [purple.dispatch :as dispatch]
            [purple.test.db :refer [db-config]]
            [purple.test.orders :as orders]
            [clojure.test :refer [use-fixtures deftest is test-ns testing]]
            [clj-webdriver.taxi :refer :all]))

;; normally, the test server runs on port 3000. If you would like to manually
;; run tests, you can set this to (def test-port 3000) in the repl
;; just reload this file (C-c C-l in cider) when running
;; (test-ns 'purple.test.dashboard)
(def test-port 5744)
(def test-host "localhost")
(def user "purpleadmin")
(def password "gasdelivery8791")
(def test-base-url (str "http://" test-host ":" test-port "/"))

;; the fixtures setup are based off of
;; https://semaphoreci.com/community/tutorials/testing-clojure-web-applications-with-selenium
(defn start-server []
  (loop [server (run-jetty app {:port test-port, :join? false})]
    (if (.isStarted server)
      server
      (recur server))))

(defn stop-server [server]
  (.stop server))

(defn with-server [t]
  (let [server (start-server)]
    (t)
    (stop-server server)))

(defn start-browser []
  (set-driver! {:browser :chrome}))

(defn stop-browser []
  (quit))

(defn with-browser [t]
  (start-browser)
  (t)
  (stop-browser))

;; this function is used to slow down clojure so the browser has time to catch
;; up. If you are having problems with tests passing, particuarly if they appear
;; to randomly fail, try increasing the amount of sleep time before the call
;; that is failing
(defn sleep
  "Sleep for ms."
  [& [ms]]
  (let [default-ms 700
        time (or ms default-ms)]
    (Thread/sleep time)))

(defn go-to-dashboard
  "Navigate to the dashboard"
  []
  (to (str
       "http://" user ":" password "@" test-host ":" test-port "/dashboard")))

(defn cancel-order
  "Cancel the most recently added order"
  []
  (click ".cancel-order")
  (sleep)
  (accept)
  (sleep)
  (accept))

(defn fully-cycle-order
  "Cycle through the first order with an unassigned status from accepted
  to completed. This assumes the courier has already been assigned the order
  and the order has a status of 'acccepted'."
  []
  (let [order-id     (attribute {:css "input.advance-status"} :data-order-id)
        order-status #(text (find-element
                             {:xpath (str "//input[@data-order-id='"
                                          order-id
                                          "']//..")}))]
    (is (= (order-status) "accepted"))
    ;; accepted -> enroute
    (click "input.advance-status")
    (sleep)
    (accept)
    (sleep)
    (is (= (order-status) "enroute"))
    ;; enroute -> servicing
    (click "input.advance-status")
    (sleep)
    (accept)
    (sleep)
    (is (= (order-status) "servicing"))
    ;; servicing -> complete
    (click "input.advance-status")
    (sleep)
    (accept)
    (sleep)
    (accept)
    ;; the order status of 'complete' is not checked because there is
    ;; currently no way to determine which tr corresponds to an order-id
    ;; (sleep)
    ;;(is (= (order-status) "complete"))
    ))

(defn assign-courier
  "Assign courier-name to the first route open in the dashboard. Returns the
  courier's id value"
  [courier-name]
  (let [courier-id (value (select-option ".assign-courier"
                                         {:text courier-name}))]
    (click "input.assign-courier")
    (sleep)
    (accept)
    (sleep 700)
    (accept)
    courier-id))

(defn is-courier-busy?
  "Is the text inside of the td.busy element in table#couriers for courier
  'Yes'?"
  [courier]
  (boolean
   (= "Yes" (text (find-element {:xpath (str "//table[@id='couriers']"
                                             "//td[text()='"
                                             courier
                                             "']"
                                             "/../td[@class='busy']")})))))

(use-fixtures :once with-browser with-server)

(deftest dashboard-greeting
  (testing "make sure that the dashboard is accessible"
    (go-to-dashboard)
    (is (exists? "#last-updated"))))

;; tests below assume all orders in the dashboard have a status of
;; complete or cancelled
(deftest add-order-and-cancel-it
  (testing "Add an order an cancel it in the dashboard"
    (orders/add-order orders/test-order1)
    (go-to-dashboard)
    (cancel-order)))

(deftest add-order-assign-and-cancel
  (testing "Add an order, assign it a courier and then cancel it"
    (orders/add-order orders/test-order1)
    (go-to-dashboard)
    (assign-courier "Test Courier1")
    (sleep)
    (is (true? (is-courier-busy? "Test Courier1")))
    (cancel-order)
    (sleep)
    (is (false? (is-courier-busy? "Test Courier1")))))

(deftest order-is-added-assigned-and-cycled
  (testing "An order is added, assigned to 'Test Courier1' and
the status cycled through. Courier is checked for proper busy status"
    (orders/add-order orders/test-order1)
    (go-to-dashboard)
    ;; assign the courier
    (assign-courier "Test Courier1")
    ;; give the browser time to catch up
    (sleep)
    ;; check to see that the courier is busy
    (is (true? (is-courier-busy? "Test Courier1")))
    ;; cycle through the first order
    (fully-cycle-order)
    (sleep)
    ;; check to see that courier is NOT busy
    (is (false? (is-courier-busy? "Test Courier1")))
    ;; the server will be brought down by the fixture before
    ;; the browser responds, so let it sleep a bit
    (sleep)))

(deftest two-orders-are-added-to-courier-and-cycled
  (testing "Two orders are added, two are assigned to 'Test Courier1',
both are cycled and the busy status of the courier is checked"
    ;; add two orders
    (orders/add-order orders/test-order1)
    (orders/add-order orders/test-order1)
    (go-to-dashboard)
    (assign-courier "Test Courier1")
    (sleep)
    (is (true? (is-courier-busy? "Test Courier1")))
    (assign-courier "Test Courier1")
    (sleep)
    ;; cycle through the first order
    (fully-cycle-order)
    (sleep)
    (is (true? (is-courier-busy? "Test Courier1")))
    ;; cycle through the second order
    (fully-cycle-order)
    (sleep)
    ;; all orders are cleared, the courier should
    ;; no longer be busy
    (is (false? (is-courier-busy? "Test Courier1")))))