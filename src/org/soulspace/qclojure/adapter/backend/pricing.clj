(ns org.soulspace.qclojure.adapter.backend.pricing
  "Functions for querying and calculating pricing for quantum jobs on AWS Braket.
   
   This namespace provides functions to interact with the AWS Pricing API to
   retrieve current pricing information for Braket services, as well as
   functions to calculate estimated costs for quantum jobs based on device type,
   number of shots, and circuit complexity. It also includes caching mechanisms
   to minimize API calls and fallback pricing estimates when API access is
   unavailable."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.adapter.backend.format :as fmt]
            [org.soulspace.qclojure.adapter.backend.device :as device]))

;;;
;;; AWS Pricing Functions
;;;

;; Pricing specs
(s/def ::price-per-task (s/double-in 0.0 1000.0))
(s/def ::price-per-shot (s/double-in 0.0 1.0))
(s/def ::currency string?)
(s/def ::pricing-data
  (s/keys :req-un [::price-per-task ::price-per-shot ::currency]
          :opt-un [::last-updated ::device-type]))

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

(defn provider-pricing-multiplier
  "Get pricing multiplier for a specific provider"
  [provider]
  (get provider-pricing-multipliers
       provider
       (:default provider-pricing-multipliers)))

(defn query-braket-pricing
  "Query AWS Pricing API for Braket service pricing"
  [pricing-client service-code region _device-type]
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

; TODO replace atoms
(defn parse-braket-pricing
  "Parse Braket pricing from AWS Pricing API response"
  [pricing-products device-type]
  (try
    (let [products (map #(json/read-str % :key-fn fmt/->keyword) pricing-products)
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

(defn braket-pricing
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

(def ^:private default-pricing-config
  "Default configuration for AWS Pricing client"
  {:api :pricing
   :region "us-east-1"}) ; Pricing API is available in us-east-1 and ap-south-1

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

(defn estimate-cost
  [backend circuits options]
  (let [device-arn (or (:id (:current-device @(:state backend)))
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
        device-info (device/parse-device-info device-arn)
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

