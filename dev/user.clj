(require '[org.soulspace.qclojure.application.algorithm.bernstein-vazirani :as bv])
(require '[org.soulspace.qclojure.adapter.backend :as qb])

; Define the Amazon Braket backend with AWS credentials
; Ensure you have set the AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables
; before running this code.
; You can set them in your terminal or IDE configuration.
(def braket-backend
  (qb/->BraketBackend
   :region "us-west-2"
   :aws-access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
   :aws-secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")))

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm braket-backend [1 0 1 0] {:shots 1000})
