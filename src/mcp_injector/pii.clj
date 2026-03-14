(ns mcp-injector.pii
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.security MessageDigest)))

(def default-patterns
  [{:id :EMAIL_ADDRESS
    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
    :label "[EMAIL_ADDRESS]"}
   {:id :IBAN_CODE
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
       (if (and (> (count token) 12)
                (> (shannon-entropy token) threshold)
                (character-diversity? token))
         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
         acc))
     text
     tokens)))

(defn scan-and-redact
  "Scans input text for PII patterns, high-entropy secrets, and env vars."
  [text {:keys [mode patterns entropy-threshold env]
         :or {mode :replace
              patterns default-patterns
              entropy-threshold 4.0
              env {}}}]
  (let [regex-result (reduce
                      (fn [state {:keys [id pattern label]}]
                        (if (seq (re-seq pattern (:text state)))
                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
                           :detected (conj (:detected state) id)}
                          state))
                      {:text text :detected []}
                      patterns)
        env-text (scan-env (:text regex-result) env mode)
        env-detections (find-env-detections text env)
        final-text (scan-entropy env-text entropy-threshold mode)
        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
    {:text final-text
     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))

(defn generate-token
  "Generate a deterministic, truncated SHA-256 hash token.
   Uses 12 hex chars (48 bits) to reduce collision probability."
  [label value salt]
  (let [input (str (name label) "|" value "|" salt)
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
        hash-str (->> digest
                      (map (partial format "%02x"))
                      (apply str))
        truncated (subs hash-str 0 12)]
    (str "[" (name label) "_" truncated "]")))

(defn- redact-string-value
  "Redact a single string value, returning [redacted-text token detected-label]"
  [v config]
  (if-not (string? v)
    [v nil nil]
    (if (empty? v)
      [v nil nil]
      (let [vault (:vault config)
            salt (:salt config)
            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
        (cond
          existing-token [existing-token nil nil]
          previous-token [previous-token nil nil]
          :else
          (let [result (scan-and-redact v config)]
            (if (seq (:detected result))
              (let [detected (first (:detected result))
                    token (generate-token detected v salt)]
                (swap! vault assoc token v)
                [token token detected])
              [(:text result) nil nil])))))))

(defn redact-data
  "Recursively walk a data structure, redact string values, store in vault.
    Returns [redacted-data vault-atom detected-labels]"
  ([data config]
   (redact-data data config (atom {})))
  ([data config vault]
   (let [config-with-vault (assoc config :vault vault)
         detected-labels (atom [])
         redacted (walk/postwalk
                   (fn [x]
                     (if (string? x)
                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
                         (when detected (swap! detected-labels conj detected))
                         redacted-text)
                       x))
                   data)]
     [redacted vault @detected-labels])))

(defn restore-tokens
  "Recursively walk a data structure, replacing tokens with original values from vault."
  [data vault]
  (let [v-map @vault]
    (if (empty? v-map)
      data
      (walk/postwalk
       (fn [x]
         (if (string? x)
           (reduce
            (fn [s [token original]]
              (if (and (string? s) (str/includes? s token))
                (str/replace s (str token) (str original))
                s))
            x
            v-map)
           x))
       data))))
