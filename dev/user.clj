(require '[org.soulspace.qclojure.application.algorithm.bernstein-vazirani :as bv])
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

; Define the Amazon Braket simulation backend.
;
; The backend will use the default simulator provided by Amazon Braket.
;
; Ensure you have the necessary AWS permissions to run Braket simulations.
; You can set the AWS credentials and region in your environment variables
; or use the AWS CLI to configure them.
;
; For example, you can set them in your terminal:
; export AWS_ACCESS_KEY_ID=your_access_key
; export AWS_SECRET_ACCESS_KEY=your_secret_key
; export AWS_DEFAULT_REGION=your_region
(def braket-backend
  (braket/create-braket-simulator))

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm braket-backend [1 0 1 0] {:shots 1000})
