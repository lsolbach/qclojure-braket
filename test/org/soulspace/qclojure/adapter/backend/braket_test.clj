(ns org.soulspace.qclojure.adapter.backend.braket-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.soulspace.qclojure.application.backend :as qb]
            [org.soulspace.qclojure.adapter.backend.braket :as braket]))

(deftest test-protocol-implementation
  (testing "Braket backend implements required protocols"
    (let [b (braket/create-braket-simulator)]
      (is (satisfies? qb/QuantumBackend b))
      (is (satisfies? qb/CloudQuantumBackend b)))))

(deftest test-backend-info-shape
  (testing "get-backend-info returns required keys"
    (let [b (braket/create-braket-simulator)
          info (qb/get-backend-info b)]
      (is (contains? info :backend-type))
      (is (= :cloud (:backend-type info)))
      (is (contains? info :backend-name))
      (is (set? (:supported-gates info)))
      (is (contains? info :capabilities)))))

(deftest test-job-methods-shapes
  (testing "Job methods return protocol-conformant shapes for missing job"
    (let [b (braket/create-braket-simulator)
          status (qb/get-job-status b "nonexistent")
          result (qb/get-job-result b "nonexistent")]
      (is (keyword? status))
      (is (map? result))
      (is (contains? result :job-status)))))