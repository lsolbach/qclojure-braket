(ns org.soulspace.qclojure.adapter.backend.task
  "Functions for managing quantum jobs on AWS Braket."
  (:require [clojure.data.json :as json]
            [cognitect.aws.client.api :as aws]
            [org.soulspace.qclojure.adapter.backend.format :as fmt]
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
  ; TODO check s-3 vs s3
  (when-let [output-s3-bucket (:output-s3-bucket task-response)]
    (let [output-s3-dir (:output-s3-directory task-response)]
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
    (let [results (json/read-str results-json :key-fn fmt/->keyword)
          measurements (:measurements results)
          measurement-counts (:measurement-counts results)
          measurement-probabilities (:measurement-probabilities results)]

      {:raw-results results
       :measurements (or measurements measurement-counts)
       :probabilities measurement-probabilities
       :task-metadata (:task-metadata results)
       :additional-metadata (:additional-metadata results)})
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

;;;
;;; Backend State Management
;;;
(defn quantum-task
  "Get quantum task details from AWS Braket"
  [backend task-arn]
  (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                :request {:quantumTaskArn task-arn}}))]
    (println "GetQuantumTask response:" response)
    (if (:cognitect.anomalies/category response)
      {:error response}
      response)))

(defn job-status
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                                       :request {:quantumTaskArn task-arn}}))
          _ (println "Job status response:" response)]
      (if (:cognitect.anomalies/category response)
        :failed
        (case (:status response)
          "CREATED" :submitted
          "QUEUED" :queued
          "RUNNING" :running
          "COMPLETED" :completed
          "FAILED" :failed
          "CANCELLED" :cancelled
          :unknown)))
    :failed))

; TODO simplifix 
(defn job-result
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)
          response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetQuantumTask
                                                                       :request {:quantumTaskArn task-arn}}))
          _ (println "Job result response:" response)]
      (if (:cognitect.anomalies/category response)
        {:job-status :failed
         :job-id job-id
         :error-message (str "AWS error: " (pr-str response))}
        (if (= "COMPLETED" (:status response))
          ;; Retrieve actual results from S3
          (let [s3-results (retrieve-task-results (:s3-client backend) response)
                shots (get-in job-info [:options :shots] 0)
                _ (spit (str job-id "-s3results.edn") s3-results)]
            (if (:error s3-results)
              {:job-status :failed
               :job-id job-id
               :error-message (get-in s3-results [:error :message])}
              (let [meas (:measurements s3-results)
                    ;; Normalize to QClojure's :measurement-results map {"bitstring" count}
                    measurement-results (cond
                                          (map? meas) meas
                                          (sequential? meas)
                                          (->> meas
                                               (map (fn [bits]
                                                      (->> bits
                                                           (map str)
                                                           (apply str))))
                                               (frequencies))
                                          :else {})

                    ;; Build base Braket result
                    braket-result {:job-status :completed
                                   :job-id job-id
                                   :measurement-results measurement-results
                                   :probabilities (:probabilities s3-results)
                                   :shots shots
                                   :execution-time-ms (- (System/currentTimeMillis)
                                                         (:submitted-at job-info))
                                   :task-arn task-arn
                                   :raw-results (:raw-results s3-results)
                                   :task-metadata (:task-metadata s3-results)
                                   :s3-location (:s3-location s3-results)}
                    _ (spit (str job-id "-braket-result.edn") braket-result)
                    ;; Convert to QClojure format
                    result (convert-braket-results braket-result job-info)
                    _ (spit (str job-id "-result.edn") (fmt/format-edn result))]
                result)))
          {:job-status :running
           :job-id job-id
           :message "Job not completed yet"})))
    {:job-status :failed
     :job-id job-id
     :error-message "Job not found"}))


(defn cancel-job
  [backend job-id]
  (if-let [job-info (get-in @(:state backend) [:jobs job-id])]
    (let [task-arn (:task-arn job-info)]
      (try
        (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :CancelQuantumTask
                                                                           :request {:quantumTaskArn task-arn}}))]
          (if (:cognitect.anomalies/category response)
            :cannot-cancel
            (do
              (swap! (:state backend) assoc-in [:jobs job-id :cancelled-at] (System/currentTimeMillis))
              :cancelled)))
        (catch Exception _e
          :cannot-cancel)))
    :not-found))

(defn queue-status
  [backend]
  (let [device-arn (or (:id (:current-device @(:state backend)))
                       "arn:aws:braket:::device/quantum-simulator/amazon/sv1")]
    (try
      (let [response (fmt/clj-keys (aws/invoke (:braket-client backend) {:op :GetDevice
                                                                         :request {:deviceArn device-arn}}))]
        (if (:cognitect.anomalies/category response)
          {:error response}
          (let [queue-info (:device-queue-info response)]
            {:device-arn device-arn
             :queue-type (:queue-type queue-info)
             :queue-size (:queue-size queue-info)
             :priority (:queue-priority queue-info)
             :status (:device-status response)})))
      (catch Exception e
        {:error {:message (.getMessage e) :type :api-error}}))))



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