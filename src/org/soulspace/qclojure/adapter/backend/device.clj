(ns org.soulspace.qclojure.adapter.backend.device
  "Functions for managing and querying AWS Braket quantum devices, including QPUs and simulators."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

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


