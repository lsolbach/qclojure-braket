(defproject org.soulspace/qclojure-braket "0.1.0"
  :description "A Amazon Braket Clojure Backend for "
  :license {:name "Eclipse Public License 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure         "1.12.1"]
                 [com.cognitect.aws/api       "0.8.762"]
                 [com.cognitect.aws/endpoints "871.2.32.15"]
                 [com.cognitect.aws/s3        "871.2.32.2"]
                 [com.cognitect.aws/braket    "871.2.29.35"]
                 [com.cognitect.aws/pricing   "871.2.32.2"]
                 [com.cognitect.aws/iam       "871.2.32.2"]
                 [org.soulspace/qclojure      "0.12.0"]])
