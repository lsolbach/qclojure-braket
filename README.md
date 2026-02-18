# QClojure Braket
QClojure Braket contains a backend to run [QClojure](https://img.shields.io/github/license/lsolbach/qclojure) quantum algorithms with [Amazon Braket](https://aws.amazon.com/braket/).

It provides access to the simulators and to quantum hardware (QPUs)
supported by Amazon Braket.

Disclaimers:
* *experimental/alpha*
  * the braket backend implementation is tested with the SV1 simulator and the IonQ Forte QPU
* Using Amazon Braket costs [money](https://aws.amazon.com/braket/pricing/) even for using simulators
  * use the simulators provided by QClojure for experimentation first

⚠️ **Important**: AWS Braket requires an S3 bucket to store quantum task results. See [S3 SETUP](doc/S3_SETUP.md) for configuration details.

[![Clojars Project](https://img.shields.io/clojars/v/org.soulspace/qclojure-braket.svg)](https://clojars.org/org.soulspace/qclojure-braket)
[![cljdoc badge](https://cljdoc.org/badge/org.soulspace/qclojure-braket)](https://cljdoc.org/d/org.soulspace/qclojure-braket)
![GitHub](https://img.shields.io/github/license/lsolbach/qclojure-braket)
[![DOI](https://zenodo.org/badge/996627430.svg)](https://doi.org/10.5281/zenodo.17059604)

## Usage
The qclojure-braket provides an Amazon Braket backend for QClojure
algorithms. To use it in your code, you need both the qclojure and
the qclojure-braket dependency in your project definition.

### Prerequisites

1. **AWS Account and Credentials**: Set up AWS credentials via environment variables, AWS CLI, or IAM roles
2. **S3 Bucket**: Create an S3 bucket for storing Braket task results (see [S3 SETUP](doc/S3_SETUP.md))

``` clojure
(require '[org.soulspace.qclojure.application.algorithm.bernstein-vazirani :as bv])
(require '[org.soulspace.qclojure.application.backend :as backend])
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

; IMPORTANT: You must provide an S3 bucket for storing quantum task results.
; AWS Braket requires this for all quantum task executions.
;
; Ensure you have:
; 1. AWS credentials configured (via environment variables, CLI, or IAM roles)
; 2. Permissions to use AWS Braket
; 3. An S3 bucket with appropriate permissions in the region as the Braket device

;; Create a Braket backend with S3 configuration
;; Replace "amazon-braket-results-1234" with your actual bucket name
(def backend
  (braket/create-braket-backend {:s3-bucket "amazon-braket-results-1234"
                                 :region "us-east-1"}))

;; Select a specific device (e.g., IonQ Forte-1 QPU) for execution
(backend/select-device backend
                       "arn:aws:braket:us-east-1::device/qpu/ionq/Forte-1")

; Estimate the cost of running the Bernstein-Vazirani algorithm on the selected device
; with 100 shots. Costs will be around $0.30 per task fee + $0.08 per shot for IonQ devices,
; but may vary based on actual pricing.
(backend/estimate-cost backend
                       (bv/bernstein-vazirani-circuit [1 0 1 0])
                       {:shots 100})

; Run Bernstein-Vazirani algorithm on Amazon Braket
; with a specific secret string [1 0 1 0] and 1000 shots.
; The result will be a map with the counts of the measured results.
(bv/bernstein-vazirani-algorithm backend
                                 [1 0 1 0]
                                 {:shots 100})
```


### Advanced Features

The enhanced Braket backend includes:

- **Hardware Optimization**: Automatic circuit optimization for device topology and gate constraints
- **Error Mitigation**: Zero-noise extrapolation, readout error correction, and symmetry verification  
- **Multi-Device Support**: Supports Rigetti, IonQ, IQM, OQC, and QuEra devices
- **Cost Estimation**: Real-time pricing estimates for QPU usage

## Documentation

📚 **[Documentation](doc/)** - Setup guides, tutorials, and device information

### Quick Links
- 🚀 **[Getting Started](doc/README.md)** - Overview and setup sequence
- 🔑 **[AWS Credentials](doc/AWS_CREDENTIALS.md)** - Authentication setup
- 🪣 **[S3 Setup](doc/S3_SETUP.md)** - Required storage configuration  
- 🖥️ **[Braket Devices](doc/BRAKET_DEVICES.md)** - Available quantum hardware
- 📖 **[Tutorial](doc/BRAKET_TUTORIAL.md)** - Step-by-step learning guide
- ⚙️ **[Terraform Setup](terraform/)** - Infrastructure as code

See [examples/enhanced_braket_usage.clj](examples/braket_usage.clj) for code examples.

## Copyright
© 2025 Ludger Solbach

## License
Eclipse Public License 1.0 (EPL1.0)
