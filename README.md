# QClojure Braket
QClojure Braket contains a backend to run [QClojure](https://img.shields.io/github/license/lsolbach/qclojure) quantum algorithms with Amazon Braket.

It provides access to the simulators and to quantum hardware (QPUs)
supported by Amazon Braket.

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
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

; Define the Amazon Braket simulation backend.
;
; The backend will use the default simulator provided by Amazon Braket.
;
; Ensure you have the necessary AWS permissions to run Braket simulations.
; You can set the AWS credentials and region in your environment variables
; or use the AWS CLI to configure them.
;
; For example, you can set them in your terminal:
; export AWS_ACCESS_KEY_ID=your_access_key
; export AWS_SECRET_ACCESS_KEY=your_secret_key
; export AWS_DEFAULT_REGION=your_region
(def braket-backend
  (braket/create-braket-simulator))

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm braket-backend [1 0 1 0] {:shots 1000})
```

## Copyright
© 2025 Ludger Solbach

## License
Eclipse Public License 1.0 (EPL1.0)
