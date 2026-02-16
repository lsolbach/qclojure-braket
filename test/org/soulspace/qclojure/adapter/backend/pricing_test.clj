(ns org.soulspace.qclojure.adapter.backend.pricing-test
  "Tests for the AWS Braket pricing module.
   
   Tests cover the pricing resolution chain (device capabilities → API → fallback),
   cost estimation for QPU and simulator devices, caching behavior, and
   the 4-arg estimate-cost arity."
  (:require [clojure.test :refer [deftest is testing]]
            [org.soulspace.qclojure.domain.circuit :as circuit]
            [org.soulspace.qclojure.adapter.backend.braket :as braket]
            [org.soulspace.qclojure.adapter.backend.pricing :as pricing]
            [org.soulspace.qclojure.adapter.backend.device :as device]))

;;;
;;; Test Helpers
;;;

(defn- test-backend
  "Create a minimal backend for pricing tests."
  []
  (braket/create-braket-simulator {:s3-bucket "test-pricing-bucket"}))

(defn- bell-circuit
  "Create a simple Bell state circuit for cost estimation tests."
  []
  (-> (circuit/create-circuit 2 "Bell")
      (circuit/h-gate 0)
      (circuit/cnot-gate 0 1)))

(defn- set-device-with-cost!
  "Set a mock device with specific pricing on the backend.
   
   Parameters:
   - backend: BraketBackend instance
   - device-arn: device ARN string
   - price: price per unit
   - unit: pricing unit (\"shot\" or \"minute\")"
  [backend device-arn price unit]
  (swap! (:state backend) assoc :current-device
         {:id device-arn
          :name "Test Device"
          :capabilities {:service {:device-cost {:price price
                                                 :unit unit}}}}))

;;;
;;; device-cost extraction tests
;;;

(deftest test-device-cost-extraction
  (testing "Extracts device-cost from capabilities map"
    (let [capabilities {:service {:device-cost {:price 0.03 :unit "shot"}}}]
      (is (= {:price 0.03 :unit "shot"}
             (device/device-cost capabilities)))))

  (testing "Extracts device-cost from device map with :capabilities key"
    (let [device {:id "arn:test" :capabilities {:service {:device-cost {:price 0.075 :unit "minute"}}}}]
      (is (= {:price 0.075 :unit "minute"}
             (device/device-cost device)))))

  (testing "Returns nil when device-cost is not present"
    (is (nil? (device/device-cost {})))
    (is (nil? (device/device-cost {:service {}})))))

;;;
;;; QPU cost estimation tests
;;;

(deftest test-qpu-cost-estimation
  (testing "Single circuit QPU cost: task fee + shot cost"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1" 0.08 "shot")
          estimate (pricing/estimate-cost b (bell-circuit) {:shots 1000})]
      (is (= :per-shot (:pricing-model estimate)))
      (is (= "USD" (:currency estimate)))
      ;; total = 0.30 (task) + 1000 * 0.08 (shots) = 80.30
      (is (== 80.30 (:total-cost estimate)))
      (is (== 0.30 (get-in estimate [:cost-breakdown :per-task-fee])))
      (is (== 0.08 (get-in estimate [:cost-breakdown :price-per-shot])))
      (is (== 1 (get-in estimate [:cost-breakdown :total-tasks])))
      (is (== 1000 (get-in estimate [:cost-breakdown :total-shots])))
      (is (== 0.30 (get-in estimate [:cost-breakdown :task-cost])))
      (is (== 80.0 (get-in estimate [:cost-breakdown :shot-cost])))))

  (testing "Multiple circuits QPU cost: N tasks + total shots"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:aws:braket:us-east-1::device/qpu/ionq/Aria-1" 0.03 "shot")
          circuits [(bell-circuit) (bell-circuit) (bell-circuit)]
          estimate (pricing/estimate-cost b circuits {:shots 2500})]
      ;; total = 3 * 0.30 + 3 * 2500 * 0.03 = 0.90 + 225.00 = 225.90
      (is (== 225.90 (:total-cost estimate)))
      (is (== 3 (get-in estimate [:cost-breakdown :total-tasks])))
      (is (== 7500 (get-in estimate [:cost-breakdown :total-shots])))))

  (testing "QPU cost with IQM Garnet pricing"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:aws:braket:eu-north-1::device/qpu/iqm/Garnet" 0.00145 "shot")
          estimate (pricing/estimate-cost b (bell-circuit) {:shots 10000})]
      ;; total = 0.30 + 10000 * 0.00145 = 0.30 + 14.50 = 14.80
      (is (< (Math/abs (- 14.80 (:total-cost estimate))) 0.001)))))

;;;
;;; Simulator cost estimation tests
;;;

(deftest test-simulator-cost-estimation
  (testing "Simulator uses per-minute pricing model"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:aws:braket:::device/quantum-simulator/amazon/sv1" 0.075 "minute")
          estimate (pricing/estimate-cost b (bell-circuit) {:shots 1000})]
      (is (= :per-minute (:pricing-model estimate)))
      (is (= "USD" (:currency estimate)))
      (is (contains? (:cost-breakdown estimate) :price-per-minute))
      (is (contains? (:cost-breakdown estimate) :estimated-minutes))
      (is (contains? (:cost-breakdown estimate) :minute-cost))
      (is (pos? (:total-cost estimate)))))

  (testing "Simulator cost scales with circuit count"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:aws:braket:::device/quantum-simulator/amazon/sv1" 0.075 "minute")
          single (pricing/estimate-cost b (bell-circuit) {:shots 100})
          triple (pricing/estimate-cost b [(bell-circuit) (bell-circuit) (bell-circuit)] {:shots 100})]
      ;; 3 circuits should cost more than 1
      (is (> (:total-cost triple) (:total-cost single))))))

;;;
;;; Simulator time estimation tests
;;;

(deftest test-simulator-time-estimation
  (testing "Minimum estimated time is 1 minute"
    (let [tiny-circuit (circuit/create-circuit 1)]
      (is (>= (pricing/estimate-simulator-minutes tiny-circuit 1) 1.0))))

  (testing "More shots increase estimated time"
    (let [c (bell-circuit)]
      (is (<= (pricing/estimate-simulator-minutes c 100)
              (pricing/estimate-simulator-minutes c 100000))))))

;;;
;;; Pricing source resolution tests
;;;

(deftest test-pricing-source
  (testing "Pricing from device capabilities"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:test:qpu" 0.05 "shot")
          estimate (pricing/estimate-cost b (bell-circuit) {:shots 100})]
      (is (= :device-capabilities (:pricing-source estimate)))))

  (testing "Fallback pricing when no capabilities on device"
    (let [b (test-backend)
          ;; Set a device without capabilities, for a QPU ARN
          _ (swap! (:state b) assoc :current-device {:id "arn:aws:braket:us-east-1::device/qpu/test/TestQPU"
                                                     :name "Test QPU"})
          estimate (pricing/estimate-cost b (bell-circuit) {:shots 100})]
      ;; Should still return a valid estimate (from API or fallback)
      (is (map? estimate))
      (is (number? (:total-cost estimate)))
      (is (contains? #{:pricing-api :fallback} (:pricing-source estimate))))))

;;;
;;; 4-arg arity tests
;;;

(deftest test-estimate-cost-with-device-arn
  (testing "4-arg arity estimates cost for a specific device ARN"
    (let [b (test-backend)
          ;; Set current device to simulator
          _ (set-device-with-cost! b "arn:aws:braket:::device/quantum-simulator/amazon/sv1" 0.075 "minute")
          ;; But estimate cost for a QPU
          estimate (pricing/estimate-cost b (bell-circuit)
                                          "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1"
                                          {:shots 100})]
      ;; Should use QPU pricing, not simulator
      (is (= :per-shot (:pricing-model estimate)))
      (is (= "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1" (:device-arn estimate))))))

;;;
;;; Pricing cache tests
;;;

(deftest test-pricing-cache
  (testing "Pricing data is cached after first resolution"
    (let [b (test-backend)
          _ (set-device-with-cost! b "arn:test:cached" 0.05 "shot")
          _ (pricing/estimate-cost b (bell-circuit) {:shots 100})
          cached (get-in @(:state b) [:pricing-cache "arn:test:cached"])]
      (is (some? cached))
      (is (= :device-capabilities (:source cached)))
      (is (= {:price 0.05 :unit "shot"} (:device-cost cached))))))

;;;
;;; Per-task fee constant test
;;;

(deftest test-per-task-fee
  (testing "Per-task fee is $0.30 for all QPUs"
    (is (== 0.30 pricing/per-task-fee))))
