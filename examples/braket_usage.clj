(ns braket-usage
  "Examples demonstrating enhanced Braket backend with hardware optimization and error mitigation"
  (:require [org.soulspace.qclojure.adapter.backend.braket :as braket]
            [org.soulspace.qclojure.domain.circuit :as qc]
            [org.soulspace.qclojure.application.backend :as qb]))

;;
;; Basic Usage Examples
;;

(comment
  ;; Create a Braket backend for IBM-style quantum hardware
  ;; IMPORTANT: AWS Braket requires an S3 bucket to store quantum task results
  (def qpu-backend 
    (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3"
                              {:s3-bucket "my-braket-results-bucket"        ; REQUIRED
                               :s3-key-prefix "experiments/bell-states/"    ; Optional
                               :region "us-east-1"}))

  ;; Create a test circuit
  (def bell-circuit
    (-> (qc/create-circuit 2)
        (qc/h-gate 0)
        (qc/cnot-gate 0 1)))

  ;; Submit circuit with hardware optimization and error mitigation
  (def job-id 
    (qb/submit-circuit qpu-backend bell-circuit 
                       {:shots 1000
                        :optimize-for-device? true         ; Enable hardware optimization
                        :apply-error-mitigation? true      ; Enable error mitigation
                        :resource-limit :moderate          ; Error mitigation resource limit
                        :priority :fidelity}))             ; Prioritize fidelity over speed

  ;; Check job status
  (qb/get-job-status qpu-backend job-id)

  ;; Get results when complete
  (def results (qb/get-job-result qpu-backend job-id))
  
  ;; Results now include optimization and mitigation metadata
  (:optimization-result results)  ; Hardware optimization details
  (:mitigation-result results)    ; Error mitigation strategies applied
  )

;;
;; Advanced Usage - Device-Specific Optimization
;;

(comment
  ;; Different providers have different optimization strategies
  
  ;; IonQ (trapped ion) - all-to-all connectivity
  (def ionq-backend 
    (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1"
                              {:s3-bucket "my-braket-results-bucket"}))
  
  ;; Rigetti (superconducting) - limited connectivity
  (def rigetti-backend 
    (braket/create-braket-qpu "arn:aws:braket:us-west-1::device/qpu/rigetti/aspen-m-3"
                              {:s3-bucket "my-braket-results-bucket" 
                               :region "us-west-1"}))
  
  ;; Get device-specific information
  (braket/get-device-info ionq-backend "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1")
  ;; => {:provider :ionq, :supported-gates #{:rx :ry :rz :cnot :swap :h :x :y :z :s :t :measure}, 
  ;;     :constraints {:max-qubits 32, :max-shots 10000, :native-connectivity :all-to-all}}
  
  ;; Validate circuit compatibility
  (braket/validate-circuit rigetti-backend bell-circuit)
  ;; => {:valid? true, :qubit-count 2, :max-qubits 80, :qubit-constraint-ok? true, ...}
  )

;;
;; Error Mitigation Strategies
;;

(comment
  ;; Manual error mitigation configuration
  (def mit-config {:strategies [:readout-error-mitigation     ; Correct measurement errors
                                :zero-noise-extrapolation     ; Extrapolate to zero noise
                                :circuit-optimization]        ; Reduce gate count
                   :num-shots 2000
                   :constraints {:resource-limit :abundant    ; Use more resources for better fidelity
                                 :priority :fidelity}})
  
  ;; Apply error mitigation manually
  (def mitigation-result 
    (braket/apply-error-mitigation qpu-backend bell-circuit mit-config))
  
  ;; Check what mitigation was applied
  (:mitigation-applied mitigation-result)  ; => [:readout-error-mitigation :zero-noise-extrapolation :circuit-optimization]
  (:circuit mitigation-result)             ; => Optimized and mitigation-ready circuit
  )

;;
;; Batch Processing with Optimization
;;

(comment
  ;; Create multiple circuits for batch processing
  (def circuits 
    [(-> (qc/create-circuit 2) (qc/h-gate 0) (qc/cnot-gate 0 1))  ; Bell state
     (-> (qc/create-circuit 3) (qc/h-gate 0) (qc/h-gate 1) (qc/h-gate 2))  ; GHZ preparation
     (-> (qc/create-circuit 2) (qc/x-gate 0) (qc/h-gate 1) (qc/cnot-gate 0 1))]) ; Mixed state
  
  ;; Submit batch with optimization
  (def batch-result 
    (qb/batch-submit qpu-backend circuits 
                     {:shots 500
                      :optimize-for-device? true
                      :apply-error-mitigation? true}))
  
  ;; Monitor batch progress
  (qb/get-batch-status qpu-backend (:batch-id batch-result))
  
  ;; Get all results when complete
  (qb/get-batch-results qpu-backend (:batch-id batch-result))
  )

;;
;; Cost Estimation with Provider-Specific Pricing
;;

(comment
  ;; Estimate costs for different providers
  (def cost-ionq 
    (qb/estimate-cost ionq-backend bell-circuit {:shots 1000}))
  
  (def cost-rigetti 
    (qb/estimate-cost rigetti-backend bell-circuit {:shots 1000}))
  
  ;; Compare costs
  (println "IonQ cost:" (:total-cost cost-ionq) (:currency cost-ionq))
  (println "Rigetti cost:" (:total-cost cost-rigetti) (:currency cost-rigetti))
  
  ;; Cost breakdown includes provider-specific multipliers
  (:cost-breakdown cost-ionq)  ; => {..., :provider-multiplier 1.5, :provider :ionq}
  )

;;
;; Device Selection and Topology Analysis
;;

(comment
  ;; List available devices with capabilities
  (def available-devices (qb/list-available-devices qpu-backend))
  
  ;; Select best device manually by filtering available devices
  (def best-device 
    (->> available-devices
         (filter #(= (:device-status %) :online))           ; Only online devices
         (filter #(>= (:max-qubits % 0) 2))                 ; At least 2 qubits
         (sort-by :error-rate)                              ; Sort by error rate
         first                                              ; Take the best one
         :device-id))                                       ; Get device ID
  
  ;; Get device topology for routing optimization
  (def device-topology 
    (qb/get-device-topology qpu-backend best-device))
  
  ;; Analyze connectivity
  (:coupling-map device-topology)  ; => [[0 1] [1 0] [1 2] ...]
  (:max-qubits device-topology)    ; => 80
  )

;;
;; Real-world Integration Example
;;

(comment
  ;; Complete workflow for production quantum application
  (defn run-quantum-algorithm [backend algorithm-circuit options]
    (let [;; Step 1: Validate circuit compatibility
          validation (braket/validate-circuit backend algorithm-circuit)
          
          _ (when-not (:valid? validation)
              (throw (ex-info "Circuit not compatible with device" validation)))
          
          ;; Step 2: Estimate cost
          cost-estimate (qb/estimate-cost backend algorithm-circuit options)
          
          _ (println "Estimated cost:" (:total-cost cost-estimate) (:currency cost-estimate))
          
          ;; Step 3: Submit with full optimization
          job-id (qb/submit-circuit backend algorithm-circuit 
                                    (merge options 
                                           {:optimize-for-device? true
                                            :apply-error-mitigation? true
                                            :priority :fidelity}))
          
          ;; Step 4: Monitor and retrieve results
          _ (loop [status (qb/get-job-status backend job-id)]
              (when (#{:queued :running} status)
                (Thread/sleep 5000)  ; Wait 5 seconds
                (recur (qb/get-job-status backend job-id))))
          
          results (qb/get-job-result backend job-id)]
      
      ;; Return comprehensive results
      {:job-id job-id
       :results results
       :validation validation
       :cost-estimate cost-estimate
       :optimization-applied (get-in results [:optimization-result])
       :mitigation-applied (get-in results [:mitigation-result])}))
  
  ;; Use the complete workflow
  (def algorithm-result 
    (run-quantum-algorithm qpu-backend bell-circuit {:shots 2000}))
  )
