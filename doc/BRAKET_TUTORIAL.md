# QClojure Braket Tutorial

## Prerequisites

This tutorial assumes you are familiar with:
- [QClojure basics](https://github.com/lsolbach/qclojure) - circuits, gates, measurements
- Basic AWS concepts (accounts, regions, credentials)
- Clojure development environment

## Setup

Before starting, ensure you have:
1. [AWS credentials configured](AWS_CREDENTIALS.md)
2. [S3 bucket set up](S3_SETUP.md) for storing results
3. QClojure and QClojure-Braket dependencies in your project

```clojure
;; project.clj dependencies
[org.soulspace/qclojure "0.12.0"]
[org.soulspace/qclojure-braket "0.1.0-SNAPSHOT"]
```

## Chapter 1: Your First Braket Circuit

Let's start with a simple Bell state circuit that you might already know from QClojure.

```clojure
(require '[org.soulspace.qclojure.domain.circuit :as qc]
         '[org.soulspace.qclojure.adapter.backend.braket :as braket]
         '[org.soulspace.qclojure.application.backend :as qb])

;; Create your Braket backend (simulator for now)
(def backend 
  (braket/create-braket-simulator {:s3-bucket "your-braket-results-bucket"
                                   :region "us-east-1"}))

;; Create a Bell state circuit (same as in QClojure)
(def bell-circuit
  (-> (qc/create-circuit 2)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)))

;; Submit to Amazon Braket
(def job-id (qb/submit-circuit backend bell-circuit {:shots 1000}))

;; Check the status
(qb/get-job-status backend job-id)
;; => :running, :completed, or :failed

;; Get results when completed
(qb/get-job-result backend job-id)
;; => {:job-status :completed
;;     :measurement-results {\"00\" 523, \"11\" 477}
;;     :job-id \"braket-12345...\"}
```

**Key Differences from Local QClojure**:
- Results come back asynchronously via job IDs
- Results are stored in S3 automatically
- You pay for each shot executed

## Chapter 2: Understanding Braket Backends

### Simulator vs QPU

```clojure
;; Simulator - always available, lower cost
(def sim-backend 
  (braket/create-braket-simulator {:s3-bucket "your-bucket"}))

;; Real quantum hardware - scheduled, higher cost, more interesting!
(def qpu-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1"
                            {:s3-bucket "your-bucket"}))
```

### Backend Information

```clojure
;; Check what your backend supports
(qb/get-backend-info sim-backend)
;; => {:backend-type :cloud
;;     :backend-name \"Amazon Braket SV1\"
;;     :supported-gates #{:h :x :y :z :s :t :cnot :rx :ry :rz ...}
;;     :capabilities {:max-qubits 34, :shots-range [1 100000]}}

;; Get provider-specific information
(braket/get-provider-info sim-backend :ionq)
;; => {:provider :ionq
;;     :supported-gates #{:rx :ry :rz :xx :i :measure}
;;     :constraints {:max-qubits 32, :native-connectivity :all-to-all}}
```

## Chapter 3: Hardware Optimization Features

One of the key advantages of the QClojure Braket backend is automatic optimization for different quantum hardware.

### Automatic Circuit Optimization

```clojure
(def complex-circuit
  (-> (qc/create-circuit 4)
      (qc/h-gate 0)
      (qc/t-gate 1)        ; Not a native gate on most hardware
      (qc/cnot-gate 0 3)    ; Might not be directly connected
      (qc/rz-gate 2 0.5)
      (qc/cnot-gate 1 2)))

;; Submit with optimization enabled (default)
(def job-id 
  (qb/submit-circuit qpu-backend complex-circuit 
                     {:shots 1000
                      :optimize-for-device? true}))  ; This is the default
```

The backend will automatically:
- Decompose T gates into native gates
- Insert SWAP gates if qubits aren't connected
- Optimize the gate sequence for the specific device

### Manual Circuit Validation

```clojure
;; Check if your circuit is valid for a device before submitting
(braket/validate-circuit qpu-backend complex-circuit)
;; => {:valid? true
;;     :qubit-count 4
;;     :qubit-constraint-ok? true
;;     :gate-constraint-ok? true  
;;     :warnings [\"T gate will be decomposed to native gates\"
;;               \"CNOT(0,3) requires SWAP insertion\"]}
```

## Chapter 4: Error Mitigation

Real quantum hardware has noise. The Braket backend includes error mitigation techniques.

### Zero-Noise Extrapolation (ZNE)

```clojure
(def noisy-circuit
  (-> (qc/create-circuit 3)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)
      (qc/cnot-gate 1 2)))

;; Submit with error mitigation
(def job-id
  (qb/submit-circuit qpu-backend noisy-circuit
                     {:shots 2000
                      :apply-error-mitigation? true
                      :mitigation-method :zne           ; Zero-noise extrapolation
                      :noise-scaling-factors [1 2 3]    ; Scale noise levels
                      :extrapolation-method :linear}))   ; Linear extrapolation
```

### Readout Error Correction

```clojure
;; For circuits with many measurements
(def measurement-heavy-circuit
  (-> (qc/create-circuit 5)
      (qc/h-gate 0)
      (qc/h-gate 1)
      (qc/h-gate 2)
      (qc/h-gate 3)
      (qc/h-gate 4)))

(def job-id
  (qb/submit-circuit qpu-backend measurement-heavy-circuit
                     {:shots 5000
                      :apply-error-mitigation? true
                      :mitigation-method :readout-correction
                      :calibration-shots 1000}))   ; Extra shots for calibration
```

## Chapter 5: Multi-Provider Optimization

Different quantum hardware providers have different strengths.

### IonQ: All-to-All Connectivity

```clojure
(def ionq-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1"
                            {:s3-bucket "your-bucket"}))

;; IonQ can connect any qubit to any other qubit directly
(def long-range-circuit
  (-> (qc/create-circuit 8)
      (qc/h-gate 0)
      (qc/cnot-gate 0 7)    ; Direct connection possible on IonQ
      (qc/cnot-gate 1 6)    ; No SWAP gates needed
      (qc/cnot-gate 2 5)))

(qb/submit-circuit ionq-backend long-range-circuit {:shots 1000})
```

### Rigetti: Fast Gates with Limited Connectivity

```clojure
(def rigetti-backend
  (braket/create-braket-qpu "arn:aws:braket:us-west-1::device/qpu/rigetti/aspen-m-3"
                            {:s3-bucket "your-bucket"
                             :region "us-west-1"}))

;; Rigetti has fast gates but limited connectivity
(def rigetti-optimized-circuit
  (-> (qc/create-circuit 4)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)    ; Adjacent qubits work well
      (qc/cnot-gate 1 2)    ; Chain of operations
      (qc/cnot-gate 2 3)))

(qb/submit-circuit rigetti-backend rigetti-optimized-circuit {:shots 1000})
```

## Chapter 6: Cost Management

Running on real quantum hardware costs money. Here's how to manage costs effectively.

### Cost Estimation

```clojure
;; Always estimate cost before running expensive experiments
(qb/estimate-cost qpu-backend complex-circuit {:shots 10000})
;; => {:total-cost 45.30
;;     :cost-breakdown {:shots-cost 3.50
;;                     :task-cost 5.00
;;                     :provider-multiplier 1.2
;;                     :estimated-total 45.30}}

;; Compare costs across providers
(qb/estimate-cost ionq-backend complex-circuit {:shots 10000})
;; => {:total-cost 67.80, ...}

(qb/estimate-cost rigetti-backend complex-circuit {:shots 10000})
;; => {:total-cost 23.40, ...}
```

### Batch Processing for Efficiency

```clojure
;; Instead of submitting many small jobs, batch them
(def circuits [bell-circuit complex-circuit noisy-circuit])

;; Submit all at once to reduce per-task fees
(def batch-results
  (qb/submit-batch backend circuits {:shots-per-circuit 1000}))

;; Process results  
(doseq [[circuit-idx result] (map-indexed vector batch-results)]
  (println (str "Circuit " circuit-idx ": " 
                (get-in result [:measurement-results]))))
```

## Chapter 7: Working with Device-Specific Features

### QuEra Analog Quantum Simulation

QuEra's Aquila device works differently - it's an analog quantum simulator:

```clojure
(def quera-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/quera/aquila"
                            {:s3-bucket "your-bucket"}))

;; QuEra uses analog Hamiltonian evolution instead of gates
;; (This requires special circuit construction - see QuEra documentation)
```

### Provider-Specific Native Gates

```clojure
;; Each provider has different native gates
(braket/get-provider-info backend :rigetti)
;; => {:supported-gates #{:rz :rx :cz :i :measure}, ...}

(braket/get-provider-info backend :ionq)  
;; => {:supported-gates #{:rx :ry :rz :xx :i :measure}, ...}

;; Use native gates when possible for better fidelity
(def native-ionq-circuit
  (-> (qc/create-circuit 2)
      (qc/rx-gate 0 (/ Math/PI 2))    ; Native RX
      (qc/xx-gate 0 1 (/ Math/PI 4)))) ; Native XX entangling gate
```

## Chapter 8: Advanced Error Mitigation

### Symmetry Verification

```clojure
(def symmetric-circuit
  (-> (qc/create-circuit 2)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)
      (qc/h-gate 0)))  ; Should return to |00⟩

(def job-id
  (qb/submit-circuit qpu-backend symmetric-circuit
                     {:shots 2000
                      :apply-error-mitigation? true
                      :mitigation-method :symmetry-verification
                      :expected-symmetries [:parity-conservation]}))
```

### Virtual Distillation

```clojure
;; For expectation value estimation
(def expectation-circuit
  (-> (qc/create-circuit 3)
      (qc/ry-gate 0 0.5)
      (qc/ry-gate 1 0.3)
      (qc/cnot-gate 0 1)
      (qc/cnot-gate 1 2)))

(def job-id
  (qb/submit-circuit qpu-backend expectation-circuit
                     {:shots 5000
                      :apply-error-mitigation? true
                      :mitigation-method :virtual-distillation
                      :observable :z-pauli          ; Measure Z expectation
                      :distillation-copies 3}))      ; Number of virtual copies
```

## Chapter 9: Monitoring and Debugging

### Real-time Job Monitoring

```clojure
;; Monitor job progress
(defn wait-for-completion [backend job-id]
  (loop []
    (let [status (qb/get-job-status backend job-id)]
      (println (str "Job status: " status))
      (case status
        :completed (qb/get-job-result backend job-id)
        :failed    (throw (ex-info "Job failed" {:job-id job-id}))
        (do (Thread/sleep 5000)  ; Wait 5 seconds
            (recur))))))

(def result (wait-for-completion backend job-id))
```

### S3 Result Inspection

```clojure
;; Results are stored in S3 - you can inspect them directly
(def s3-location (get-in result [:metadata :s3-location]))
(println (str "Results stored at: " s3-location))

;; The backend handles S3 access automatically, but you can also
;; use AWS CLI to browse results:
;; aws s3 ls s3://your-bucket/braket-results/
```

## Chapter 10: Production Best Practices

### Circuit Design for Hardware

```clojure
;; Design circuits with hardware constraints in mind
(defn hardware-friendly-circuit [n-qubits]
  (-> (qc/create-circuit n-qubits)
      ;; Use nearest-neighbor operations when possible
      (qc/h-gate 0)
      (reduce (fn [circ i] 
                (qc/cnot-gate circ i (inc i)))
              (range (dec n-qubits)))
      ;; Add some parameterized gates for VQE/QAOA
      (qc/rz-gate 0 0.5)
      (qc/rz-gate 1 0.3)))

(def hw-circuit (hardware-friendly-circuit 4))
```

### Error Handling

```clojure
(defn robust-submit [backend circuit options]
  (try
    (let [job-id (qb/submit-circuit backend circuit options)]
      (loop [attempts 0]
        (when (> attempts 10)
          (throw (ex-info "Job timeout" {:job-id job-id})))
        (let [status (qb/get-job-status backend job-id)]
          (case status
            :completed (qb/get-job-result backend job-id)
            :failed    (throw (ex-info "Job failed" 
                                      {:job-id job-id
                                       :result (qb/get-job-result backend job-id)}))
            (do (Thread/sleep 10000)
                (recur (inc attempts)))))))
    (catch Exception e
      (println (str "Error submitting circuit: " (.getMessage e)))
      nil)))
```

### Resource Management

```clojure
;; Clean up old results periodically (they cost money in S3)
(defn cleanup-old-results [backend days-old]
  ;; Implementation would use AWS S3 lifecycle policies
  ;; or custom cleanup logic
  )

;; Use connection pooling for high-throughput applications
(defn create-backend-pool [config pool-size]
  (repeatedly pool-size #(braket/create-braket-simulator config)))
```

## Next Steps

Now that you've completed the tutorial:

1. **Experiment with real QPUs**: Try the same circuits on different hardware providers
2. **Explore device-specific features**: Learn about each provider's unique capabilities  
3. **Build larger applications**: Combine QClojure algorithms with Braket hardware
4. **Cost optimization**: Profile your workloads and optimize for cost-effectiveness
5. **Contribute**: Help improve the QClojure Braket backend

## Additional Resources

- [Amazon Braket Developer Guide](https://docs.aws.amazon.com/braket/)
- [QClojure Documentation](https://github.com/lsolbach/qclojure)
- [Braket Device Information](BRAKET_DEVICES.md)
- [AWS Credentials Setup](AWS_CREDENTIALS.md)
- [S3 Configuration](S3_SETUP.md)

Happy quantum computing! 🚀
