(ns readme)
(require '[org.soulspace.qclojure.application.algorithm.bernstein-vazirani :as bv])
(require '[org.soulspace.qclojure.application.backend :as backend])
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

; IMPORTANT: You must provide an S3 bucket for storing quantum task results.
; AWS Braket requires this for all quantum task executions.
;
; Ensure you have:
; 1. AWS credentials configured (via environment variables, CLI, or IAM roles)
; 2. Permissions to use AWS Braket
; 3. An S3 bucket with appropriate permissions in the region as the Braket device

;; Create a Braket backend with S3 configuration
;; Replace "amazon-braket-results-1234" with your actual bucket name
(def backend
  (braket/create-braket-backend {:s3-bucket "amazon-braket-results-1234"
                                 :region "us-east-1"}))

;; Select a specific device (e.g., IonQ Forte-1 QPU) for execution
(backend/select-device backend
                       "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1")

; Estimate the cost of running the Bernstein-Vazirani algorithm on the selected device
; with 100 shots. Costs will be around $0.30 per task fee + $0.08 per shot for IonQ devices,
; but may vary based on actual pricing.
(backend/estimate-cost backend
                       (bv/bernstein-vazirani-circuit [1 0 1 0])
                       {:shots 100})

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm backend
                                 [1 0 1 0]
                                 {:shots 100})
