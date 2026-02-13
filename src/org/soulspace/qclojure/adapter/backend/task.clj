(ns org.soulspace.qclojure.adapter.backend.task
  "Functions for managing quantum jobs on AWS Braket."
  (:require [clojure.data.json :as json]
            [camel-snake-kebab.core :as csk]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.domain.circuit :as circuit]
            ; [org.soulspace.qclojure.domain.state :as state]
            ))

;;;
;;;
;;;
(def default-s3-config
  "Default configuration for AWS S3 client"
  {:api :s3
   :region "us-east-1"})

(defn create-s3-client
  "Creates an AWS S3 client with optional configuration overrides.
   
   Args:
     config-overrides - Map of configuration overrides (optional)
   
   Returns:
     AWS S3 client instance"
  ([]
   (create-s3-client {}))
  ([config-overrides]
   (let [config (merge default-s3-config config-overrides)]
     (aws/client config))))

;;;
;;; Multi-QPU Device Management Functions
;;;
;;
;; S3 Result Retrieval Functions
;;
(defn parse-s3-location
  "Parse S3 bucket and key from Braket task response"
  [task-response]
  (when-let [output-s3-bucket (:outputS3Bucket task-response)]
    (let [output-s3-dir (:outputS3Directory task-response)]
      {:bucket output-s3-bucket
       :key-prefix (str output-s3-dir "/")
       :results-key (str output-s3-dir "/results.json")
       :task-metadata-key (str output-s3-dir "/task-metadata.json")})))

(defn download-s3-object
  "Download an object from S3 and return its content as a string"
  [s3-client bucket key]
  (try
    (let [response (aws/invoke s3-client {:op :GetObject
                                          :request {:Bucket bucket
                                                    :Key key}})]
      (if (:cognitect.anomalies/category response)
        {:error response}
        {:content (slurp (:Body response))}))
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :s3-error
               :bucket bucket
               :key key}})))

; TODO: move to QClojure domain.state
(defn bitstring-to-index
  "Convert a bitstring like \"00\" or \"11\" to basis state index."
  [bitstring num-qubits]
  (reduce (fn [acc [idx bit]]
            (+ acc (* (- (int bit) (int \0))
                      (bit-shift-left 1 (- num-qubits idx 1)))))
          0
          (map-indexed vector bitstring)))

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
                                     [outcome (double (/ count shots))])
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
     :source :braket-sim}))

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
        outcomes (->> frequencies
                      (mapcat (fn [[outcome count]]
                                (repeat count outcome)))
                      (shuffle)
                      (vec))

        ;; For hardware, theoretical = empirical
        measurement-probs-vec (mapv #(get empirical-probs % 0.0)
                                    (range (bit-shift-left 1 num-qubits)))]

    {:measurement-outcomes outcomes
     :measurement-probabilities measurement-probs-vec
     :empirical-probabilities empirical-probs
     :shot-count shots
     :measurement-qubits (range num-qubits)
     :frequencies frequencies
     :source :braket-qpu}))

; TODO consolidate keywords
(defn detect-device-type
  "Detect whether results are from simulator or QPU.
   
   Heuristic:
   - if we have raw measurement bit vectors, it's a simulator;
   - if we have probabilities, it's a QPU.
   
   Parameters:
   - raw-results: Raw results from Braket task (as parsed from JSON)
   
   Returns:
   :simulator, :qpu, or :unknown"
  [raw-results]
  (cond
    (contains? raw-results :measurements)
    :simulator

    (contains? raw-results :measurement-probabilities)
    :qpu

    :else
    :unknown))

(defn convert-braket-measurement-results
  "Convert Braket measurement results to QClojure format.
   
   Automatically detects device type and applies appropriate conversion.
   
   Parameters:
   - raw-results: Raw results from Braket task (as parsed from JSON)
   - shots: Number of shots executed
   - num-qubits: Number of qubits in the circuit
   
   Returns:
   Measurement results in QClojure format"
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
       (:measurement-probabilities raw-results)
       shots
       num-qubits)

      ;; Unknown format
      (throw (ex-info "Unknown Braket result format"
                      {:raw-results raw-results
                       :device-type device-type})))))

(defn convert-braket-results
  "Convert Braket task result to QClojure result format.
   
   Parameters:
   - braket-result: Raw result from job-result function
   - job-info: Job info from backend state containing circuit and options
   
   Returns:
   Result in QClojure format compatible with simulator backends"
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

(defn parse-braket-results
  "Parse Braket quantum task results from JSON"
  [results-json]
  (try
    (let [results (json/read-str results-json :key-fn csk/->kebab-case-keyword)
          measurements (:measurements results)
          measurement-counts (:measurementCounts results)
          measurement-probabilities (:measurementProbabilities results)]

      {:raw-results results
       :measurements (or measurements measurement-counts)
       :probabilities measurement-probabilities
       :task-metadata (:taskMetadata results)
       :additional-metadata (:additionalMetadata results)})
    (catch Exception e
      {:error {:message (.getMessage e)
               :type :json-parse-error
               :content results-json}})))

(defn retrieve-task-results
  "Retrieve and parse results from S3 for a completed Braket task"
  [s3-client task-response]
  (if-let [s3-location (parse-s3-location task-response)]
    (let [{:keys [bucket results-key]} s3-location
          results-download (download-s3-object s3-client bucket results-key)]

      (if (:error results-download)
        {:error (:error results-download)}
        (let [parsed-results (parse-braket-results (:content results-download))]
          (if (:error parsed-results)
            {:error (:error parsed-results)}
            (merge parsed-results
                   {:s3-location s3-location
                    :retrieved-at (System/currentTimeMillis)})))))
    {:error {:message "No S3 output location found in task response"
             :type :missing-s3-location}}))

(comment ; conversion tests
  (def sim-result
    {:measurements [[0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]
                    [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0] [1 1] [1 1] [0 0]]
     :measured-qubits [0 1]
     :shots 100})

  (def qpu-result
    {:measurement-probabilities {:00 0.40 :11 0.60}
     :measured-qubits [0 1]
     :shots 100})

  (convert-simulator-measurements (:measurements sim-result) (:shots sim-result) 2)
  (convert-qpu-probabilities (:measurement-probabilities qpu-result) (:shots qpu-result) 2)
  ;
  )