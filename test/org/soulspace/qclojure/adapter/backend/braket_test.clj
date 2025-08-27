(ns org.soulspace.qclojure.adapter.backend.braket-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.soulspace.qclojure.application.backend :as qb]
            [org.soulspace.qclojure.adapter.backend.braket :as braket]
            [org.soulspace.qclojure.domain.circuit :as qc]))

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
      (is (satisfies? qb/QuantumBackend b))
      (is (satisfies? qb/CloudQuantumBackend b)))))

(deftest test-backend-info-shape
  (testing "get-backend-info returns required keys"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          info (qb/get-backend-info b)]
      (is (contains? info :backend-type))
      (is (= :cloud (:backend-type info)))
      (is (contains? info :backend-name))
      (is (set? (:supported-gates info)))
      (is (contains? info :capabilities)))))

(deftest test-job-methods-shapes
  (testing "Job methods return protocol-conformant shapes for missing job"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          status (qb/get-job-status b "nonexistent")
          result (qb/get-job-result b "nonexistent")]
      (is (keyword? status))
      (is (map? result))
      (is (contains? result :job-status)))))

(deftest test-enhanced-backend-features
  (testing "Enhanced AmazonBraketBackend protocol methods"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})]
      (is (satisfies? braket/AmazonBraketBackend b))
      
      ;; Test provider info
      (let [provider-info (braket/get-provider-info b :simulator)]
        (is (map? provider-info))
        (is (contains? provider-info :provider))
        (is (contains? provider-info :supported-gates)))
      
      ;; Test device info
      (let [device-info (braket/get-device-info b "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
        (is (map? device-info))
        (is (contains? device-info :provider)))
      
      ;; Test circuit validation
      (let [test-circuit (-> (qc/create-circuit 2)
                             (qc/h-gate 0)
                             (qc/cnot-gate 0 1))
            validation (braket/validate-circuit b test-circuit)]
        (is (map? validation))
        (is (contains? validation :valid?)))
      
      ;; Test error mitigation
      (let [test-circuit (-> (qc/create-circuit 2)
                             (qc/h-gate 0)
                             (qc/cnot-gate 0 1))
            mitigation-result (braket/apply-error-mitigation b test-circuit {:shots 100})]
        (is (map? mitigation-result))
        (is (contains? mitigation-result :circuit))
        (is (contains? mitigation-result :mitigation-applied)))
      
      ;; Test noise model
      (let [noise-model (braket/get-device-noise-model b "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
        (is (map? noise-model))
        (is (contains? noise-model :gate-noise))
        (is (contains? noise-model :readout-error))))))

(deftest test-provider-specific-optimization
  (testing "Provider-specific gate sets and constraints"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})]
      ;; Test different provider configurations
      (doseq [provider [:rigetti :ionq :iqm :oqc :quera :amazon :simulator]]
        (let [provider-info (braket/get-provider-info b provider)]
          (is (set? (:supported-gates provider-info)))
          (is (map? (:constraints provider-info)))
          (is (number? (:pricing-multiplier provider-info))))))))

(deftest test-device-validation
  (testing "Circuit validation for different device types"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          simple-circuit (-> (qc/create-circuit 2)
                             (qc/h-gate 0)
                             (qc/cnot-gate 0 1))
          validation (braket/validate-circuit b simple-circuit)]
      
      ;; Test validation passes for simple circuit
      (is (:valid? validation))
      
      ;; Test validation includes required fields
      (is (contains? validation :qubit-count))
      (is (contains? validation :qubit-constraint-ok?))
      (is (contains? validation :gate-constraint-ok?)))))

(deftest test-cost-estimation-enhancements
  (testing "Enhanced cost estimation with provider multipliers"
    (let [b (braket/create-braket-simulator {:s3-bucket "test-braket-results"})
          test-circuit (-> (qc/create-circuit 2)
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 1))
          cost-estimate (qb/estimate-cost b test-circuit {:shots 1000})]
      
      ;; Test cost estimation structure
      (is (map? cost-estimate))
      (is (contains? cost-estimate :total-cost))
      (is (contains? cost-estimate :cost-breakdown))
      (is (contains? (:cost-breakdown cost-estimate) :provider-multiplier))
      (is (number? (:total-cost cost-estimate))))))

(deftest test-optimization-integration
  (testing "Circuit optimization for different device types"
    (let [test-circuit (-> (qc/create-circuit 3)
                           (qc/h-gate 0)
                           (qc/cnot-gate 0 2)) ; Non-adjacent for some topologies
          device-info {:provider :rigetti 
                       :constraints {:max-qubits 80}}
          optimization-result (braket/optimize-circuit-for-device test-circuit device-info)]
      
          ;; Test optimization returns proper structure
      (is (map? optimization-result))
      (is (contains? optimization-result :quantum-circuit)))))

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