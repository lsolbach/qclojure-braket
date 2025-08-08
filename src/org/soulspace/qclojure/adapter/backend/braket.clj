(ns org.soulspace.qclojure.adapter.backend.braket
  "AWS Braket backend implementation for QClojure quantum computing library.
   
   This namespace provides a backend that connects QClojure to Amazon Braket
   quantum computing services, supporting both simulators and quantum hardware
   devices."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.application.format.qasm3 :as qasm3]
            [org.soulspace.qclojure.application.backend :as qb]
            [org.soulspace.qclojure.domain.circuit-transformation :as ct]))

;;
;; AWS Braket Client Configuration  
;;
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

;;
;; Specs for Data Validation
;;
(s/def ::device-arn string?)
(s/def ::region string?)
(s/def ::shots pos-int?)
(s/def ::max-parallel-shots pos-int?)
(s/def ::device-type #{:quantum :simulator})

(s/def ::backend-config
  (s/keys :opt-un [::device-arn
                   ::region
                   ::shots
                   ::max-parallel-shots
                   ::device-type]))

(s/def ::circuit-definition map?) ; We'll refine this based on QClojure's circuit format

;; S3 and result specs
(s/def ::bucket string?)
(s/def ::key string?)
(s/def ::s3-location
  (s/keys :req-un [::bucket]
          :opt-un [::key]))

(s/def ::measurement-outcome (s/coll-of (s/int-in 0 2)))
(s/def ::measurements (s/coll-of ::measurement-outcome))
(s/def ::measurement-counts (s/map-of string? pos-int?))
(s/def ::probabilities (s/map-of string? (s/double-in 0.0 1.0)))

(s/def ::task-result
  (s/keys :opt-un [::measurements
                   ::measurement-counts
                   ::probabilities
                   ::task-metadata
                   ::s3-location]))

;; Pricing specs
(s/def ::price-per-task (s/double-in 0.0 1000.0))
(s/def ::price-per-shot (s/double-in 0.0 1.0))
(s/def ::currency string?)
(s/def ::pricing-data
  (s/keys :req-un [::price-per-task ::price-per-shot ::currency]
          :opt-un [::last-updated ::device-type]))

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

(defn- get-braket-pricing
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

;;
;; Multi-QPU Device Management Functions
;;

;; Provider-specific gate sets based on real AWS Braket QPU capabilities  
(def ^:private provider-gate-sets
  "Native gate sets supported by different QPU providers on AWS Braket"
  {:rigetti #{:rx :ry :rz :cz :xy :measure}  ; Rigetti Aspen series superconducting
   :ionq #{:rx :ry :rz :cnot :swap :h :x :y :z :s :t :measure} ; IonQ trapped ion
   :iqm #{:rx :ry :rz :cz :measure} ; IQM superconducting 
   :oqc #{:rx :ry :rz :ecr :measure} ; OQC photonic (ECR = echoed cross-resonance)
   :quera #{:rydberg-blockade :rydberg-phase :measure} ; QuEra neutral atom
   :amazon #{:rx :ry :rz :cnot :ccnot :swap :h :x :y :z :s :t :measure} ; Amazon Braket devices
   :simulator #{:rx :ry :rz :cnot :ccnot :cz :swap :h :x :y :z :s :t :phase :u1 :u2 :u3 :measure}})

;; Provider-specific hardware constraints
(def ^:private provider-constraints
  "Hardware constraints for different QPU providers"
  {:rigetti {:max-qubits 80 :max-shots 100000 :native-connectivity :limited :coherence-time-us 50}
   :ionq {:max-qubits 32 :max-shots 10000 :native-connectivity :all-to-all :coherence-time-us 10000}
   :iqm {:max-qubits 20 :max-shots 100000 :native-connectivity :limited :coherence-time-us 60}
   :oqc {:max-qubits 8 :max-shots 100000 :native-connectivity :limited :coherence-time-us 5}
   :quera {:max-qubits 256 :max-shots 1000 :native-connectivity :programmable :coherence-time-us 1000}
   :amazon {:max-qubits 34 :max-shots 100000 :native-connectivity :all-to-all :coherence-time-us 100}
   :simulator {:max-qubits 40 :max-shots 100000 :native-connectivity :all-to-all :coherence-time-us :unlimited}})

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

(defn- get-provider-gate-set
  "Get the native gate set for a specific provider"
  [provider]
  (get provider-gate-sets provider (:simulator provider-gate-sets)))

(defn- get-provider-constraints
  "Get hardware constraints for a specific provider"
  [provider]
  (get provider-constraints provider (:simulator provider-constraints)))

(defn- get-provider-pricing-multiplier
  "Get pricing multiplier for a specific provider"
  [provider]
  (get provider-pricing-multipliers provider (:default provider-pricing-multipliers)))

(defn- validate-circuit-for-device
  "Validate if a circuit can run on a specific device"
  [circuit device-info]
  (let [provider (:provider device-info)
        constraints (get-provider-constraints provider)
        supported-gates (get-provider-gate-set provider)
        circuit-gates (set (keys (:gates circuit)))
        qubit-count (:qubit-count circuit 0)
        unsupported-gates (set/difference circuit-gates supported-gates)]

    {:valid? (and (<= qubit-count (:max-qubits constraints))
                  (empty? unsupported-gates))
     :qubit-count qubit-count
     :max-qubits (:max-qubits constraints)
     :qubit-constraint-ok? (<= qubit-count (:max-qubits constraints))
     :unsupported-gates unsupported-gates
     :gate-constraint-ok? (empty? unsupported-gates)
     :connectivity (:native-connectivity constraints)
     :constraints constraints}))

(defn- cache-devices!
  "Cache the list of available devices for 5 minutes to avoid excessive API calls"
  [backend devices]
  (swap! (:state backend) assoc
         :devices-cache devices
         :last-devices-refresh (System/currentTimeMillis)))

(defn- get-cached-devices
  "Get cached devices if still valid (less than 5 minutes old)"
  [backend]
  (let [{:keys [devices-cache last-devices-refresh]} @(:state backend)
        cache-age (- (System/currentTimeMillis) (or last-devices-refresh 0))
        cache-valid? (< cache-age (* 5 60 1000))] ; 5 minutes
    (when cache-valid?
      devices-cache)))

(defn- list-braket-devices
  "Call AWS Braket API to list available devices"
  [client]
  (try
    (let [response (aws/invoke client {:op :SearchDevices})]
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:devices (:devices response)}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :api-error}})))

(defn- create-braket-task
  "Create a Braket task from a circuit"
  [backend client device-arn circuit options]
  (let [transformed (ct/transform-circuit
                     circuit
                     (qb/get-supported-gates backend))
        openqasm (qasm3/circuit-to-qasm transformed)
        shots (get options :shots 1000)
        task-request {:deviceArn device-arn
                      :action {:source openqasm
                               :sourceType "OPENQASM_3"}
                      :shots shots}]
    (aws/invoke client {:op :CreateQuantumTask :request task-request})))

(defprotocol AmazonBraketBackend
  "Protocol for interacting with AWS Braket"
  (get-provider-info [this provider]
    "Get information about a specific QPU provider")
  (get-device-info [this device-arn]
    "Get comprehensive device information including provider-specific details")
  (validate-circuit [this circuit]
    "Validate if a circuit can run on the configured device"))

(defrecord BraketBackend [client s3-client pricing-client config state session-info]
  ;; Basic backend info
  Object
  (toString [_this]
    (str "BraketBackend{region=" (get-in config [:region])
         ", device=" (get-in config [:device-arn] "default-simulator") "}"))

  ;; QuantumBackend protocol implementation
  qb/QuantumBackend
  (submit-circuit [this circuit options]
    "Submit a quantum circuit for execution on AWS Braket"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1") ; Default simulator
          response (create-braket-task this client device-arn circuit options)]
      (if (:cognitect.anomalies/category response)
        {:error response}
        (let [task-arn (:quantumTaskArn response)
              job-id (str "braket-" (java.util.UUID/randomUUID))]
          ; Store the task mapping in our state
          (swap! state assoc-in [:jobs job-id] {:task-arn task-arn
                                                :submitted-at (System/currentTimeMillis)
                                                :circuit circuit
                                                :options options})
          job-id))))

  (get-job-status [_this job-id]
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

  (get-job-result [_this job-id]
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

  (get-supported-gates [_this]
    "Get the gates supported by the current device with enhanced provider-specific logic"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
          device-info (parse-device-info device-arn)]

      (if device-info
        ;; Use provider-specific gate set
        (get-provider-gate-set (:provider device-info))
        ;; Fallback to querying the device directly
        (if (str/includes? device-arn "simulator")
          ;; Simulators support all standard gates
          (:simulator provider-gate-sets)
          ;; For real devices, query device capabilities
          (try
            (let [response (aws/invoke client {:op :GetDevice
                                               :request {:deviceArn device-arn}})]
              (if (:cognitect.anomalies/category response)
                #{:h :x :y :z :cnot :measure} ; Fallback gate set
                (let [native-gates (get-in response [:deviceCapabilities :action :braket.ir.openqasm :supportedOperations])]
                  (set (map keyword native-gates)))))
            (catch Exception _e
              #{:h :x :y :z :cnot :measure}))))))

  (get-queue-status [_this]
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

  (is-available? [_this]
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

  (get-backend-info [this]
    "Get information about this backend instance (conforms to ::backend-info)"
    {:backend-type :cloud
     :backend-name "Amazon Braket"
     :capabilities #{:cloud :batch :cost-estimation :s3-results}
     :supported-gates (qb/get-supported-gates this)
     :backend-config config
     :provider :aws
     :region (:region config)
     :device-arn (:device-arn config)
     :device-type (:device-type config)
     :default-shots (:shots config)
     :max-parallel-shots (:max-parallel-shots config)
     :created-at (System/currentTimeMillis)
     :version "1.0.0"})

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

  ;; CloudQuantumBackend protocol implementation  
  qb/CloudQuantumBackend
  (authenticate [_this _credentials]
    "AWS Braket uses AWS credentials, not separate authentication"
    {:status :authenticated
     :region (:region config)
     :authenticated-at (System/currentTimeMillis)})

  (list-available-devices [this]
    "List all available Braket devices (returns collection as per protocol)"
    (let [raw-devices (if-let [cached (get-cached-devices this)]
                        cached
                        (let [result (list-braket-devices client)]
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
      (when (and (seq normalized) (nil? (get-cached-devices this)))
        (cache-devices! this normalized))
      normalized))

  ;; Enhanced multi-QPU protocol methods 
  (get-device-topology [_this device-arn]
    "Get the connectivity topology of a specific device (protocol-conformant)"
    (try
      (let [response (aws/invoke client {:op :GetDevice
                                         :request {:deviceArn device-arn}})]
        (if (:cognitect.anomalies/category response)
          {:device-id device-arn
           :device-name (str device-arn)
           :coupling-map []}
          (let [capabilities (:deviceCapabilities response)
                device-name (:deviceName response)
                connectivity (get-in capabilities [:action :braket.ir.openqasm :supportedConnectivity])
                max-qubits (or (get-in capabilities [:action :braket.ir.openqasm :maxQubits]) 0)
                coupling-map (cond
                               (and (sequential? connectivity)
                                    (every? sequential? connectivity))
                               (mapv vec connectivity)
                               (= connectivity "AllToAll")
                               (vec (for [i (range max-qubits)
                                          j (range max-qubits)
                                          :when (not= i j)]
                                      [i j]))
                               :else [])]
            {:device-id device-arn
             :device-name device-name
             :coupling-map coupling-map
             :max-qubits max-qubits})))
      (catch Exception _e
        {:device-id device-arn :device-name (str device-arn) :coupling-map []})))

  (get-session-info [_this]
    "Get current session information"
    {:status :authenticated
     :session-id (str "braket-session-" (java.util.UUID/randomUUID))
     :region (:region config)
     :device-arn (:device-arn config)
     :active-jobs (count (get-in @state [:jobs]))
     :active-batches (count (get-in @state [:batches]))
     :session-start (System/currentTimeMillis)})

  (get-calibration-data [_this device-arn]
    "Get calibration data for a specific device (if available)"
    (try
      (let [response (aws/invoke client {:op :GetDevice
                                         :request {:deviceArn device-arn}})]
        (if (:cognitect.anomalies/category response)
          {:device-id device-arn
           :timestamp (java.time.Instant/now)}
          {:device-id device-arn
           :timestamp (java.time.Instant/now)
           :device-status (:deviceStatus response)
           :note "Braket API does not expose detailed calibration data"}))
      (catch Exception _e
        {:device-id device-arn
         :timestamp (java.time.Instant/now)})))

  (estimate-cost [this circuits options]
    "Estimate the cost of running circuits with enhanced provider-specific pricing"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
          shots (get options :shots 1000)
          circuit-count (if (sequential? circuits) (count circuits) 1)
          total-shots (* circuit-count shots)
          device-type (if (str/includes? device-arn "simulator") :simulator :quantum)
          region (:region config "us-east-1")
          pricing-data (get-braket-pricing this device-type region)
          base-cost (:price-per-task pricing-data)
          shot-cost (:price-per-shot pricing-data)
          circuit-complexity (get (if (sequential? circuits) (first circuits) circuits) :gate-count 10)
          ;; Enhanced: Apply provider-specific pricing multiplier
          device-info (parse-device-info device-arn)
          provider-multiplier (if device-info
                                (get-provider-pricing-multiplier (:provider device-info))
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
                                 (qb/submit-circuit this circuit job-options)))
                             chunk))]
            (recur (rest chunks)
                   (concat job-ids chunk-jobs)
                   (inc chunk-index)))
          ; Store batch information
          (do
            (swap! (:state this) assoc-in [:batches batch-id]
                   {:job-ids job-ids
                    :submitted-at (System/currentTimeMillis)
                    :total-circuits (count circuits)
                    :status :submitted})
            {:batch-id batch-id
             :job-ids job-ids
             :total-circuits (count circuits)})))))

  (get-batch-status [this batch-id]
    "Get status of a batch operation"
    (if-let [batch-info (get-in @(:state this) [:batches batch-id])]
      (let [job-statuses (doall (map #(qb/get-job-status this %) (:job-ids batch-info)))
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

  (get-batch-results [this batch-id]
    "Get results of a completed batch"
    (if-let [batch-info (get-in @(:state this) [:batches batch-id])]
      (let [job-ids (:job-ids batch-info)
            results (map #(qb/get-job-result this %) job-ids)]
        {:batch-id batch-id
         :total-jobs (count job-ids)
         :results results
         :completed-at (System/currentTimeMillis)})
      {:error "Batch not found"}))

  AmazonBraketBackend
  (get-provider-info [_this provider]
                   "Get information about a specific QPU provider"
                   {:provider provider
                    :supported-gates (get-provider-gate-set provider)
                    :constraints (get-provider-constraints provider)
                    :pricing-multiplier (get-provider-pricing-multiplier provider)})

  (get-device-info [_this device-arn]
    "Get comprehensive device information including provider-specific details"
    (let [device-info (parse-device-info device-arn)
          provider (:provider device-info)]
      (merge device-info
             {:supported-gates (get-provider-gate-set provider)
              :constraints (get-provider-constraints provider)
              :pricing-multiplier (get-provider-pricing-multiplier provider)})))
  
  (validate-circuit [_this circuit]
    "Validate if a circuit can run on the configured device"
    (let [device-arn (or (:device-arn config)
                         "arn:aws:braket:::device/quantum-simulator/amazon/sv1")
          device-info (parse-device-info device-arn)]
      (if device-info
        (validate-circuit-for-device circuit device-info)
        {:valid? false
         :error "Unable to parse device information"}))))

;;
;; Backend Creation Functions
;;

(defn create-braket-backend
  "Create a new Braket backend instance.
   
   Parameters:
     config - Configuration map with keys:
       :region - AWS region (optional, defaults to us-east-1)
       :device-arn - ARN of the Braket device (optional, defaults to simulator) 
       :shots - Default number of shots (optional, defaults to 1000)
       :max-parallel-shots - Maximum parallel jobs (optional, defaults to 10)
       :device-type - Type of device :quantum or :simulator (optional)
   
   Returns:
     BraketBackend instance
   
   Example:
     (create-braket-backend {:region \"us-west-2\" 
                            :device-arn \"arn:aws:braket:::device/quantum-simulator/amazon/sv1\"
                            :shots 1000})"
  ([]
   (create-braket-backend {}))
  ([config]
   (let [merged-config (merge {:region "us-east-1"
                               :shots 1000
                               :max-parallel-shots 10
                               :device-type :simulator}
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
   
   Returns:
     BraketBackend configured for the default AWS Braket simulator"
  []
  (create-braket-backend {:device-arn "arn:aws:braket:::device/quantum-simulator/amazon/sv1"
                          :device-type :simulator
                          :region "us-east-1"}))

(defn create-braket-qpu
  "Create a Braket QPU backend for quantum hardware execution.

   Parameters:
     device-arn - ARN of the specific QPU device
     region - AWS region where the device is located (optional)
   
   Returns:
     BraketBackend configured for the specified QPU"
  [device-arn & {:keys [region] :or {region "us-east-1"}}]
  (create-braket-backend {:device-arn device-arn
                          :device-type :quantum
                          :region region}))

(comment
  ;; REPL experimentation and testing

  ;; Create test backend
  (def test-backend (create-braket-simulator))

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
  (qb/get-backend-info test-backend)

  ;; Test device availability check
  (qb/is-available? test-backend)

  ;; Test supported gates
  (qb/get-supported-gates test-backend)

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

  (qb/estimate-cost test-backend mock-circuit {:shots 1000})
  (qb/estimate-cost test-backend mock-circuits {:shots 500})

  ;; Test QPU pricing
  (def qpu-backend (create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3"))
  (qb/estimate-cost qpu-backend mock-circuit {:shots 1000})
  
  ;
  )