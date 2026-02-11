# Braket Result Conversion Strategy

## Overview

Amazon Braket returns different result formats depending on the device used for job execution:
- **SV1 Simulator**: Returns individual measurement outcomes
- **QPU Devices (e.g., IONQ Forte)**: Returns aggregated measurement probabilities

This document describes how to convert these different formats to the QClojure-compatible result format.

## Braket Result Formats

### 1. SV1 Simulator Results

The SV1 state vector simulator returns:

```clojure
{:measurements [[0 0] [1 1] [1 1] [0 0] [1 1] ...]  ; Array of shot results
 :measured-qubits [0 1]
 :task-metadata {:shots 100 ...}}
```

**Characteristics:**
- Individual measurement outcomes for each shot
- Each element is a vector of qubit measurement results (0 or 1)
- Total elements equals number of shots
- Device ARN: `arn:aws:braket:::device/quantum-simulator/amazon/sv1`

### 2. QPU Results (IONQ Forte, etc.)

Quantum Processing Units return aggregated probability distributions:

```clojure
{:measurement-probabilities {:00 0.40    ; 40% probability
                             :10 0.01    ; 1% probability
                             :01 0.01    ; 1% probability
                             :11 0.58}   ; 58% probability
 :measured-qubits [0 1]
 :task-metadata {:shots 100 ...}}
```

**Characteristics:**
- Aggregated probabilities instead of individual measurements
- Keys are bit strings (as keywords like `:00`, `:11`)
- Values are probabilities (sum to 1.0)
- Device ARN: `arn:aws:braket:<region>::device/qpu/<provider>/<device-name>`

## QClojure Expected Format

QClojure's `extract-measurement-results` function expects the following structure:

```clojure
{:measurement-outcomes [3 3 3 0 3 ...]           ; Vector of integer outcomes
 :measurement-probabilities [0.5 0.0 0.0 0.5]   ; Theoretical probabilities
 :empirical-probabilities {3 0.58                ; Map of outcome -> probability
                           0 0.40
                           2 0.01
                           1 0.01}
 :shot-count 100                                 ; Total number of shots
 :measurement-qubits [0 1]                       ; Qubits measured
 :frequencies {3 58                              ; Map of outcome -> count
               0 40
               2 1
               1 1}
 :source :braket-hardware}                       ; Source indicator
```

**Key Fields:**
- `:measurement-outcomes` - Vector of measurement outcomes as integers (basis state indices)
- `:measurement-probabilities` - For hardware, same as empirical probabilities
- `:empirical-probabilities` - Map of outcome index -> empirical probability
- `:shot-count` - Total number of measurement shots
- `:measurement-qubits` - Which qubits were measured
- `:frequencies` - Map of outcome index -> count (fundamental format)
- `:source` - Set to `:braket-hardware` for Braket backends

**Important Note:** The `:frequencies` map is the fundamental representation that drives all other fields. For Braket hardware results, we use empirical probabilities as both theoretical and empirical since we don't have access to the true theoretical probabilities from the quantum state.

## Conversion Strategies

### Strategy 1: SV1 Simulator Conversion

**Input:** Array of measurement vectors
**Steps:**
1. Convert each measurement vector to a basis state index
2. Count frequencies of each outcome
3. Calculate empirical probabilities from frequencies
4. Generate the measurement outcomes vector

**Implementation:**

```clojure
(defn convert-simulator-measurements
  "Convert SV1 simulator measurements to QClojure format."
  [measurements shots num-qubits]
  (let [;; Convert bit vectors to basis state indices
        outcomes (mapv (fn [measurement]
                        (reduce (fn [acc [idx bit]]
                                  (+ acc (* bit (bit-shift-left 1 (- num-qubits idx 1)))))
                                0
                                (map-indexed vector measurement)))
                      measurements)
        
        ;; Calculate frequencies
        frequencies (clojure.core/frequencies outcomes)
        
        ;; Calculate empirical probabilities
        empirical-probs (into {}
                             (map (fn [[outcome count]]
                                    [outcome (/ count shots)])
                                  frequencies))
        
        ;; For hardware, theoretical = empirical
        measurement-probs (mapv #(get empirical-probs % 0.0)
                               (range (bit-shift-left 1 num-qubits)))]
    
    {:measurement-outcomes outcomes
     :measurement-probabilities measurement-probs
     :empirical-probabilities empirical-probs
     :shot-count shots
     :measurement-qubits (range num-qubits)
     :frequencies frequencies
     :source :braket-hardware}))
```

### Strategy 2: QPU Conversion

**Input:** Map of probabilities
**Steps:**
1. Convert probability map keys from keywords to basis state indices
2. Multiply probabilities by shot count to get frequencies
3. Calculate empirical probabilities (same as input probabilities)
4. Generate measurement outcomes by sampling from probabilities

**Implementation:**

```clojure
(defn bitstring-to-index
  "Convert a bitstring like \"00\" or \"11\" to basis state index."
  [bitstring num-qubits]
  (reduce (fn [acc [idx bit]]
            (+ acc (* (- (int bit) (int \0))
                     (bit-shift-left 1 (- num-qubits idx 1)))))
          0
          (map-indexed vector bitstring)))

(defn convert-qpu-probabilities
  "Convert QPU measurement probabilities to QClojure format."
  [measurement-probs shots num-qubits]
  (let [;; Convert keyword keys to string bitstrings
        prob-map (into {}
                      (map (fn [[k v]]
                             [(name k) v])
                           measurement-probs))
        
        ;; Convert to frequencies (multiply probabilities by shots)
        frequencies (into {}
                         (map (fn [[bitstring prob]]
                                [(bitstring-to-index bitstring num-qubits)
                                 (Math/round (* prob shots))])
                              prob-map))
        
        ;; Empirical probabilities (same as input for QPU)
        empirical-probs (into {}
                             (map (fn [[bitstring prob]]
                                    [(bitstring-to-index bitstring num-qubits) prob])
                                  prob-map))
        
        ;; Generate measurement outcomes by sampling
        outcomes (vec (mapcat (fn [[outcome count]]
                               (repeat count outcome))
                             frequencies))
        
        ;; For hardware, theoretical = empirical        measurement-probs-vec (mapv #(get empirical-probs % 0.0)
                                   (range (bit-shift-left 1 num-qubits)))]
    
    {:measurement-outcomes outcomes
     :measurement-probabilities measurement-probs-vec
     :empirical-probabilities empirical-probs
     :shot-count shots
     :measurement-qubits (range num-qubits)
     :frequencies frequencies
     :source :braket-hardware}))
```

## Device Type Detection

To determine which conversion strategy to use, check the raw results structure:

```clojure
(defn detect-device-type
  "Detect whether results are from simulator or QPU."
  [raw-results]
  (cond
    (contains? raw-results :measurements)
    :simulator
    
    (contains? raw-results :measurement-probabilities)
    :qpu
    
    (contains? raw-results :measurementProbabilities)  ; Camelcase variant
    :qpu
    
    :else
    :unknown))
```

## Unified Conversion Function

```clojure
(defn convert-braket-measurement-results
  "Convert Braket measurement results to QClojure format.
   
   Automatically detects device type and applies appropriate conversion."
  [raw-results shots num-qubits]
  (let [device-type (detect-device-type raw-results)]
    (case device-type
      :simulator
      (convert-simulator-measurements
       (:measurements raw-results)
       shots
       num-qubits)
      
      :qpu
      (convert-qpu-probabilities
       (or (:measurement-probabilities raw-results)
           (:measurementProbabilities raw-results))
       shots
       num-qubits)
      
      ;; Unknown format
      (throw (ex-info "Unknown Braket result format"
                      {:raw-results raw-results
                       :device-type device-type})))))
```

## Integration with Existing Code

The conversion should be applied in the `convert-braket-results` function:

```clojure
(defn- convert-braket-results
  "Convert Braket task result to QClojure result format."
  [braket-result job-info]
  (let [raw-results (:raw-results braket-result)
        shots (:shots braket-result)
        circuit (:original-circuit job-info)
        num-qubits (:num-qubits circuit)
        
        ;; Convert measurement results
        measurement-results (convert-braket-measurement-results
                            raw-results
                            shots
                            num-qubits)
        
        ;; Build QClojure-compatible result
        qclojure-result
        {:job-status :completed
         :job-id (:job-id braket-result)
         :circuit circuit
         :circuit-metadata (when circuit
                            {:circuit-depth (circuit/circuit-depth circuit)
                             :circuit-operation-count (circuit/circuit-operation-count circuit)
                             :circuit-gate-count (circuit/circuit-gate-count circuit)})
         :shots-executed shots
         :execution-time-ms (:execution-time-ms braket-result)
         :results measurement-results}]
    
    ;; Add optional fields
    (cond-> qclojure-result
      (:task-arn braket-result)
      (assoc-in [:results :task-arn] (:task-arn braket-result))
      
      (:s3-location braket-result)
      (assoc-in [:results :s3-location] (:s3-location braket-result)))))
```

## Testing Considerations

### Test Cases

1. **SV1 Simulator Results:**
   - Test with small number of shots (10-100)
   - Verify frequency counts match input measurements
   - Verify outcome indices are correct
   - Check empirical probabilities sum to 1.0

2. **QPU Results:**
   - Test with probability distributions
   - Verify frequency counts match rounded probabilities × shots
   - Check bitstring-to-index conversion
   - Verify outcomes are sampled correctly

3. **Edge Cases:**
   - Single qubit measurements
   - Multi-qubit (2-5 qubits) measurements
   - Zero probability outcomes (should be absent from frequencies)
   - All outcomes have equal probability

### Sample Test Data

```clojure
;; SV1 Simulator sample
{:measurements [[0 0] [1 1] [1 1] [0 0] [1 1]
                [1 1] [0 0] [1 1] [1 1] [0 0]]
 :measured-qubits [0 1]
 :shots 10}
;; Expected frequencies: {0 4, 3 6}

;; QPU sample
{:measurement-probabilities {:00 0.40 :11 0.60}
 :measured-qubits [0 1]
 :shots 100}
;; Expected frequencies: {0 40, 3 60}
```

## Summary

The conversion strategy handles two distinct Braket result formats:

1. **SV1 Simulator**: Individual measurements → frequencies → probabilities
2. **QPU**: Probabilities → frequencies → sampled outcomes

Both are converted to QClojure's unified format with:
- Frequencies as the fundamental representation
- Empirical probabilities derived from frequencies
- Measurement outcomes vector for compatibility
- Source tagged as `:braket-hardware`

The key insight is that for hardware backends, we don't have access to the true quantum state, so empirical probabilities from measurements serve as both theoretical and empirical probabilities in the result format.
