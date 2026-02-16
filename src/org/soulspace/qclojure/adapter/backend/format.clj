(ns org.soulspace.qclojure.adapter.backend.format
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [zprint.core :as zp]
            [clojure.core :as c]))
;;;
;;; Formatting utilities for AWS data structures
;;;


(def key-word-regex
  ;; Matches:
  ;; 1. Acronyms followed by normal word (ECRImage → ECR)
  ;; 2. Normal capitalized words (Image)
  ;; 3. Lowercase+digit combos (t2, s3)
  ;; 4. Plain lowercase words
  #"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+\d*|[A-Z]+\d*|\d+")

(defn ->keyword [k]
  (->> (name k)
       (re-seq key-word-regex)
       (map str/lower-case)
       (str/join "-")
       keyword))

(defn transform-keys
  "Recursively transforms all map keys in coll with t."
  [t coll]
  (letfn [(transform [[k v]] [(t k) v])]
    (walk/postwalk
     (fn [x]
       (if (map? x)
         (with-meta (into {} (map transform x)) (meta x))
         x)) coll)))

(comment
  (->keyword "outputS3Location") ; => :output-s3-location
  (->keyword "t2.micro") ; => :t2-micro
  (->keyword "ECRImage") ; => :ecr-image
  (->keyword "S3Bucket") ; => :s3-bucket

  (transform-keys ->keyword {"outputS3Location" "s3://my-bucket/outputs"
                             "t2.micro" "instanceType"
                             "ECRImage" "image"
                             :inputURL "http://example.com/input"
                             "nested" {"S3Bucket" "my-bucket"}})
  ;
  )

;;
;; Key formatting
;;
(defn clj-keys
  "Convert all map keys to kebab-case keywords.
   
   Parameters:
   - m: input map
   
   Returns: map with kebab-case keyword keys"
  [m]
  (transform-keys ->keyword m))

;;
;; EDN formatting
;;
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
