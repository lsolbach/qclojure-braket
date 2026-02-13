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
    (let [response (fmt/kebab-keys (aws/invoke (:braket-client backend) {:op :SearchDevices
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
  "Get device information from device ARN"
  ([backend]
   (device-info backend (get-in @(:state backend) [:current-device :id])))
  ([backend device-arn]
   (println "Fetching device from AWS Braket...")
   (let [response (fmt/kebab-keys (aws/invoke (:braket-client backend) {:op :GetDevice
                                                                        :request {:deviceArn device-arn}}))
         _ (println "GetDevice response:" response)
         _ (println "Keys:" (keys response))]
     (if (:cognitect.anomalies/category response)
       {:error response}
       (let [capabilities (json/read-str (:device-capabilities response) {:key-fn keyword})
             _ (println "Device capabilities:" capabilities)]
         (assoc response :capabilities capabilities))))))


;;;
;;; Multi-Device Management Functions
;;;
(defn devices
  [backend]
  (let [devices (braket-devices backend)
        state (:state backend)]
    (swap! state assoc :devices devices)
    devices))

(defn select-device
  [backend device]
  (let [device (if (string? device)
                 (some (fn [d] (when (= (:id d) device) d))
                       (:devices @(:state backend)))
                 device)]
    (swap! (:state backend) assoc :current-device device)
    (:current-device @(:state backend))))

