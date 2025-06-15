# QClojure Braket
Contains a backend to run [QClojure](https://img.shields.io/github/license/lsolbach/qclojure) quantum algorithms with Amazon Braket.

*Skeleton implementation, currently untested*

[![Clojars Project](https://img.shields.io/clojars/v/org.soulspace/qclojure-braket.svg)](https://clojars.org/org.soulspace/qclojure-braket)
[![cljdoc badge](https://cljdoc.org/badge/org.soulspace/qclojure-braket)](https://cljdoc.org/d/org.soulspace/qclojure-braket)
![GitHub](https://img.shields.io/github/license/lsolbach/qclojure-braket)

## Usage
The qclojure-braket provides an Amazon Braket backend for QClojure
algorithms. To use it in your code, you need both the qclojure and
the qclojure-braket dependency in your project definition.

``` clojure
(require '[org.soulspace.qclojure.application.algorithm.bernstein-vazirani :as bv])
(require '[org.soulspace.qclojure.adapter.backend :as qb])

; Define the Amazon Braket backend with AWS credentials
; Ensure you have set the AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables
; before running this code.
; You can set them in your terminal or IDE configuration.
(def braket-backend
  (qb/->BraketBackend
   :region "us-west-2"
   :aws-access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
   :aws-secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")))

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm braket-backend [1 0 1 0] {:shots 1000})
```


## Copyright
© 2025 Ludger Solbach

## License
Eclipse Public License 1.0 (EPL1.0)
