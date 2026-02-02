(ns org.soulspace.qclojure.adapter.backend.format
  (:require [zprint.core :as zp]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            ))

;;;
;;; EDN formatting
;;;
(def format-options
  "Zprint formatting options for EDN output"
  {:style :respect-bl
   :map {:comma? false
         :force-nl? true
         :sort? true
         :key-order [:id :name :provider :type]}})

(defn format-edn
  "Format a Clojure data structure as a pretty-printed EDN string.
   
   Parameters:
   - data: data to format
   
   Returns: formatted EDN string"
  [data]
  (binding [*print-length* nil
            *print-level* nil]
    (zp/zprint-file-str (prn-str data) "braket" format-options)))

(defn save-formatted-edn
  "Save a Clojure data structure as a pretty-printed EDN file.
   
   Parameters:
   - path: file path to save the EDN data
   - data: data to save
   
   Returns: the original data"
  [path data]
  (spit path (format-edn data))
  data)
