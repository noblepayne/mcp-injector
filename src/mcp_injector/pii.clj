(ns mcp-injector.pii
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.security MessageDigest)))

(def ^:const max-vault-size
  "Maximum number of unique PII tokens per request vault."
  500)

(def default-patterns
  [{:id :EMAIL_ADDRESS
    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
    :label "[EMAIL_ADDRESS]"}
   {:id :IBAN_CODE
    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
    :label "[IBAN_CODE]"}
   {:id :AWS_ACCESS_KEY_ID
    :pattern #"\b(AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}\b"
    :label "[AWS_ACCESS_KEY_ID]"}
   {:id :AWS_SECRET_ACCESS_KEY
    :pattern #"\b[A-Za-z0-9/+=]{40}\b"
    :label "[AWS_SECRET_ACCESS_KEY]"}
   {:id :GITHUB_TOKEN
    :pattern #"\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}\b"
    :label "[GITHUB_TOKEN]"}
   {:id :STRIPE_API_KEY
    :pattern #"\b(sk|pk)_(live|test)_[a-zA-Z0-9]{24,}\b"
    :label "[STRIPE_API_KEY]"}
   {:id :DATABASE_URL
    :pattern #"\b(postgresql|mysql|mongodb)://[a-zA-Z0-9._%+-]+:[^@\s]+@[a-zA-Z0-9.-]+:[0-9]+/[a-zA-Z0-9._%+-]+\b"
    :label "[DATABASE_URL]"}
   {:id :SLACK_WEBHOOK
    :pattern #"\bhttps://hooks.slack.com/services/[A-Z0-9]+/[A-Z0-9]+/[a-zA-Z0-9]+\b"
    :label "[SLACK_WEBHOOK]"}
   {:id :PRIVATE_KEY_HEADER
    :pattern #"\b-----BEGIN (RSA|EC|DSA|OPENSSH) PRIVATE KEY-----\b"
    :label "[PRIVATE_KEY_HEADER]"}])

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
  "Checks if a string contains sufficient character diversity to be considered a secret.
   Requires at least 4 distinct character classes and minimum length for fewer classes."
  [s]
  (let [classes [(when (re-find #"[a-z]" s) :lower)
                 (when (re-find #"[A-Z]" s) :upper)
                 (when (re-find #"[0-9]" s) :digit)
                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]
        num-classes (count (remove nil? classes))]
    (cond
      (>= num-classes 4) true
      (= num-classes 3) (>= (count s) 30)  ; 3 classes needs to be longer
      :else false)))

(defn- scan-env [text env-vars]
  (reduce-kv
   (fn [acc k v]
     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
       (str/replace acc v (str "[ENV_VAR_" k "]"))
       acc))
   text
   env-vars))

(defn- find-env-detections [text env-vars]
  (keep (fn [[k v]]
          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
            (keyword (str "ENV_VAR_" k))))
        env-vars))

(defn- scan-entropy [text threshold]
  (let [tokens (str/split text #"\s+")]
    (reduce
     (fn [acc token]
       (if (and (> (count token) 20)  ; Increased from 12 to reduce false positives
                (> (shannon-entropy token) threshold)
                (character-diversity? token))
         (str/replace acc token "[HIGH_ENTROPY_SECRET]")
         acc))
     text
     tokens)))

(defn- find-all-matches
  "Returns a map of {label-id [match1 match2 ...]} for all PII found in text."
  [text patterns]
  (reduce
   (fn [acc {:keys [id pattern]}]
     (let [matches (re-seq pattern text)]
       (if (seq matches)
         (assoc acc id matches)
         acc)))
   {}
   patterns))

(defn scan-and-redact
  "Scans input text for PII patterns, high-entropy secrets, and env vars.
   Returns {:text redacted-text :detected [label-ids] :matches {label [raw-matches]}}"
  [text {:keys [patterns entropy-threshold env]
         :or {patterns default-patterns
              entropy-threshold 4.0
              env {}}}]
  (let [all-matches (find-all-matches text patterns)
        text-with-labels (reduce
                          (fn [t [label matches]]
                            (reduce #(str/replace %1 %2 (name label)) t (distinct matches)))
                          text
                          all-matches)
        env-text (scan-env text-with-labels env)
        env-detections (find-env-detections text env)
        final-text (scan-entropy env-text entropy-threshold)
        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])
        detected (distinct (concat (keys all-matches) env-detections entropy-detected))]
    {:text final-text
     :detected detected
     :matches all-matches}))

(defn generate-token
  "Generate a deterministic, truncated SHA-256 hash token.
   Uses 24 hex chars (96 bits) providing a collision bound of ~2^48 values per session.
   For in-memory request vaults (~500 entries), the probability of collision is effectively zero (<10^-20)."
  [label value salt]
  (let [input (str (name label) "|" value "|" salt)
        bytes (.getBytes input "UTF-8")
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)
        hash-str (->> digest
                      (map (partial format "%02x"))
                      (apply str))
        truncated (subs hash-str 0 24)]
    (str "[" (name label) "_" truncated "]")))

(defn- redact-string-value
  "Redact a single string value, returning [redacted-text new-vault labels-vec].
   Tokenizes each PII match individually to prevent overflow bypass."
  [v config vault]
  (if-not (string? v)
    [v vault []]
    (if (empty? v)
      [v vault []]
      (let [salt (:salt config)
            patterns (or (:patterns config) default-patterns)
            all-matches (find-all-matches v patterns)]
        (if (empty? all-matches)
          [v vault []]
          (let [sorted-labels (keys all-matches)
                vault-full? (>= (count vault) max-vault-size)]
            (if vault-full?
              [(str "[VAULT_OVERFLOW_" (name (first sorted-labels)) "]") vault sorted-labels]
              (loop [text v
                     current-vault vault
                     labels-to-process sorted-labels]
                (if (empty? labels-to-process)
                  [text current-vault (mapcat all-matches sorted-labels)]
                  (let [label (first labels-to-process)
                        matches (get all-matches label)
                        ;; Generate unique token for each distinct match value
                        unique-matches (distinct matches)
                        new-vault (reduce
                                   (fn [vault m]
                                     (let [token (generate-token label m salt)]
                                       (assoc vault token m)))
                                   current-vault
                                   unique-matches)
                        redacted (reduce
                                  (fn [t m]
                                    (let [token (generate-token label m salt)]
                                      (str/replace t (re-pattern (java.util.regex.Pattern/quote m)) token)))
                                  text
                                  unique-matches)]
                    (recur redacted new-vault (rest labels-to-process))))))))))))

(defn- redact-impl
  "Recursive helper for immutable vault threading.
   Includes depth limit to prevent StackOverflowError on malicious input."
  ([data config vault detected]
   (redact-impl data config vault detected 0))
  ([data config vault detected depth]
   (if (>= depth 20)
     ;; Depth limit reached - return truncated result
     ["[RECURSION_DEPTH_LIMIT]" vault detected]
     (cond
       (string? data)
       (redact-string-value data config vault)
       (map? data)
       (reduce
        (fn [[m vault-acc det-acc] [k v]]
          (let [[new-val new-vault new-det] (redact-impl v config vault-acc det-acc (inc depth))]
            [(assoc m k new-val) new-vault (into det-acc new-det)]))
        [{} vault detected]
        data)
       (sequential? data)
       (reduce
        (fn [[v vault-acc det-acc] item]
          (let [[new-item new-vault new-det] (redact-impl item config vault-acc det-acc (inc depth))]
            [(conj v new-item) new-vault (into det-acc new-det)]))
        [[] vault detected]
        data)
       :else
       [data vault detected]))))

(defn redact-data
  "Recursively walk a data structure, redact string values, store in vault.
   Returns [redacted-data new-vault-map detected-labels]"
  ([data config]
   (redact-data data config {}))
  ([data config vault]
   (let [[redacted final-vault detected] (redact-impl data config vault [] 0)]
     [redacted final-vault (distinct detected)])))

(defn restore-tokens
  "Recursively walk a data structure, replacing tokens with original values from vault."
  [data vault]
  (if (empty? vault)
    data
    (let [keys-pattern (str/join "|" (map #(java.util.regex.Pattern/quote %) (keys vault)))
          pattern (re-pattern keys-pattern)]
      (walk/postwalk
       (fn [x]
         (if (string? x)
           (str/replace x pattern (fn [match] (str (get vault match))))
           x))
       data))))
