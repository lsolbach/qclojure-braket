(ns org.soulspace.qclojure.adapter.backend.device
  "Functions for managing and querying AWS Braket quantum devices, including QPUs and simulators."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.adapter.backend.format :as fmt]))

;;;
;;; Device Management
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
(defn parse-device-info
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
  (let [arn (:device-arn braket-device)
        braket-device {:id (:device-arn braket-device)
                       :name (:device-name braket-device)
                       :status (device-status (:device-status braket-device) :unknown)
                       :type (device-type (:device-type braket-device) :qpu)
                       :provider (:provider-name braket-device)}
        enhanced-device (merge (get device-properties arn {}) braket-device)]
    enhanced-device))

(defn braket-devices
  "Call AWS Braket API to list available devices"
  [backend]
  (try
    (println "Fetching devices from AWS Braket..." (:braket-client backend))
    (let [response (fmt/clj-keys (aws/invoke (:braket-client backend)
                                               {:op :SearchDevices
                                                :request {:filters []}}))]
      (println "Braket devices response:" response)
      (if (:cognitect.anomalies/category response)
        {:error response}
        (map braket-device (:devices response))))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :api-error}})))

(defn device
  [backend]
  (:current-device @(:state backend)))


(defn device-info
  "Get detailed device information from AWS Braket GetDevice API.
   
   Fetches device details including capabilities with parsed device-cost data.
   The result includes the full capabilities map with service properties
   such as device-cost, execution-windows, and shots-range.
   
   Parameters:
   - backend: BraketBackend instance
   - device-arn: (optional) Device ARN string; defaults to current device
   
   Returns:
   Device info map with :capabilities key containing parsed JSON capabilities,
   or {:error ...} on failure."
  ([backend]
   (device-info backend (get-in @(:state backend) [:current-device :id])))
  ([backend device-arn]
   (let [response (fmt/clj-keys (aws/invoke (:braket-client backend)
                                              {:op :GetDevice
                                               :request {:deviceArn device-arn}}))]
     (if (:cognitect.anomalies/category response)
       {:error response}
       (if-let [caps-str (:device-capabilities response)]
         (let [capabilities (json/read-str caps-str :key-fn fmt/->keyword)]
           (assoc response :capabilities capabilities))
         response)))))

(defn device-cost
  "Extract device-cost pricing from a device's capabilities.
   
   Reads the device-cost map from the capabilities service properties
   returned by the GetDevice API. The cost map contains :price and :unit
   (either \"shot\" for QPUs or \"minute\" for simulators).
   
   Parameters:
   - device-or-capabilities: Either a device map with :capabilities key,
     or a capabilities map directly
   
   Returns:
   {:price <number>, :unit <string>} or nil if not available.
   
   Examples:
     {:price 0.03, :unit \"shot\"}    ;; IonQ Aria QPU
     {:price 0.075, :unit \"minute\"} ;; SV1 simulator"
  [device-or-capabilities]
  (let [capabilities (or (:capabilities device-or-capabilities)
                         device-or-capabilities)]
    (get-in capabilities [:service :device-cost])))

(defn fetch-device-cost
  "Fetch device-cost pricing for a device ARN via the GetDevice API.
   
   Calls device-info to get the full capabilities and extracts the device-cost.
   Returns nil on API errors.
   
   Parameters:
   - backend: BraketBackend instance
   - device-arn: Device ARN string
   
   Returns:
   {:price <number>, :unit <string>} or nil on failure."
  [backend device-arn]
  (let [info (device-info backend device-arn)]
    (when-not (:error info)
      (device-cost info))))


;;;
;;; Multi-Device Management Functions
;;;
(defn devices
  "Fetch available devices from AWS Braket and update backend state.
   
   Calls SearchDevices API and stores the device list in backend state.
   
   Parameters:
   - backend: BraketBackend instance
   
   Returns:
   Collection of device maps."
  [backend]
  (let [devices (braket-devices backend)
        state (:state backend)]
    (swap! state assoc :devices devices)
    devices))

(defn select-device
  "Select a device for circuit execution on this backend.
   
   When a device ARN string is provided, looks up the device from the
   cached device list. Fetches device capabilities via GetDevice API
   if not already present, caching the device-cost for pricing.
   
   Parameters:
   - backend: BraketBackend instance
   - device: Device map or device ARN string
   
   Returns:
   The selected device map (now stored in backend state as :current-device)."
  [backend device]
  (let [resolved-device (if (string? device)
                          (some (fn [d] (when (= (:id d) device) d))
                                (:devices @(:state backend)))
                          device)
        ;; Fetch capabilities if not present, to cache device-cost
        enriched-device (if (:capabilities resolved-device)
                          resolved-device
                          (let [info (device-info backend (:id resolved-device))]
                            (if (:error info)
                              resolved-device
                              (merge resolved-device
                                     (select-keys info [:capabilities])))))]
    (swap! (:state backend) assoc :current-device enriched-device)
    (:current-device @(:state backend))))

;;;
;;; Device Availability
;;;
(defn available?
  "Check if the currently selected device is available for execution.
   
   Calls GetDevice API to check the device status. Returns true if the
   device status is ONLINE, false otherwise or on API errors.
   
   Parameters:
   - backend: BraketBackend instance
   
   Returns:
   true if device is ONLINE, false otherwise."
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

