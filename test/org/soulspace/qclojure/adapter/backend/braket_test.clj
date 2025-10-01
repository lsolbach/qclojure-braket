(ns org.soulspace.qclojure.adapter.backend.braket-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.soulspace.qclojure.domain.circuit :as circuit]
            [org.soulspace.qclojure.application.backend :as backend]
            [org.soulspace.qclojure.adapter.backend.braket :as braket]))

(deftest test-s3-bucket-requirement
  (testing "Backend creation requires S3 bucket configuration"
    ;; Test that creating backend without S3 bucket fails
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"S3 bucket is required for Braket backend"
                          (braket/create-braket-simulator {})))
    
    ;; Test that creating backend with S3 bucket succeeds
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})]
      (is (some? b))
      (is (= "test-braket-results" (get-in b [:config :s3-bucket])))
      (is (= "braket-results/" (get-in b [:config :s3-key-prefix])))))
  
  (testing "Backend configuration includes S3 settings"
    (let [b (braket/create-braket-simulator {:s3-bucket "my-test-bucket"
                                             :s3-key-prefix "custom-prefix/"
                                             :region "us-west-2"})
          config (:config b)]
      (is (= "my-test-bucket" (:s3-bucket config)))
      (is (= "custom-prefix/" (:s3-key-prefix config)))
      (is (= "us-west-2" (:region config))))))

(deftest test-protocol-implementation
  (testing "Braket backend implements required protocols"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})]
      (is (satisfies? backend/QuantumBackend b))
      (is (satisfies? backend/CloudQuantumBackend b)))))

(deftest test-backend-info-shape
  (testing "get-backend-info returns required keys"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          info (backend/backend-info b)]
      (is (contains? info :backend-type))
      (is (= :cloud (:backend-type info)))
      (is (contains? info :backend-name))
      ;(is (set? (:supported-gates info)))
      (is (contains? info :capabilities)))))

(deftest test-job-methods-shapes
  (testing "Job methods return protocol-conformant shapes for missing job"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          status (backend/job-status b "nonexistent")
          result (backend/job-result b "nonexistent")]
      (is (keyword? status))
      (is (map? result))
      (is (contains? result :job-status)))))

(deftest test-cost-estimation-enhancements
  (testing "Enhanced cost estimation with provider multipliers"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          test-circuit (-> (circuit/create-circuit 2)
                           (circuit/h-gate 0)
                           (circuit/cnot-gate 0 1))
          cost-estimate (backend/estimate-cost b test-circuit {:shots 1000})]
      
      ;; Test cost estimation structure
      (is (map? cost-estimate))
      (is (contains? cost-estimate :total-cost))
      (is (contains? cost-estimate :cost-breakdown))
      (is (contains? (:cost-breakdown cost-estimate) :provider-multiplier))
      (is (number? (:total-cost cost-estimate))))))

(deftest test-qpu-backend-creation
  (testing "QPU backend creation requires S3 configuration"
    ;; Test QPU backend creation with S3 config
    (let [device-arn "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3"
          b (braket/create-braket-qpu device-arn {:s3-bucket "test-braket-qpu-results"
                                                   :region "us-east-1"})]
      (is (some? b))
      (is (= device-arn (get-in b [:config :device-arn])))
      (is (= "test-braket-qpu-results" (get-in b [:config :s3-bucket])))
      (is (= :quantum (get-in b [:config :device-type]))))))

(deftest test-backend-string-representation
  (testing "Backend toString method works correctly"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"
                                             :region "us-west-2"})]
      (is (string? (str b)))
      (is (re-find #"BraketBackend.*region=us-west-2" (str b))))))