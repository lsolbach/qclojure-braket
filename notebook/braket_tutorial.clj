;;
;; # QClojure Braket Tutorial
;; This tutorial demonstrates how to use QClojure with the Amazon Braket backend.
;;
;; 
;;
;; Let's start by requiring the necessary namespaces

(ns braket-tutorial
  "Tutorial demonstrating Braket backend with hardware optimization and error mitigation"
  (:require [org.soulspace.qclojure.domain.circuit :as qc]
            [org.soulspace.qclojure.application.backend :as qb]
            [org.soulspace.qclojure.adapter.backend.braket :as braket]))

;;
;; ## Setup
;;
;; To use the Braket backend, you need to have an AWS account and configure your credentials.
;; Additionally, you need to create an S3 bucket to store the results of your quantum tasks.
;; See the [S3 SETUP](doc/S3_SETUP.md) document for instructions on setting up the S3 bucket and IAM roles.
;;
;; ### Create a Braket Backend
;;
;; You can create a Braket backend for either a simulator or a quantum processing unit (QPU).
;; Here, we create a backend for a Rigetti QPU. Make sure to replace the ARN with the one for your desired device.
;; The S3 bucket name must match the one you created during setup.

(def qpu-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3"
                            {:s3-bucket "my-braket-results-bucket"        ; REQUIRED
                             :s3-key-prefix "experiments/bell-states/"    ; Optional
                             :region "us-east-1"}))                       ; Optional, defaults to us-east-1

;; ### Create a Quantum Circuit
;; Let's create a simple Bell state circuit.

(def bell-circuit
  (-> (qc/create-circuit 2)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)))

;; ### Submit Circuit with Optimization.
;; You can submit the circuit to the Braket backend with options for hardware optimization and error mitigation.
;; Here we skip error mitigation for simplicity.

(def job-id
  (qb/submit-circuit qpu-backend bell-circuit
                     {:shots 10                          ; Just a few shots for testing
                      :optimize-for-device? true         ; Enable hardware optimization
                      :apply-error-mitigation? false     ; Disable error mitigation for simplicity
                      :priority :fidelity}))             ; Prioritize fidelity over speed

;; ### Check Job Status
;; You can check the status of your job using the job ID returned when you submitted the circuit.

(qb/get-job-status qpu-backend job-id)

;; This will return a keyword indicating the job status, e.g., :QUEUED, :RUNNING, :COMPLETED, etc.
;; You may need to wait a few moments and check again until the job is complete.
;;
;; ### Get Job Results
;; Once the job is complete, you can retrieve the results using the job ID.

(def results (qb/get-job-result qpu-backend job-id))

;; The results will include the measurement outcomes and any metadata related to optimization and mitigation.
;; You can access the measurement results as follows:

(:measurement-results results)

;; This will return a map of bitstrings to their respective counts.

