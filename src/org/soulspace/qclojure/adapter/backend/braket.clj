(ns org.soulspace.qclojure.adapter.backend.braket
  "AWS Braket backend implementation for QClojure quantum computing library.
   
   This namespace provides a backend that connects QClojure to Amazon Braket
   quantum computing services, supporting both simulators and quantum hardware
   devices."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.application.format.qasm3 :as qasm3]
            [org.soulspace.qclojure.application.backend :as backend]
            [org.soulspace.qclojure.application.hardware-optimization :as hwopt]))

;;;
;;; Specs for Data Validation
;;;
(s/def ::device-arn string?)
(s/def ::region string?)
(s/def ::max-parallel-shots pos-int?)
(s/def ::device-type #{:quantum :simulator})

;; S3 and result specs
(s/def ::bucket string?)
(s/def ::key string?)
(s/def ::s3-location
  (s/keys :req-un [::bucket]
          :opt-un [::key]))

;; Pricing specs
(s/def ::price-per-task (s/double-in 0.0 1000.0))
(s/def ::price-per-shot (s/double-in 0.0 1.0))
(s/def ::currency string?)
(s/def ::pricing-data
  (s/keys :req-un [::price-per-task ::price-per-shot ::currency]
          :opt-un [::last-updated ::device-type]))

;;;
;;; AWS Braket Client Configuration  
;;;
(def ^:private default-braket-config
  "Default configuration for AWS Braket client"
  {:api :braket
   :region "us-east-1"}) ; Default region - credentials-provider defaults to shared provider

(def ^:private default-s3-config
  "Default configuration for AWS S3 client"
  {:api :s3
   :region "us-east-1"})

(def ^:private default-pricing-config
  "Default configuration for AWS Pricing client"
  {:api :pricing
   :region "us-east-1"}) ; Pricing API is available in us-east-1 and ap-south-1

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

(defn create-s3-client
  "Creates an AWS S3 client with optional configuration overrides.
   
   Args:
     config-overrides - Map of configuration overrides (optional)
   
   Returns:
     AWS S3 client instance"
  ([]
   (create-s3-client {}))
  ([config-overrides]
   (let [config (merge default-s3-config config-overrides)]
     (aws/client config))))

(defn create-pricing-client
  "Creates an AWS Pricing client with optional configuration overrides.
   
   Args:
     config-overrides - Map of configuration overrides (optional)
   
   Returns:
     AWS Pricing client instance
     
   Note:
     The Pricing API is only available in us-east-1 and ap-south-1 regions"
  ([]
   (create-pricing-client {}))
  ([config-overrides]
   (let [config (merge default-pricing-config config-overrides)]
     (aws/client config))))

;;;
;;; Multi-QPU Device Management Functions
;;;
;; Pricing multipliers by provider (relative to base cost)
(def ^:private provider-pricing-multipliers
  "Cost multipliers for different QPU providers"
  {:rigetti 1.2   ; Superconducting QPUs
   :ionq 1.5      ; Trapped ion systems
   :iqm 1.1       ; European superconducting 
   :oqc 2.0       ; Photonic systems (premium)
   :quera 0.8     ; Neutral atom (experimental pricing)
   :amazon 1.0    ; Amazon's own devices
   :simulator 0.1 ; Simulators are much cheaper
   :default 1.0})

(defn- provider-pricing-multiplier
  "Get pricing multiplier for a specific provider"
  [provider]
  (get provider-pricing-multipliers
       provider
       (:default provider-pricing-multipliers)))

;;
;; AWS Pricing Functions
;;
(defn- query-braket-pricing
  "Query AWS Pricing API for Braket service pricing"
  [pricing-client service-code region _device-type]
  (try
    (let [filters [{:Type "TERM_MATCH"
                    :Field "ServiceCode"
                    :Value service-code}
                   {:Type "TERM_MATCH"
                    :Field "Location"
                    :Value (or region "US East (N. Virginia)")}]
          response (aws/invoke pricing-client {:op :GetProducts
                                               :request {:ServiceCode service-code
                                                         :Filters filters
                                                         :MaxResults 100}})]
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:products (:PriceList response)}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :pricing-api-error}})))

(defn- parse-braket-pricing
  "Parse Braket pricing from AWS Pricing API response"
  [pricing-products device-type]
  (try
    (let [products (map #(json/read-str % :key-fn keyword) pricing-products)
          task-pricing (atom {:price-per-task 0.0 :price-per-shot 0.0})
          currency (atom "USD")]

      (doseq [product products]
        (let [product-attrs (:attributes (:product product))
              pricing-dims (-> product :terms :OnDemand vals first :priceDimensions vals first)
              price-per-unit (-> pricing-dims :pricePerUnit :USD)
              unit (:unit pricing-dims)]

          (when price-per-unit
            (reset! currency "USD")
            (cond
              (and (= unit "Request")
                   (str/includes? (str (:usagetype product-attrs)) "Task"))
              (swap! task-pricing assoc :price-per-task (Double/parseDouble price-per-unit))

              (and (= unit "Shot")
                   (str/includes? (str (:usagetype product-attrs)) "Shot"))
              (swap! task-pricing assoc :price-per-shot (Double/parseDouble price-per-unit))))))

      {:price-per-task (:price-per-task @task-pricing)
       :price-per-shot (:price-per-shot @task-pricing)
       :currency @currency
       :last-updated (System/currentTimeMillis)
       :device-type device-type})

    (catch Exception e
      {:error {:message (.getMessage e)
               :type :pricing-parse-error}})))

(defn- braket-pricing
  "Get cached or fresh pricing data for Braket services"
  [backend device-type region]
  (let [cache-key (str device-type "-" region)
        cached-pricing (get-in @(:state backend) [:pricing-cache cache-key])
        cache-age (when cached-pricing
                    (- (System/currentTimeMillis) (:last-updated cached-pricing)))
        cache-valid? (and cached-pricing (< cache-age (* 24 60 60 1000)))] ; 24 hours

    (if cache-valid?
      cached-pricing
      ;; Fetch fresh pricing data
      (if-let [pricing-client (:pricing-client backend)]
        (let [service-code "AmazonBraket"
              pricing-response (query-braket-pricing pricing-client service-code region device-type)]
          (if (:error pricing-response)
            ;; Fallback to estimated pricing on error
            {:price-per-task (if (= device-type :simulator) 0.075 0.30)
             :price-per-shot (if (= device-type :simulator) 0.0 0.00019)
             :currency "USD"
             :last-updated (System/currentTimeMillis)
             :device-type device-type
             :source :fallback}
            (let [parsed-pricing (parse-braket-pricing (:products pricing-response) device-type)]
              (if (:error parsed-pricing)
                ;; Fallback on parse error
                {:price-per-task (if (= device-type :simulator) 0.075 0.30)
                 :price-per-shot (if (= device-type :simulator) 0.0 0.00019)
                 :currency "USD"
                 :last-updated (System/currentTimeMillis)
                 :device-type device-type
                 :source :fallback}
                (do
                  ;; Cache the successful result
                  (swap! (:state backend) assoc-in [:pricing-cache cache-key]
                         (assoc parsed-pricing :source :api))
                  (assoc parsed-pricing :source :api))))))
        ;; No pricing client, use fallback
        {:price-per-task (if (= device-type :simulator) 0.075 0.30)
         :price-per-shot (if (= device-type :simulator) 0.0 0.00019)
         :currency "USD"
         :last-updated (System/currentTimeMillis)
         :device-type device-type
         :source :fallback}))))

;;
;; S3 Result Retrieval Functions
;;
(defn- parse-s3-location
  "Parse S3 bucket and key from Braket task response"
  [task-response]
  (when-let [output-s3-bucket (:outputS3Bucket task-response)]
    (let [output-s3-dir (:outputS3Directory task-response)]
      {:bucket output-s3-bucket
       :key-prefix (str output-s3-dir "/")
       :results-key (str output-s3-dir "/results.json")
       :task-metadata-key (str output-s3-dir "/task-metadata.json")})))

(defn- download-s3-object
  "Download an object from S3 and return its content as a string"
  [s3-client bucket key]
  (try
    (let [response (aws/invoke s3-client {:op :GetObject
                                          :request {:Bucket bucket
                                                    :Key key}})]
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:content (slurp (:Body response))}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :s3-error
               :bucket bucket
               :key key}})))

(defn- parse-braket-results
  "Parse Braket quantum task results from JSON"
  [results-json]
  (try
    (let [results (json/read-str results-json :key-fn keyword)
          measurements (:measurements results)
          measurement-counts (:measurementCounts results)
          measurement-probabilities (:measurementProbabilities results)]

      {:raw-results results
       :measurements (or measurements measurement-counts)
       :probabilities measurement-probabilities
       :task-metadata (:taskMetadata results)
       :additional-metadata (:additionalMetadata results)})
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :json-parse-error
               :content results-json}})))

(defn- retrieve-task-results
  "Retrieve and parse results from S3 for a completed Braket task"
  [s3-client task-response]
  (if-let [s3-location (parse-s3-location task-response)]
    (let [{:keys [bucket results-key]} s3-location
          results-download (download-s3-object s3-client bucket results-key)]

      (if (:error results-download)
        {:error (:error results-download)}
        (let [parsed-results (parse-braket-results (:content results-download))]
          (if (:error parsed-results)
            {:error (:error parsed-results)}
            (merge parsed-results
                   {:s3-location s3-location
                    :retrieved-at (System/currentTimeMillis)})))))
    {:error {:message "No S3 output location found in task response"
             :type :missing-s3-location}}))

;;;
;;; Backend State Management
;;;
(defonce backend-state
  (atom {:job-counter 0
         :active-jobs {}
         :devices []
         :current-device nil}))

;;;
;;; Device Management Helpers
;;;
(defn- parse-device-info
  "Parse device information from AWS Braket device ARN"
  [device-arn]
  (when device-arn
    (let [arn-parts (str/split device-arn #":")
          device-path (last arn-parts)
          path-parts (str/split device-path #"/")]
      (when (>= (count path-parts) 4)
        (let [[device-type provider device-name] (drop 1 path-parts)]
          {:device-type (keyword device-type)
           :provider (keyword provider)
           :device-name device-name
           :arn device-arn})))))

(defn braket-device
  [braket-device]
  {:id (:device-id braket-device)
   :name (:device-name braket-device)
   :status (:device-status braket-device)
   :type (:device-type braket-device)
   :provider (:provider braket-device)})

; TODO use function from backend with qclojure 0.17.0 
(defn- cache-devices!
  "Cache the list of available devices for 5 minutes to avoid excessive API calls"
  [_backend devices]
  (swap! backend-state assoc
         :devices devices
         :last-devices-refresh (System/currentTimeMillis)))

(defn- cached-devices
  "Get cached devices if still valid (less than 5 minutes old)"
  [_backend]
  (let [{:keys [devices last-devices-refresh]} @backend-state
        cache-age (- (System/currentTimeMillis) (or last-devices-refresh 0))
        cache-valid? (< cache-age (* 5 60 1000))] ; 5 minutes
    (when cache-valid?
      devices)))

(defn- braket-devices
  "Call AWS Braket API to list available devices"
  [client]
  (try
    (println "Fetching devices from AWS Braket...")
    (let [response (aws/invoke client {:op :SearchDevices
                                       :request {:filters []}})]
      (println "Braket devices response:" response)
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:devices (map braket-device (:devices response))}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :api-error}})))
;;;
;;; BraketBackend Implementation
;;;
(defrecord BraketBackend [client s3-client pricing-client config state session-info]
  ;; Basic backend info
  Object
  (toString [_this]
    (str "BraketBackend{region=" (get-in config [:region])
         ", device=" (get-in config [:device-arn] "default-simulator") "}"))

  ;; QuantumBackend protocol implementation
  backend/QuantumBackend
  (backend-info [_this]
    "Get information about this backend instance (conforms to ::backend-info)"
    {:backend-type :cloud
     :backend-name "Amazon Braket"
     :capabilities #{:mult-device :cloud :batch}
     :backend-config config
     :provider :aws
     :region (:region config)
     :device-arn (:device-arn config)
     :device-type (:device-type config)
     :default-shots (:shots config)
     :max-parallel-shots (:max-parallel-shots config)
     :created-at (System/currentTimeMillis)})

  (device [_this] (:current-device @backend-state))

  (submit-circuit [_this circuit options]
    (let [device (:current-device @backend-state)

          ;; Apply hardware optimization if requested
          optimization-result (hwopt/optimize circuit device options)

          optimized-circuit (:circuit optimization-result)

          ;; Transform circuit to QASM3 format for Braket
          qasm3-circuit (qasm3/circuit-to-qasm optimized-circuit)

          ;; Prepare task request
          shots (get options :shots 1000)
          timestamp (System/currentTimeMillis)
          task-key (str (:s3-key-prefix config) "task-" timestamp "-" (java.util.UUID/randomUUID))
          task-request {:deviceArn (:id device)
                        :action {:source qasm3-circuit
                                 :sourceType "OPENQASM_3"}
                        :shots shots
                        :outputS3Bucket (:s3-bucket config)
                        :outputS3KeyPrefix task-key}

          ;; Submit to Braket
          response (aws/invoke client {:op :CreateQuantumTask :request task-request})]

      (if (:cognitect.anomalies/category response)
        {:error response}
        (let [task-arn (:quantumTaskArn response)
              job-id (str "braket-" (java.util.UUID/randomUUID))]
          ; Store the task mapping in our state with additional metadata
          (swap! state assoc-in [:jobs job-id] {:task-arn task-arn
                                                :submitted-at (System/currentTimeMillis)
                                                :original-circuit circuit
                                                :final-circuit optimized-circuit
                                                :options options})
          job-id))))

  (job-status [_this job-id]
    "Get the status of a submitted job (keyword per protocol)"
    (if-let [job-info (get-in @state [:jobs job-id])]
      (let [task-arn (:task-arn job-info)
            response (aws/invoke client {:op :GetQuantumTask
                                         :request {:quantumTaskArn task-arn}})]
        (if (:cognitect.anomalies/category response)
          :failed
          (case (:quantumTaskStatus response)
            "CREATED" :submitted
            "QUEUED" :queued
            "RUNNING" :running
            "COMPLETED" :completed
            "FAILED" :failed
            "CANCELLED" :cancelled
            :unknown)))
      :failed))

  (job-result [_this job-id]
    "Get the results of a completed job (protocol-conformant map)"
    (if-let [job-info (get-in @state [:jobs job-id])]
      (let [task-arn (:task-arn job-info)
            response (aws/invoke client {:op :GetQuantumTask
                                         :request {:quantumTaskArn task-arn}})]
        (if (:cognitect.anomalies/category response)
          {:job-status :failed
           :job-id job-id
           :error-message (str "AWS error: " (pr-str response))}
          (if (= "COMPLETED" (:quantumTaskStatus response))
            ;; Retrieve actual results from S3
            (let [s3-results (retrieve-task-results s3-client response)
                  shots (get-in job-info [:options :shots] 0)]
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
                                            :else {})]
                  {:job-status :completed
                   :job-id job-id
                   :measurement-results measurement-results
                   :probabilities (:probabilities s3-results)
                   :shots shots
                   :execution-time-ms (- (System/currentTimeMillis)
                                         (:submitted-at job-info))
                   :task-arn task-arn
                   :raw-results (:raw-results s3-results)
                   :task-metadata (:task-metadata s3-results)
                   :s3-location (:s3-location s3-results)})))
            {:job-status :running
             :job-id job-id
             :message "Job not completed yet"})))
      {:job-status :failed
       :job-id job-id
       :error-message "Job not found"}))

  (queue-status [_this]
    "Get queue status information for the configured device"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
      (try
        (let [response (aws/invoke client {:op :GetDevice
                                           :request {:deviceArn device-arn}})]
          (if (:cognitect.anomalies/category response)
            {:error response}
            (let [queue-info (:deviceQueueInfo response)]
              {:device-arn device-arn
               :queue-type (:queueType queue-info)
               :queue-size (:queueSize queue-info)
               :priority (:queuePriority queue-info)
               :status (:deviceStatus response)})))
        (catch Exception e
          {:error {:message (.getMessage e) :type :api-error}}))))

  (available? [_this]
    "Check if the backend is available for job submission"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
      (try
        (let [response (aws/invoke client {:op :GetDevice
                                           :request {:deviceArn device-arn}})]
          (if (:cognitect.anomalies/category response)
            false
            (= "ONLINE" (:deviceStatus response))))
        (catch Exception _e
          false))))

  (cancel-job [_this job-id]
    "Cancel a running job"
    (if-let [job-info (get-in @state [:jobs job-id])]
      (let [task-arn (:task-arn job-info)]
        (try
          (let [response (aws/invoke client {:op :CancelQuantumTask
                                             :request {:quantumTaskArn task-arn}})]
            (if (:cognitect.anomalies/category response)
              :cannot-cancel
              (do
                (swap! state assoc-in [:jobs job-id :cancelled-at] (System/currentTimeMillis))
                :cancelled)))
          (catch Exception _e
            :cannot-cancel)))
      :not-found))

  ;; MultiDeviceBackend protocol implementation  
  backend/MultiDeviceBackend
  (devices [this]
    "List all available Braket devices (returns collection as per protocol)"
    (let [raw-devices (if-let [cached (cached-devices this)]
                        cached
                        (let [result (braket-devices client)]
                          (:devices result)))
          normalized (map (fn [d]
                            {:device-id (:deviceArn d)
                             :device-name (:deviceName d)
                             :device-status (case (:deviceStatus d)
                                              "ONLINE" :online
                                              "OFFLINE" :offline
                                              "RETIRED" :maintenance
                                              :unknown)
                             :device-type (keyword (str/lower-case (or (:deviceType d) "unknown")))
                             :provider (keyword (or (:providerName d) "aws"))})
                          (or raw-devices []))]
      (when (and (seq normalized) (nil? (cached-devices this)))
        (cache-devices! this normalized))
      normalized))

  (select-device [_this device]
    (let [device (if (string? device)
                   (some (fn [d] (when (= (:id d) device) d))
                         (:devices @backend-state))
                   device)]
      (swap! backend-state assoc :current-device device)
      (:current-device @backend-state)))

  ;; CloudQuantumBackend protocol implementation  
  backend/CloudQuantumBackend
  (authenticate [_this _credentials]
    "AWS Braket uses AWS credentials, not separate authentication"
    {:status :authenticated
     :region (:region config)
     :authenticated-at (System/currentTimeMillis)})

  (session-info [_this]
    {:status :authenticated
     :session-id (str "braket-session-" (java.util.UUID/randomUUID))
     :region (:region config)
     :device-arn (:device-arn config)
     :active-jobs (count (get-in @state [:jobs]))
     :active-batches (count (get-in @state [:batches]))
     :session-start (System/currentTimeMillis)})

  (estimate-cost [this circuits options]
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
          shots (get options :shots 1000)
          circuit-count (if (sequential? circuits) (count circuits) 1)
          total-shots (* circuit-count shots)
          device-type (if (str/includes? device-arn "simulator") :simulator :quantum)
          region (:region config "us-east-1")
          pricing-data (braket-pricing this device-type region)
          base-cost (:price-per-task pricing-data)
          shot-cost (:price-per-shot pricing-data)
          circuit-complexity (get (if (sequential? circuits) (first circuits) circuits) :gate-count 10)
          ;; Enhanced: Apply provider-specific pricing multiplier
          device-info (parse-device-info device-arn)
          provider-multiplier (if device-info
                                (provider-pricing-multiplier (:provider device-info))
                                1.0)
          adjusted-base-cost (* base-cost provider-multiplier)
          adjusted-shot-cost (* shot-cost provider-multiplier)]
      {:total-cost (+ (* circuit-count adjusted-base-cost)
                      (* total-shots adjusted-shot-cost))
       :currency (:currency pricing-data)
       :cost-breakdown {:base-cost-per-task adjusted-base-cost
                        :shot-cost-per-shot adjusted-shot-cost
                        :total-tasks circuit-count
                        :total-shots total-shots
                        :task-cost (* circuit-count adjusted-base-cost)
                        :shot-cost (* total-shots adjusted-shot-cost)
                        :provider-multiplier provider-multiplier
                        :original-base-cost base-cost
                        :original-shot-cost shot-cost
                        :device-type device-type
                        :provider (get device-info :provider :unknown)
                        :complexity-factor circuit-complexity}
       ;; legacy fields retained for convenience
       :estimated-cost-usd (+ (* circuit-count adjusted-base-cost)
                              (* total-shots adjusted-shot-cost))
       :pricing-source (:source pricing-data)
       :last-updated (:last-updated pricing-data)}))

  backend/BatchJobBackend
  (batch-status [this batch-id]
    "Get status of a batch operation"
    (if-let [batch-info (get-in @(:state this) [:batches batch-id])]
      (let [job-statuses (doall (map #(backend/job-status this %) (:job-ids batch-info)))
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

  (batch-results [this batch-id]
    "Get results of a completed batch"
    (if-let [batch-info (get-in @backend-state [:batches batch-id])]
      (let [job-ids (:job-ids batch-info)
            results (map #(backend/job-result this %) job-ids)]
        {:batch-id batch-id
         :total-jobs (count job-ids)
         :results results
         :completed-at (System/currentTimeMillis)})
      {:error "Batch not found"}))

  (batch-submit [this circuits options]
    "Submit multiple circuits as a batch"
    (let [batch-id (str "batch-" (java.util.UUID/randomUUID))
          max-parallel (get-in (:config this) [:max-parallel-shots] 10)
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
                                 (backend/submit-circuit this circuit job-options)))
                             chunk))]
            (recur (rest chunks)
                   (concat job-ids chunk-jobs)
                   (inc chunk-index)))
          ; Store batch information
          (do
            (swap! @backend-state assoc-in [:batches batch-id]
                   {:job-ids job-ids
                    :submitted-at (System/currentTimeMillis)
                    :total-circuits (count circuits)
                    :status :submitted})
            {:batch-id batch-id
             :job-ids job-ids
             :total-circuits (count circuits)}))))))

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
   (when (nil? (:s3-bucket config))
     (throw (ex-info "S3 bucket is required for Braket backend. AWS Braket stores all quantum task results in S3."
                     {:type :missing-s3-bucket
                      :config config
                      :help "Provide :s3-bucket in the config map, e.g., {:s3-bucket \"my-braket-results\"}"})))
   (let [merged-config (merge {:region "us-east-1"
                               :shots 1000
                               :max-parallel-shots 10
                               :device-type :simulator
                               :s3-key-prefix "braket-results/"}
                              config)
         client (create-braket-client merged-config)
         s3-client (create-s3-client merged-config)
         pricing-client (create-pricing-client merged-config)
         initial-state (atom {:jobs {}
                              :batches {}
                              :devices-cache nil
                              :last-devices-refresh nil
                              :pricing-cache {}})]
     (->BraketBackend client s3-client pricing-client merged-config initial-state {}))))

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

  ;; Create test backend
  (def test-backend (create-braket-simulator {:s3-bucket "test-braket-results"}))

  ;; Test S3 location parsing
  (def mock-task-response
    {:outputS3Bucket "quantum-task-outputs"
     :outputS3Directory "tasks/arn-aws-braket-us-east-1-123456789012-quantum-task-12345"
     :quantumTaskStatus "COMPLETED"})

  (parse-s3-location mock-task-response)

  ;; Test result parsing with different formats
  (def mock-measurement-counts-json
    "{\"measurementCounts\": {\"000\": 334, \"001\": 342, \"010\": 162, \"011\": 162}}")

  (def mock-measurements-json
    "{\"measurements\": [[0, 1, 0], [1, 0, 1], [0, 0, 0]]}")

  (def mock-statevector-json
    "{\"statevector\": [0.5, 0.5, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0]}")

  (parse-braket-results mock-measurement-counts-json)
  (parse-braket-results mock-measurements-json)
  (parse-braket-results mock-statevector-json)

  ;; Test backend info
  (backend/backend-info test-backend)

  ;; Test device availability check
  (backend/available? test-backend)

  ;; Test error handling
  (parse-braket-results "invalid json")
  (parse-s3-location {:no-s3-bucket true})

  ;; Test pricing functionality
  (def mock-pricing-products
    ["{\"product\": {\"attributes\": {\"serviceName\": \"Amazon Braket\", \"usagetype\": \"Task-Request\"}}, 
       \"terms\": {\"OnDemand\": {\"test-term\": {\"priceDimensions\": {\"test-dim\": {\"unit\": \"Request\", \"pricePerUnit\": {\"USD\": \"0.075\"}}}}}}}"])

  (parse-braket-pricing mock-pricing-products :simulator)

  ;; Test cost estimation
  (def mock-circuit {:gate-count 10})
  (def mock-circuits [{:gate-count 5} {:gate-count 8} {:gate-count 12}])

  (backend/estimate-cost test-backend mock-circuit {:shots 1000})
  (backend/estimate-cost test-backend mock-circuits {:shots 500})

  ;; Test QPU pricing
  (def qpu-backend (create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3" 
                                      {:s3-bucket "test-braket-results"}))
  (backend/estimate-cost qpu-backend mock-circuit {:shots 1000})
  
  ;
  )