(ns mcp-injector.pii
  (:require [clojure.string :as str]))

(def default-patterns
  [{:id :EMAIL_ADDRESS
    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
    :label "[EMAIL_ADDRESS]"}
   {:id :IBAN_CODE
    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
    :label "[IBAN_CODE]"}])

(defn shannon-entropy
  "Calculates the Shannon entropy of a string."
  [s]
  (if (empty? s)
    0.0
    (let [freqs (vals (frequencies s))
          len (count s)]
      (- (reduce + (map (fn [f]
                          (let [p (/ f len)]
                            (* p (/ (Math/log p) (Math/log 2)))))
                        freqs))))))

(defn- character-diversity?
  "Checks if a string contains at least 3 distinct character classes."
  [s]
  (let [classes [(when (re-find #"[a-z]" s) :lower)
                 (when (re-find #"[A-Z]" s) :upper)
                 (when (re-find #"[0-9]" s) :digit)
                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]]
    (>= (count (remove nil? classes)) 3)))

(defn- mask-string
  "Fixed-length mask to prevent leaking structural entropy."
  [_s]
  "********")

(defn- redact-match [mode label match]
  (case mode
    :replace label
    :mask (mask-string match)
    :hash (str "#" (hash match))
    label))

(defn- scan-env [text env-vars mode]
  (reduce-kv
   (fn [acc k v]
     ;; Case-sensitive match for env vars is usually safer, 
     ;; but we ensure the value is long enough to avoid false positives.
     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
       (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
       acc))
   text
   env-vars))

(defn- find-env-detections [text env-vars]
  (keep (fn [[k v]]
          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
            (keyword (str "ENV_VAR_" k))))
        env-vars))

(defn- scan-entropy [text threshold mode]
  (let [tokens (str/split text #"\s+")]
    (reduce
     (fn [acc token]
       ;; Threshold raised to 4.0 + diversity check + length check
       (if (and (> (count token) 12)
                (> (shannon-entropy token) threshold)
                (character-diversity? token))
         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
         acc))
     text
     tokens)))

(defn scan-and-redact
  "Scans input text for PII patterns, high-entropy secrets, and env vars.
   Calculations are performed sequentially on the text."
  [text {:keys [mode patterns entropy-threshold env]
         :or {mode :replace
              patterns default-patterns
              entropy-threshold 4.0
              env {}}}]
  (let [;; 1. Regex patterns (Standard PII)
        regex-result (reduce
                      (fn [state {:keys [id pattern label]}]
                        (if (seq (re-seq pattern (:text state)))
                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
                           :detected (conj (:detected state) id)}
                          state))
                      {:text text :detected []}
                      patterns)

        ;; 2. Env vars (Exact matches)
        env-text (scan-env (:text regex-result) env mode)
        env-detections (find-env-detections text env)

        ;; 3. Entropy (Heuristic secrets)
        final-text (scan-entropy env-text entropy-threshold mode)
        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]

    {:text final-text
     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
