(ns org.soulspace.qclojure.adapter.backend.braket
  "AWS Braket backend implementation for QClojure quantum computing library.
   
   This namespace provides a backend that connects QClojure to Amazon Braket
   quantum computing services, supporting both simulators and quantum hardware
   devices."
  (:require [clojure.spec.alpha :as s]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.application.format.qasm3 :as qasm3]
            [org.soulspace.qclojure.application.backend :as backend]
            [org.soulspace.qclojure.application.hardware-optimization :as hwopt]
            [org.soulspace.qclojure.domain.circuit :as circuit]
            [org.soulspace.qclojure.adapter.backend.format :as fmt]
            [org.soulspace.qclojure.adapter.backend.device :as device]
            [org.soulspace.qclojure.adapter.backend.task :as task]
            [org.soulspace.qclojure.adapter.backend.pricing :as pricing]))

;;;
;;; Specs for Data Validation
;;;
(s/def ::device-arn string?)
(s/def ::region string?)
(s/def ::max-parallel-shots pos-int?)
(s/def ::device-type #{:qpu :simulator})

;; S3 and result specs
(s/def ::client-token string?)
(s/def ::bucket string?)
(s/def ::key string?)
(s/def ::s3-location
  (s/keys :req-un [::bucket]
          :opt-un [::key]))

(comment ;; Test result formatting
  (def braket-result
    (->> "generated/results/braket-f6c3d842-8e86-416c-a92c-9008ff57d8d7-result.edn"
         (slurp)
         (edn/read-string)
         (fmt/clj-keys)))

  (fmt/clj-keys braket-result)

  (println (fmt/format-edn braket-result))
  ;
  )

;;;
;;; AWS Braket Client Configuration  
;;;
(def ^:private default-braket-config
  "Default configuration for AWS Braket client"
  {:api :braket
   :region "us-east-1"}) ; Default region - credentials-provider defaults to shared provider

(defn create-braket-client
  "Creates an AWS Braket client with optional configuration overrides.
   
   Args:
     config-overrides - Map of configuration overrides (optional)
   
   Returns:
     AWS Braket client instance
   
   Example:
     (create-braket-client {:region \"us-west-2\"})"
  ([]
   (create-braket-client {}))
  ([config-overrides]
   (let [config (merge default-braket-config config-overrides)]
     (aws/client config))))

;;;
;;; Backend State Management
;;;
(defn quantum-task
  "Get quantum task details from AWS Braket"
  [backend task-arn]
  (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                :request {:quantumTaskArn task-arn}}))]
    (println "GetQuantumTask response:" response)
    (if (:cognitect.anomalies/category response)
      {:error response}
      response)))

;;;
;;; Quantum Backend Functions
;;;
(defn backend-info
  [backend]
  {:backend-type :cloud
   :backend-name "Amazon Braket"
   :capabilities #{:mult-device :cloud :batch}
   :config (:config backend)
   :provider :aws
   :devices (:devices @(:state backend))
   :device (:current-device @(:state backend))
   :created-at (System/currentTimeMillis)})

(defn available?
  [backend]
  (let [device-arn (or (:id (:current-device @(:state backend)))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
    (try
      (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetDevice
                                                    :request {:deviceArn device-arn}}))
            _ (println "Device availability response:" response)]
        (if (:cognitect.anomalies/category response)
          false
          (= "ONLINE" (:device-status response))))
      (catch Exception _e
        false))))

(defn submit-circuit
  [backend circuit options]
  (let [device (:current-device @(:state backend))
        options (assoc options :target :braket)
        ;; Apply hardware optimization if requested
        optimization-result (hwopt/optimize circuit device options)

        optimized-circuit (:circuit optimization-result)

        ;; Transform circuit to QASM3 format for Braket
        qasm3-circuit (qasm3/circuit-to-qasm optimized-circuit options)
        action (json/write-str {:braketSchemaHeader {:name "braket.ir.openqasm.program"
                                                     :version "1"}
                                :source qasm3-circuit})
        ;; Prepare task request
        shots (get options :shots 1000)
        timestamp (System/currentTimeMillis)
        task-key (str (:s3-key-prefix (:config backend)) "task-" timestamp "-" (java.util.UUID/randomUUID))
        task-request {:deviceArn (:id device)
                      :clientToken (str "qclojure-braket-backend-" (rand-int 100))
                      :action action
                      :shots shots
                      :outputS3Bucket (:s3-bucket (:config backend))
                      :outputS3KeyPrefix task-key}
        _ (println "Submitting task request:" (json/write-str task-request))
        ;; Submit to Braket
        response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :CreateQuantumTask :request task-request}))]

    (if (:cognitect.anomalies/category response)
      {:error response}
      (let [task-arn (:quantum-task-arn response)
            job-id (str "braket-" (java.util.UUID/randomUUID))]
          ; Store the task mapping in our state with additional metadata
        (swap! (:state backend) assoc-in [:jobs job-id] {:task-arn task-arn
                                                      :submitted-at (System/currentTimeMillis)
                                                      :original-circuit circuit
                                                      :final-circuit optimized-circuit
                                                      :options options})
        job-id))))

(defn job-status
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                  :request {:quantumTaskArn task-arn}}))
          _ (println "Job status response:" response)]
      (if (:cognitect.anomalies/category response)
        :failed
        (case (:status response)
          "CREATED" :submitted
          "QUEUED" :queued
          "RUNNING" :running
          "COMPLETED" :completed
          "FAILED" :failed
          "CANCELLED" :cancelled
          :unknown)))
    :failed))

; TODO simplifix 
(defn job-result
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                  :request {:quantumTaskArn task-arn}}))
          _ (println "Job result response:" response)]
      (if (:cognitect.anomalies/category response)
        {:job-status :failed
         :job-id job-id
         :error-message (str "AWS error: " (pr-str response))}
        (if (= "COMPLETED" (:status response))
          ;; Retrieve actual results from S3
          (let [s3-results (task/retrieve-task-results (:s3-client backend) response)
                shots (get-in job-info [:options :shots] 0)
                _ (spit (str job-id "-s3results.edn") s3-results)]
            (if (:error s3-results)
              {:job-status :failed
               :job-id job-id
               :error-message (get-in s3-results [:error :message])}
              (let [meas (:measurements s3-results)
                    ;; Normalize to QClojure's :measurement-results map {"bitstring" count}
                    measurement-results (cond
                                          (map? meas) meas
                                          (sequential? meas)
                                          (->> meas
                                               (map (fn [bits]
                                                      (->> bits
                                                           (map str)
                                                           (apply str))))
                                               (frequencies))
                                          :else {})

                    ;; Build base Braket result
                    braket-result {:job-status :completed
                                   :job-id job-id
                                   :measurement-results measurement-results
                                   :probabilities (:probabilities s3-results)
                                   :shots shots
                                   :execution-time-ms (- (System/currentTimeMillis)
                                                         (:submitted-at job-info))
                                   :task-arn task-arn
                                   :raw-results (:raw-results s3-results)
                                   :task-metadata (:task-metadata s3-results)
                                   :s3-location (:s3-location s3-results)}
                    _ (spit (str job-id "-braket-result.edn") braket-result)
                    ;; Convert to QClojure format
                    result (task/convert-braket-results braket-result job-info)
                    _ (spit (str job-id "-result.edn") (fmt/format-edn result))
                    ]
                result)))
          {:job-status :running
           :job-id job-id
           :message "Job not completed yet"})))
    {:job-status :failed
     :job-id job-id
     :error-message "Job not found"}))


(defn cancel-job
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)]
      (try
        (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :CancelQuantumTask
                                                      :request {:quantumTaskArn task-arn}}))]
          (if (:cognitect.anomalies/category response)
            :cannot-cancel
            (do
              (swap! (:state backend) assoc-in [:jobs job-id :cancelled-at] (System/currentTimeMillis))
              :cancelled)))
        (catch Exception _e
          :cannot-cancel)))
    :not-found))

(defn queue-status
  [backend]
  (let [device-arn (or (:id (:current-device @(:state backend)))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
    (try
      (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetDevice
                                                    :request {:deviceArn device-arn}}))]
        (if (:cognitect.anomalies/category response)
          {:error response}
          (let [queue-info (:device-queue-info response)]
            {:device-arn device-arn
             :queue-type (:queue-type queue-info)
             :queue-size (:queue-size queue-info)
             :priority (:queue-priority queue-info)
             :status (:device-status response)})))
      (catch Exception e
        {:error {:message (.getMessage e) :type :api-error}}))))

;;;
;;; Cloud Backend Functions
;;;
(defn authenticate
  [backend]
  ;; For AWS, we rely on the default credentials provider chain
  ;; Optionally, we could accept explicit credentials here
  {:status :authenticated
   :region (:region (:config backend))
   :authenticated-at (System/currentTimeMillis)})

(defn session-info
  [backend]
  {:status :authenticated
   :session-id (str "braket-session-" (java.util.UUID/randomUUID))
   :region (:region (:config backend))
   :device-arn (:device-arn (:config backend))
   :active-jobs (count (get-in @(:state backend) [:jobs]))
   :active-batches (count (get-in @(:state backend) [:batches]))
   :session-start (System/currentTimeMillis)})

;;;
;;; Batch Job Functions
;;;
(defn batch-status
  [backend batch-id]
  (if-let [batch-info (get-in @(:state backend) [:batches batch-id])]
    (let [job-statuses (doall (map #(backend/job-status backend %) (:job-ids batch-info)))
          completed (count (filter #(= :completed %) job-statuses))
          failed (count (filter #(= :failed %) job-statuses))
          running (count (filter #(#{:running :queued :submitted} %) job-statuses))]
      {:batch-id batch-id
       :total-jobs (count (:job-ids batch-info))
       :completed completed
       :failed failed
       :running running
       :overall-status (cond
                         (= completed (count job-statuses)) :completed
                         (> failed 0) :partially-failed
                         (> running 0) :running
                         :else :unknown)
       :job-statuses job-statuses})
    {:error "Batch not found"}))

(defn batch-results
  [backend batch-id]
  (if-let [batch-info (get-in @(:state backend) [:batches batch-id])]
    (let [job-ids (:job-ids batch-info)
          results (map #(backend/job-result backend %) job-ids)]
      {:batch-id batch-id
       :total-jobs (count job-ids)
       :results results
       :completed-at (System/currentTimeMillis)})
    {:error "Batch not found"}))

(defn batch-submit
  [backend circuits options]
  (let [batch-id (str "batch-" (java.util.UUID/randomUUID))
        max-parallel (get-in (:config backend) [:max-parallel-shots] 10)
        chunks (partition-all max-parallel circuits)]
    (loop [chunks chunks
           job-ids []
           chunk-index 0]
      (if (seq chunks)
        (let [chunk (first chunks)
              chunk-jobs (doall
                          (map-indexed
                           (fn [i circuit]
                             (let [job-options (assoc options :batch-id batch-id
                                                      :chunk-index chunk-index
                                                      :circuit-index i)]
                               (backend/submit-circuit backend circuit job-options)))
                           chunk))]
          (recur (rest chunks)
                 (concat job-ids chunk-jobs)
                 (inc chunk-index)))
            ; Store batch information
        (do
          (swap! (:state backend) assoc-in [:batches batch-id]
                 {:job-ids job-ids
                  :submitted-at (System/currentTimeMillis)
                  :total-circuits (count circuits)
                  :status :submitted})
          {:batch-id batch-id
           :job-ids job-ids
           :total-circuits (count circuits)})))))

;;;
;;; BraketBackend Implementation
;;;
(defrecord BraketBackend [state config braket-client s3-client pricing-client session-info]
  ;; Basic backend info
  Object
  (toString [_this]
    (str "BraketBackend{region=" (get-in config [:region])
         ", device=" (get-in config [:device-arn] "default-simulator") "}"))

  ;; QuantumBackend protocol implementation
  backend/QuantumBackend
  (backend-info [this]
    (backend-info this))

  (device [this]
    (device/device this))

  (available? [this]
    (available? this))

  (submit-circuit [this circuit options]
    (submit-circuit this circuit options))

  (job-status [this job-id]
    (job-status this job-id))

  (job-result [this job-id]
    (job-result this job-id))

  (cancel-job [this job-id]
    (cancel-job this job-id))

  (queue-status [this]
    (queue-status this))

  ;; MultiDeviceBackend protocol implementation  
  backend/MultiDeviceBackend
  (devices [this]
    (device/devices this))

  (select-device [this device]
    (device/select-device this device))

  ;; CloudQuantumBackend protocol implementation  
  backend/CloudQuantumBackend
  (authenticate [this _credentials]
    (authenticate this))

  (session-info [this]
    (session-info this))

  (estimate-cost [this circuits options]
    (pricing/estimate-cost this circuits options))

  (estimate-cost [this circuits device options]
    (let [device-arn (if (string? device) device (:id device))]
      (pricing/estimate-cost this circuits device-arn options)))

  backend/BatchJobBackend
  (batch-status [this batch-id]
    (batch-status this batch-id))

  (batch-results [this batch-id]
    (batch-results this batch-id))

  (batch-submit [this circuits options]
    (batch-submit this circuits options)))

;;;
;;; Backend Creation Functions
;;;
(defn create-braket-backend
  "Create a new Braket backend instance.
   
   Parameters:
     config - Configuration map with keys:
       :region - AWS region (optional, defaults to us-east-1)
       :device-arn - ARN of the Braket device (optional, defaults to simulator) 
       :shots - Default number of shots (optional, defaults to 1000)
       :max-parallel-shots - Maximum parallel jobs (optional, defaults to 10)
       :device-type - Type of device :quantum or :simulator (optional)
       :s3-bucket - S3 bucket for storing Braket task results (REQUIRED)
       :s3-key-prefix - S3 key prefix for organizing results (optional, defaults to \"braket-results/\")
   
   Returns:
     BraketBackend instance
   
   Example:
     (create-braket-backend {:region \"us-west-2\" 
                            :device-arn \"arn:aws:braket:::device/quantum-simulator/amazon/sv1\"
                            :s3-bucket \"my-braket-results-bucket\"
                            :s3-key-prefix \"experiments/\"
                            :shots 1000})
   
   Note:
     AWS Braket requires an S3 bucket to store quantum task results. The S3 bucket must exist
     and the AWS credentials must have read/write permissions to it."
  ([]
   (create-braket-backend {}))
  ([config]
   ;; TODO generate clientToken, if not provided
   (when (nil? (:s3-bucket config))
     (throw (ex-info "S3 bucket is required for Braket backend. AWS Braket stores all quantum task results in S3."
                     {:type :missing-s3-bucket
                      :config config
                      :help "Provide :s3-bucket in the config map, e.g., {:s3-bucket \"amazon-braket-results-1234\"}"})))

    (let [merged-config (merge {:region "us-east-1"
                                :shots 1000
                                :max-parallel-shots 10
                                :s3-key-prefix "braket-results/"}
                               config)]
      (map->BraketBackend {:state (atom {:job-counter 0
                                         :active-jobs {}
                                         :devices []
                                         :current-device nil})
                           :config merged-config
                           :braket-client (create-braket-client merged-config)
                           :s3-client (task/create-s3-client merged-config)
                           :pricing-client (pricing/create-pricing-client merged-config)
                           :session-info {}}))))

(defn create-braket-simulator
  "Create a Braket simulator backend for local testing.
   
   Parameters:
     config - Configuration map that must include :s3-bucket
   
   Returns:
     BraketBackend configured for the default AWS Braket simulator
   
   Example:
     (create-braket-simulator {:s3-bucket \"my-braket-results\"})"
  ([config]
   (create-braket-backend (merge {:device-arn "arn:aws:braket:::device/quantum-simulator/amazon/sv1"
                                  :device-type :simulator
                                  :region "us-east-1"}
                                 config))))

(defn create-braket-qpu
  "Create a Braket QPU backend for quantum hardware execution.

   Parameters:
     device-arn - ARN of the specific QPU device
     config - Configuration map that must include :s3-bucket and can include:
       :region - AWS region where the device is located (optional, defaults to us-east-1)
   
   Returns:
     BraketBackend configured for the specified QPU
     
   Example:
     (create-braket-qpu \"arn:aws:braket:us-east-1::device/qpu/rigetti/Aspen-M-3\"
                        {:s3-bucket \"my-braket-results\"
                         :region \"us-east-1\"})"
  [device-arn config]
  (create-braket-backend (merge {:device-arn device-arn
                                 :device-type :quantum
                                 :region "us-east-1"}
                                config)))

(comment
  ;; REPL experimentation and testing
  
  ;; To use this code, ensure you have the necessary AWS credentials configured
  ;; and replace the S3 bucket name with your own.
  
  ;; First create a braket backend instance
  (def backend (create-braket-backend {:s3-bucket "amazon-braket-results-1207"}))
  (def backend (create-braket-backend {:s3-bucket "amazon-braket-results-1207"
                                       :region "eu-north-1"})) 
  (require '[org.soulspace.qclojure.adapter.backend.ideal-simulator :as ideal])
  (def backend (ideal/create-simulator))

  backend

  ;; Enable/disable request validation for debugging
  (aws/validate-requests (:braket-client backend) true)
  (aws/validate-requests (:braket-client backend) false)

  ;; Create a Bell state circuit
  (def bell-circuit (-> (circuit/bell-state-circuit)
                        (circuit/measure-all-operation)))

  (backend/devices backend)
  (map :id (:devices @(:state backend)))

  (backend/select-device backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
  (backend/select-device backend "arn:aws:braket:::device/quantum-simulator/amazon/dm1")
  (backend/select-device backend "arn:aws:braket:::device/quantum-simulator/amazon/tn1")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-Enterprise-1")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Aria-1")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Aria-2")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Harmony")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/quera/Aquila")
  (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/xanadu/Borealis")
  (backend/select-device backend "arn:aws:braket:eu-north-1::device/qpu/iqm/Garnet")
  (backend/select-device backend "arn:aws:braket:eu-north-1::device/qpu/iqm/Emerald")

  (backend/available? backend)

  (backend/device backend)
  (device/device-info backend)

  (fmt/save-formatted-edn "dev/devices/SV1.edn" (device/device-info backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1"))
  (fmt/save-formatted-edn "dev/devices/DM1.edn" (device/device-info backend "arn:aws:braket:::device/quantum-simulator/amazon/dm1"))
  (fmt/save-formatted-edn "dev/devices/TN1.edn" (device/device-info backend "arn:aws:braket:::device/quantum-simulator/amazon/tn1"))
  (fmt/save-formatted-edn "dev/devices/Forte-1.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1"))
  (fmt/save-formatted-edn "dev/devices/Forte-Enterprise-1.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-Enterprise-1"))
  (fmt/save-formatted-edn "dev/devices/Aria-1.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Aria-1"))
  (fmt/save-formatted-edn "dev/devices/Aria-2.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Aria-2"))
  (fmt/save-formatted-edn "dev/devices/Aquila.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/quera/Aquila"))
  (fmt/save-formatted-edn "dev/devices/Borealis.edn" (device/device-info backend "arn:aws:braket:us-east-1::device/qpu/xanadu/Borealis"))
  (fmt/save-formatted-edn "dev/devices/Garnet.edn" (device/device-info backend "arn:aws:braket:eu-north-1::device/qpu/iqm/Garnet"))
  (fmt/save-formatted-edn "dev/devices/Emerald.edn" (device/device-info backend "arn:aws:braket:eu-north-1::device/qpu/iqm/Emerald"))

  (quantum-task backend "arn:aws:braket:us-east-1:579360542232:quantum-task/d02cb431-1820-4ad4-bf49-76441d0ee945")

  ;; Test QPU pricing
  (backend/estimate-cost backend bell-circuit {:shots 1000})

  ;; (backend/calibration-data backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
  
  (def options {:shots 100
                :result-specs {;:probability {:targets [[0 0] [1 1]]},
                               ;:amplitude {:basis-states [0 3]},
                               ;:state-vector true,
                               ;:density-matrix true
                               ;:sample {:shots 20}
                               }})

  (let [job-id (backend/submit-circuit backend bell-circuit options)]
    (println "Submitted job:" job-id)
    (Thread/sleep 5000)
    (println "Job status:" (backend/job-status backend job-id))
    (Thread/sleep 20000)
    (println "Job result:" (backend/job-result backend job-id)))

  (println "Job status:" (job-status backend "braket-dfe7250b-7c78-45fb-850d-28c95478abc4"))
  (println "Job result:" (job-result backend "braket-dfe7250b-7c78-45fb-850d-28c95478abc4"))

  ;; Cancel job
  (println "Job status:" (cancel-job backend ""))

  (slurp "dev/req.json")
  (aws/doc (:braket-client backend) :CreateQuantumTask)

  (let [response (fmt/clj-keys (aws/invoke (:braket-client backend)
                                             {:op :CreateQuantumTask
                                              :request (slurp "dev/req.json")}))]
    (println "CreateQuantumTask response:" response))

  ;; Test conversion with the provided result
  (def sample-braket-result
    (edn/read-string (slurp "dev/simple_braket_result1.edn")))

  (def sample-job-info
    {:original-circuit bell-circuit
     :submitted-at 1760536661569
     :options {:shots 10}})
  
  (task/convert-braket-results sample-braket-result sample-job-info)
  ;; Should produce:
  ;; {:job-status :completed
  ;;  :job-id "braket-5839488b-b7f7-46ac-a3b1-23ebf4d40b48"
  ;;  :circuit <bell-circuit>
  ;;  :circuit-metadata {:circuit-depth 2 ...}
  ;;  :shots-executed 10
  ;;  :execution-time-ms 4480553
  ;;  :results {:measurement-results {"00" 6, "11" 4}
  ;;            :probabilities {:00 0.6, :11 0.4}
  ;;            :empirical-probabilities {:00 0.6, :11 0.4}
  ;;            :source :braket-hardware
  ;;            :task-arn "..."
  ;;            :s3-location {...}
  ;;            :task-metadata {...}
  ;;            :raw-results {...}}}
  
  ;
  )