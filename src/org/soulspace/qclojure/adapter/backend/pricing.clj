(ns org.soulspace.qclojure.adapter.backend.pricing
  "Functions for calculating pricing for quantum jobs on AWS Braket.
   
   This namespace provides cost estimation for quantum circuits based on
   actual device pricing data from AWS Braket device capabilities.
   
   Pricing models:
   - QPU devices: flat per-task fee ($0.30) + per-shot fee (device-specific)
   - Simulators: per-minute fee (device-specific), estimated from circuit complexity
   
   Pricing data sources (in order of preference):
   1. Device capabilities from GetDevice API (:capabilities -> :service -> :device-cost)
   2. AWS Pricing API (fallback)
   3. Hardcoded fallback values (last resort)
   
   The per-shot prices vary by device and are available in the device capabilities
   map under [:service :device-cost {:price <number> :unit <string>}].
   The per-task fee of $0.30 is uniform across all QPUs."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.adapter.backend.format :as fmt]
            [org.soulspace.qclojure.adapter.backend.device :as device]))

;;;
;;; Pricing Constants
;;;

(def ^:const per-task-fee
  "Uniform per-task fee for all Braket QPU devices (USD).
   Per AWS Braket pricing: https://aws.amazon.com/braket/pricing/"
  0.30)

;;;
;;; Pricing Specs
;;;

(s/def ::price (s/and number? #(>= % 0.0)))
(s/def ::unit #{"shot" "minute"})
(s/def ::device-cost (s/keys :req-un [::price ::unit]))
(s/def ::currency string?)
(s/def ::pricing-source #{:device-capabilities :pricing-api :fallback})
(s/def ::pricing-model #{:per-shot :per-minute})
(s/def ::total-cost (s/and number? #(>= % 0.0)))

(s/def ::cost-breakdown
  (s/keys :req-un [::pricing-model]
          :opt-un [::per-task-fee ::price-per-shot ::price-per-minute
                   ::total-tasks ::total-shots
                   ::task-cost ::shot-cost
                   ::estimated-minutes ::minute-cost]))

(s/def ::cost-estimate
  (s/keys :req-un [::total-cost ::currency ::pricing-model
                   ::cost-breakdown ::pricing-source]))

;;;
;;; Fallback Pricing Data
;;;
;; Used only when both device capabilities and AWS Pricing API are unavailable

(def ^:private fallback-pricing
  "Last-resort pricing when no API data is available.
   Based on published AWS Braket pricing as of 2025."
  {:qpu {:price-per-shot 0.01 ; conservative middle estimate
         :price-per-task per-task-fee}
   :simulator {:price-per-minute 0.075}}) ; SV1 rate

;;;
;;; Simulator Execution Time Estimation
;;;

(def ^:private simulator-time-factors
  "Estimated time factors for simulator execution time estimation.
   These are rough approximations — actual times depend on circuit
   structure, entanglement, and the specific simulator backend."
  {:base-overhead-seconds 1.0     ; minimum task overhead
   :seconds-per-gate-per-shot 0.000002 ; ~2μs per gate per shot for SV1
   :complexity-exponent 1.5})     ; sub-exponential scaling with depth

(defn estimate-simulator-minutes
  "Estimate simulator execution time in minutes from circuit properties.
   
   This is a rough heuristic for cost estimation purposes.
   Actual execution time depends on many factors including circuit
   structure, number of qubits, entanglement, and simulator type.
   
   Parameters:
   - circuit: quantum circuit map (or first circuit if sequential)
   - shots: number of shots
   
   Returns:
   Estimated execution time in minutes (minimum 1 minute)."
  [circuit shots]
  (let [gate-count (or (:gate-count circuit)
                       (count (get circuit :operations []))
                       10)
        num-qubits (or (:num-qubits circuit) 2)
        ;; Scale factor grows with qubit count (state vector doubles per qubit)
        qubit-factor (Math/pow 2.0 (min num-qubits 25))
        base-seconds (:base-overhead-seconds simulator-time-factors)
        per-gate (:seconds-per-gate-per-shot simulator-time-factors)
        estimated-seconds (+ base-seconds
                             (* gate-count shots per-gate qubit-factor))
        estimated-minutes (/ estimated-seconds 60.0)]
    ;; Minimum 1 minute (Braket bills per minute)
    (max 1.0 estimated-minutes)))

;;;
;;; AWS Pricing API (Fallback)
;;;

(def ^:private default-pricing-config
  "Default configuration for AWS Pricing client"
  {:api :pricing
   :region "us-east-1"}) ; Pricing API is available in us-east-1 and ap-south-1

(defn create-pricing-client
  "Creates an AWS Pricing client with optional configuration overrides.
   
   Parameters:
   - config-overrides: Map of configuration overrides (optional)
   
   Returns:
   AWS Pricing client instance
   
   Note:
   The Pricing API is only available in us-east-1 and ap-south-1 regions."
  ([]
   (create-pricing-client {}))
  ([config-overrides]
   (let [config (merge default-pricing-config config-overrides)]
     (aws/client config))))

(defn query-braket-pricing
  "Query AWS Pricing API for Braket service pricing.
   
   This is a fallback mechanism used when device capabilities
   don't contain pricing data.
   
   Parameters:
   - pricing-client: AWS Pricing API client
   - service-code: Service code (e.g., \"AmazonBraket\")
   - region: AWS region name
   
   Returns:
   {:products [...]} on success, {:error ...} on failure."
  [pricing-client service-code region]
  (try
    (let [filters [{:Type "TERM_MATCH"
                    :Field "ServiceCode"
                    :Value service-code}
                   {:Type "TERM_MATCH"
                    :Field "Location"
                    :Value (or region "US East (N. Virginia)")}]
          response (fmt/clj-keys
                    (aws/invoke pricing-client
                                {:op :GetProducts
                                 :request {:ServiceCode service-code
                                           :Filters filters
                                           :MaxResults 100}}))]
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:products (:PriceList response)}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :pricing-api-error}})))

(defn parse-braket-pricing
  "Parse Braket pricing from AWS Pricing API response.
   
   Extracts per-task and per-shot prices from the Pricing API product list.
   Uses a functional reduce instead of atoms.
   
   Parameters:
   - pricing-products: Collection of JSON product strings from AWS Pricing API
   
   Returns:
   {:price-per-task <number>, :price-per-shot <number>, :currency \"USD\"} 
   or {:error ...} on failure."
  [pricing-products]
  (try
    (let [products (map #(json/read-str % :key-fn fmt/->keyword) pricing-products)]
      (reduce
       (fn [pricing product]
         (let [product-attrs (get-in product [:product :attributes])
               pricing-dims (-> product :terms :OnDemand vals first :priceDimensions vals first)
               price-str (get-in pricing-dims [:pricePerUnit :USD])
               unit (:unit pricing-dims)]
           (if price-str
             (let [price (Double/parseDouble price-str)]
               (cond
                 (and (= unit "Request")
                      (str/includes? (str (:usagetype product-attrs)) "Task"))
                 (assoc pricing :price-per-task price)

                 (and (= unit "Shot")
                      (str/includes? (str (:usagetype product-attrs)) "Shot"))
                 (assoc pricing :price-per-shot price)

                 :else pricing))
             pricing)))
       {:price-per-task 0.0
        :price-per-shot 0.0
        :currency "USD"}
       products))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :pricing-parse-error}})))

;;;
;;; Device Pricing Resolution
;;;

(defn- cache-valid?
  "Check if a cached pricing entry is still valid (within 24h TTL)."
  [cached-entry]
  (when cached-entry
    (let [age-ms (- (System/currentTimeMillis) (:cached-at cached-entry 0))]
      (< age-ms (* 24 60 60 1000)))))

(defn- cache-pricing!
  "Store pricing data in the backend's pricing cache."
  [backend cache-key pricing-data]
  (swap! (:state backend) assoc-in [:pricing-cache cache-key]
         (assoc pricing-data :cached-at (System/currentTimeMillis)))
  pricing-data)

(defn- resolve-pricing-from-device
  "Try to resolve pricing from device capabilities.
   
   Returns {:price <n>, :unit <s>} or nil."
  [backend device-arn]
  (or
   ;; Check current device capabilities first
   (let [current-device (:current-device @(:state backend))]
     (when (and current-device (= (:id current-device) device-arn))
       (device/device-cost current-device)))
   ;; Fetch from API (may fail in test or offline environments)
   (try
     (device/fetch-device-cost backend device-arn)
     (catch Exception _ nil))))

(defn- resolve-pricing-from-api
  "Try to resolve pricing from AWS Pricing API.
   
   Returns {:price-per-task <n>, :price-per-shot <n>} or nil."
  [backend region]
  (when-let [pricing-client (:pricing-client backend)]
    (let [response (query-braket-pricing pricing-client "AmazonBraket" region)]
      (when-not (:error response)
        (let [parsed (parse-braket-pricing (:products response))]
          (when-not (:error parsed)
            parsed))))))

(defn device-pricing
  "Resolve pricing data for a device, using a cascading strategy:
   1. Check pricing cache (24h TTL)
   2. Read from device capabilities (GetDevice API)
   3. Fall back to AWS Pricing API
   4. Use hardcoded fallback values
   
   Parameters:
   - backend: BraketBackend instance
   - device-arn: Device ARN string
   
   Returns:
   Map with :device-cost {:price <n> :unit <s>}, :source, and :cached-at."
  [backend device-arn]
  (let [cached (get-in @(:state backend) [:pricing-cache device-arn])]
    (if (cache-valid? cached)
      cached
      ;; Try device capabilities first
      (if-let [cost (resolve-pricing-from-device backend device-arn)]
        (cache-pricing! backend device-arn
                        {:device-cost cost :source :device-capabilities})
        ;; Try AWS Pricing API
        (let [region (get-in backend [:config :region] "us-east-1")
              api-pricing (resolve-pricing-from-api backend region)
              is-simulator? (str/includes? (str device-arn) "simulator")]
          (if api-pricing
            (let [cost (if is-simulator?
                         {:price (or (:price-per-task api-pricing) 0.075)
                          :unit "minute"}
                         {:price (or (:price-per-shot api-pricing) 0.01)
                          :unit "shot"})]
              (cache-pricing! backend device-arn
                              {:device-cost cost :source :pricing-api}))
            ;; Final fallback
            (let [cost (if is-simulator?
                         {:price (:price-per-minute (:simulator fallback-pricing))
                          :unit "minute"}
                         {:price (:price-per-shot (:qpu fallback-pricing))
                          :unit "shot"})]
              (cache-pricing! backend device-arn
                              {:device-cost cost :source :fallback}))))))))

;;;
;;; Cost Estimation
;;;

(defn- estimate-qpu-cost
  "Calculate cost for QPU execution: per-task fee + per-shot pricing.
   
   Parameters:
   - price-per-shot: device-specific per-shot price
   - circuit-count: number of circuits (each becomes a task)
   - shots: shots per circuit
   
   Returns: cost estimate map."
  [price-per-shot circuit-count shots]
  (let [total-shots (* circuit-count shots)
        task-cost (* circuit-count per-task-fee)
        shot-cost (* total-shots price-per-shot)]
    {:total-cost (+ task-cost shot-cost)
     :currency "USD"
     :pricing-model :per-shot
     :cost-breakdown {:per-task-fee per-task-fee
                      :price-per-shot price-per-shot
                      :total-tasks circuit-count
                      :total-shots total-shots
                      :task-cost task-cost
                      :shot-cost shot-cost}}))

(defn- estimate-simulator-cost
  "Calculate cost for simulator execution: per-minute pricing.
   
   Parameters:
   - price-per-minute: device-specific per-minute price
   - circuit: quantum circuit (for complexity estimation)
   - circuit-count: number of circuits
   - shots: shots per circuit
   
   Returns: cost estimate map."
  [price-per-minute circuit circuit-count shots]
  (let [total-shots (* circuit-count shots)
        estimated-minutes (* circuit-count
                             (estimate-simulator-minutes circuit shots))
        minute-cost (* estimated-minutes price-per-minute)]
    {:total-cost minute-cost
     :currency "USD"
     :pricing-model :per-minute
     :cost-breakdown {:price-per-minute price-per-minute
                      :total-tasks circuit-count
                      :total-shots total-shots
                      :estimated-minutes estimated-minutes
                      :minute-cost minute-cost}}))

(defn estimate-cost
  "Estimate the cost of executing quantum circuit(s) on AWS Braket.
   
   Uses actual device pricing from device capabilities when available,
   falling back to Pricing API and then hardcoded values.
   
   QPU pricing: $0.30 per task + device-specific per-shot price.
   Simulator pricing: device-specific per-minute price (estimated from
   circuit complexity).
   
   Parameters:
   - backend: BraketBackend instance
   - circuits: single circuit or collection of circuits
   - options: execution options including :shots (default 1000)
   - device-arn: (optional) device ARN; defaults to current device
   
   Returns:
   Map with :total-cost, :currency, :pricing-model, :cost-breakdown,
   and :pricing-source."
  ([backend circuits options]
   (let [device-arn (or (:id (:current-device @(:state backend)))
                        (get-in backend [:config :device-arn])
                        "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
     (estimate-cost backend circuits device-arn options)))
  ([backend circuits device-arn options]
   (let [shots (get options :shots 1000)
         circuit-count (if (sequential? circuits) (count circuits) 1)
         first-circuit (if (sequential? circuits) (first circuits) circuits)
         pricing-data (device-pricing backend device-arn)
         device-cost (:device-cost pricing-data)
         pricing-unit (:unit device-cost)
         price (:price device-cost)
         base-estimate (if (= pricing-unit "minute")
                         (estimate-simulator-cost price first-circuit circuit-count shots)
                         (estimate-qpu-cost price circuit-count shots))]
     (assoc base-estimate
            :pricing-source (:source pricing-data)
            :device-arn device-arn))))

(comment
  ;; Pricing REPL examples
  ;;
  ;; Create a backend and estimate costs:
  ;;
  ;; (def backend (braket/create-braket-backend {:s3-bucket "my-bucket"}))
  ;;
  ;; QPU pricing (IonQ Forte-1: $0.08/shot + $0.30/task):
  ;; (backend/select-device backend "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1")
  ;; (estimate-cost backend bell-circuit {:shots 1000})
  ;; => {:total-cost 80.30
  ;;     :currency "USD"
  ;;     :pricing-model :per-shot
  ;;     :cost-breakdown {:per-task-fee 0.30
  ;;                      :price-per-shot 0.08
  ;;                      :total-tasks 1
  ;;                      :total-shots 1000
  ;;                      :task-cost 0.30
  ;;                      :shot-cost 80.00}
  ;;     :pricing-source :device-capabilities}
  ;;
  ;; Simulator pricing (SV1: $0.075/minute):
  ;; (backend/select-device backend "arn:aws:braket:::device/quantum-simulator/amazon/sv1") 
  ;; (estimate-cost backend bell-circuit {:shots 1000})
  ;; => {:total-cost 0.075
  ;;     :currency "USD"
  ;;     :pricing-model :per-minute
  ;;     :cost-breakdown {:price-per-minute 0.075
  ;;                      :estimated-minutes 1.0
  ;;                      :minute-cost 0.075}
  ;;     :pricing-source :device-capabilities}
  ;;
  ;; 4-arg arity — estimate for a specific device without switching:
  ;; (estimate-cost backend bell-circuit
  ;;                "arn:aws:braket:us-east-1::device/qpu/iqm/Garnet"
  ;;                {:shots 10000})
  ;; => {:total-cost 14.80, ...}
  ;
  )

