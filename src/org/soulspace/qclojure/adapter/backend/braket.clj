(ns org.soulspace.qclojure.adapter.backend.braket
  "AWS Braket backend implementation for QClojure quantum computing library.
   
   This namespace provides a backend that connects QClojure to Amazon Braket
   quantum computing services, supporting both simulators and quantum hardware
   devices."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.application.format.qasm3 :as qasm3]
            [org.soulspace.qclojure.application.backend :as backend]
            [org.soulspace.qclojure.application.hardware-optimization :as hwopt]
            [org.soulspace.qclojure.domain.circuit :as circuit]))

;;;
;;; Specs for Data Validation
;;;
(s/def ::device-arn string?)
(s/def ::region string?)
(s/def ::max-parallel-shots pos-int?)
(s/def ::device-type #{:quantum :simulator})

;; S3 and result specs
(s/def ::client-token string?)
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

; TODO replace atoms
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

(defn- convert-braket-results
  "Convert Braket task result to QClojure result format.
   
   Parameters:
   - braket-result: Raw result from job-result function
   - job-info: Job info from backend state containing circuit and options
   
   Returns:
   Result in QClojure format compatible with simulator backends"
  [braket-result job-info]
  (let [raw-results (:raw-results braket-result)
        measurement-probs (:measurementProbabilities raw-results)
        shots (:shots braket-result)

        ;; Convert probabilities to counts (frequencies)
        ;; Braket gives us probabilities, but QClojure expects actual counts
        measurement-counts (into {}
                                 (map (fn [[bitstring prob]]
                                        [(name bitstring)
                                         (Math/round (* prob shots))])
                                      measurement-probs))

        ;; Extract circuit metadata
        circuit (:original-circuit job-info)
        circuit-metadata (when circuit
                           {:circuit-depth (circuit/circuit-depth circuit)
                            :circuit-operation-count (circuit/circuit-operation-count circuit)
                            :circuit-gate-count (circuit/circuit-gate-count circuit)})

        ;; Build QClojure-compatible result
        qclojure-result
        {:job-status :completed
         :job-id (:job-id braket-result)
         :circuit circuit
         :circuit-metadata circuit-metadata
         :shots-executed shots
         :execution-time-ms (:execution-time-ms braket-result)
         :results {:measurement-results measurement-counts
                   :probabilities measurement-probs
                   :empirical-probabilities measurement-probs  ; For hardware, these are the same
                   :source :braket-hardware}}]

    ;; Add optional fields if present
    (cond-> qclojure-result
      (:task-arn braket-result)
      (assoc-in [:results :task-arn] (:task-arn braket-result))

      (:s3-location braket-result)
      (assoc-in [:results :s3-location] (:s3-location braket-result))

      (:task-metadata braket-result)
      (assoc-in [:results :task-metadata] (:task-metadata braket-result))

      (:raw-results braket-result)
      (assoc-in [:results :raw-results] (:raw-results braket-result)))))

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
(def device-status {"ONLINE" :online
                    "OFFLINE" :offline
                    "RETIRED" :retired})

(def device-type {"QPU" :qpu
                  "SIMULATOR" :simulator})

(def device-list
  (edn/read-string
   (slurp (io/resource "simulator-devices.edn"))))

(def device-properties
  (->> device-list
       (filter :arn)
       (map (fn [d] (assoc d :id (:arn d))))
       (map (fn [d] [(:id d) d]))
       (into {})))

(println "Loaded device properties:" device-properties)

; TODO needed?
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

(defn- device-info
  "Get device information from device ARN"
  ([backend]
   (device-info backend (get-in @backend-state [:current-device :id])))
  ([backend device-arn]
   (println "Fetching device from AWS Braket...")
   (let [response (aws/invoke (:client backend) {:op :GetDevice
                                                 :request {:deviceArn device-arn}})]
     (println "GetDevice response:" response)
     (if (:cognitect.anomalies/category response)
       {:error response}
       (let [capabilities (json/read-str (:deviceCapabilities response) {:key-fn keyword})]
         (assoc response :capabilities capabilities))))))

(defn- quantum-task
  "Get quantum task details from AWS Braket"
  [backend task-arn]
  (let [response (aws/invoke (:client backend) {:op :GetQuantumTask
                                                :request {:quantumTaskArn task-arn}})]
    (println "GetQuantumTask response:" response)
    (if (:cognitect.anomalies/category response)
      {:error response}
      response)))

(defn braket-device
  [braket-device]
  (let [arn (:deviceArn braket-device)
        braket-device {:id (:deviceArn braket-device)
                       :name (:deviceName braket-device)
                       :status (device-status (:deviceStatus braket-device) :unknown)
                       :type (device-type (:deviceType braket-device) :qpu)
                       :provider (:providerName braket-device)}
        enhanced-device (merge (get device-properties arn {}) braket-device)]
    enhanced-device))

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
        (map braket-device (:devices response))))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :api-error}})))

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
   :devices (:devices @backend-state)
   :device (:current-device @backend-state)
   :created-at (System/currentTimeMillis)})

(defn available?
  [backend]
  (let [device-arn (or (:id (:current-device @backend-state))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
    (try
      (let [response (aws/invoke (:client backend) {:op :GetDevice
                                                    :request {:deviceArn device-arn}})
            _ (println "Device availability response:" response)]
        (if (:cognitect.anomalies/category response)
          false
          (= "ONLINE" (:deviceStatus response))))
      (catch Exception _e
        false))))

(defn device
  [backend]
  (:current-device @backend-state))

(defn submit-circuit
  [backend circuit options]
  (let [device (:current-device @backend-state)
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
        response (aws/invoke (:client backend) {:op :CreateQuantumTask :request task-request})]

    (if (:cognitect.anomalies/category response)
      {:error response}
      (let [task-arn (:quantumTaskArn response)
            job-id (str "braket-" (java.util.UUID/randomUUID))]
          ; Store the task mapping in our state with additional metadata
        (swap! backend-state assoc-in [:jobs job-id] {:task-arn task-arn
                                                      :submitted-at (System/currentTimeMillis)
                                                      :original-circuit circuit
                                                      :final-circuit optimized-circuit
                                                      :options options})
        job-id))))

(defn job-status
  [backend job-id]
  (if-let [job-info (get-in @backend-state [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (aws/invoke (:client backend) {:op :GetQuantumTask
                                                  :request {:quantumTaskArn task-arn}})
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

#_(defn job-result
  [backend job-id]
  (if-let [job-info (get-in @backend-state [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (aws/invoke (:client backend) {:op :GetQuantumTask
                                                  :request {:quantumTaskArn task-arn}})
          _ (println "Job result response:" response)]
      (if (:cognitect.anomalies/category response)
        {:job-status :failed
         :job-id job-id
         :error-message (str "AWS error: " (pr-str response))}
        (if (= "COMPLETED" (:status response))
          ;; Retrieve actual results from S3
          (let [s3-results (retrieve-task-results (:s3-client backend) response)
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


(defn job-result
  [backend job-id]
  (if-let [job-info (get-in @backend-state [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (aws/invoke (:client backend) {:op :GetQuantumTask
                                                  :request {:quantumTaskArn task-arn}})
          _ (println "Job result response:" response)]
      (if (:cognitect.anomalies/category response)
        {:job-status :failed
         :job-id job-id
         :error-message (str "AWS error: " (pr-str response))}
        (if (= "COMPLETED" (:status response))
          ;; Retrieve actual results from S3
          (let [s3-results (retrieve-task-results (:s3-client backend) response)
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
                    result (convert-braket-results braket-result job-info)
                    _ (spit (str job-id "-result.edn") result)
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
  (if-let [job-info (get-in @backend-state [:jobs job-id])]
    (let [task-arn (:task-arn job-info)]
      (try
        (let [response (aws/invoke (:client backend) {:op :CancelQuantumTask
                                                      :request {:quantumTaskArn task-arn}})]
          (if (:cognitect.anomalies/category response)
            :cannot-cancel
            (do
              (swap! backend-state assoc-in [:jobs job-id :cancelled-at] (System/currentTimeMillis))
              :cancelled)))
        (catch Exception _e
          :cannot-cancel)))
    :not-found))

(defn queue-status
  [backend]
  (let [device-arn (or (:id (:current-device @backend-state))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
    (try
      (let [response (aws/invoke (:client backend) {:op :GetDevice
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

;;;
;;; Multi-Device Management Functions
;;;
(defn devices
  [backend]
  (let [devices (braket-devices (:client backend))]
    (swap! backend-state assoc :devices devices)
    devices))

(defn select-device
  [backend device]
  (let [device (if (string? device)
                 (some (fn [d] (when (= (:id d) device) d))
                       (:devices @backend-state))
                 device)]
    (swap! backend-state assoc :current-device device)
    (:current-device @backend-state)))

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
   :active-jobs (count (get-in @backend-state [:jobs]))
   :active-batches (count (get-in @backend-state [:batches]))
   :session-start (System/currentTimeMillis)})

(defn estimate-cost
  [backend circuits options]
  (let [device-arn (or (:id (:current-device @backend-state))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
        shots (get options :shots 1000)
        circuit-count (if (sequential? circuits) (count circuits) 1)
        total-shots (* circuit-count shots)
        device-type (if (str/includes? device-arn "simulator") :simulator :quantum)
        region (:region (:config backend) "us-east-1")
        pricing-data (braket-pricing backend device-type region)
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
  (if-let [batch-info (get-in @backend-state [:batches batch-id])]
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
          (swap! @backend-state assoc-in [:batches batch-id]
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
(defrecord BraketBackend [client s3-client pricing-client config state session-info]
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
    (device this))

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
    (devices this))

  (select-device [this device]
    (select-device this device))

  ;; CloudQuantumBackend protocol implementation  
  backend/CloudQuantumBackend
  (authenticate [this _credentials]
    (authenticate this))

  (session-info [this]
    (session-info this))

  (estimate-cost [this circuits options]
    (estimate-cost this circuits options))

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
                              config)
         client (create-braket-client merged-config)
         s3-client (create-s3-client merged-config)
         pricing-client (create-pricing-client merged-config)
         initial-state (atom {:job-counter 0
                              :active-jobs {}
                              :devices []
                              :current-device nil}
                             #_{:jobs {}
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
  ;; To use this code, ensure you have the necessary AWS credentials configured
  ;; and replace the S3 bucket name with your own.

  ;; First create a braket backend instance
  (def backend (create-braket-simulator {:s3-bucket "amazon-braket-results-1207"}))

  ;; Enable/disable request validation for debugging
  (aws/validate-requests (:client backend) true)
  (aws/validate-requests (:client backend) false)

  ;; Create a Bell state circuit
  (def bell-circuit (-> (circuit/bell-state-circuit)
                        (circuit/measure-all-operation)))

  (backend/devices backend)
  (map :id (:devices @backend-state))

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

  (backend/available? backend)

  (backend/device backend)
  (device-info backend)
  (spit "SV1.edn" (device-info backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1"))
  (spit "Forte-1.edn" (device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1"))
  (spit "Forte-Enterprise-1.edn" (device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-Enterprise-1"))


  (quantum-task backend "arn:aws:braket:us-east-1:579360542232:quantum-task/d02cb431-1820-4ad4-bf49-76441d0ee945")

  ;; Test QPU pricing
  (backend/estimate-cost backend bell-circuit {:shots 1000})

  ;; (backend/calibration-data backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1")

  (def options {:shots 10
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

  (println "Job status:" (job-status backend "braket-327a3bc1-049f-4593-a7a2-59236c3b1bd1"))
  (println "Job result:" (job-result backend "braket-327a3bc1-049f-4593-a7a2-59236c3b1bd1"))
  (println "Job status:" (cancel-job backend "braket-327a3bc1-049f-4593-a7a2-59236c3b1bd1"))

  (slurp "dev/req.json")
  (aws/doc (:client backend) :CreateQuantumTask)

  (let [response (aws/invoke (:client backend) {:op :CreateQuantumTask :request (slurp "dev/req.json")})]
    (println "CreateQuantumTask response:" response))

  ;; Test conversion with the provided result
  (def sample-braket-result
    (edn/read-string (slurp "dev/simple_braket_result1.edn")))

  (def sample-job-info
    {:original-circuit bell-circuit
     :submitted-at 1760536661569
     :options {:shots 10}})

  (convert-braket-results sample-braket-result sample-job-info)
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