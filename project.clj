(defproject org.soulspace/qclojure-braket "0.3.0-SNAPSHOT"
  :description "Provides an Amazon Braket Backend for QClojure"
  :license {:name "Eclipse Public License 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure         "1.12.3"]
                 [com.cognitect.aws/api       "0.8.774"]
                 [com.cognitect.aws/endpoints "871.2.35.1"]
                 [com.cognitect.aws/s3        "871.2.33.5"]
                 [com.cognitect.aws/braket    "871.2.32.25"]
                 [com.cognitect.aws/pricing   "871.2.32.2"]
                 [com.cognitect.aws/iam       "871.2.32.2"]
                 [zprint/zprint               "1.3.0"]
                 [org.soulspace/qclojure      "0.24.0-SNAPSHOT"]]

  :scm {:name "git" :url "https://github.com/lsolbach/qclojure-braket"}
  :deploy-repositories [["clojars" {:sign-releases false :url "https://clojars.org/repo"}]])
