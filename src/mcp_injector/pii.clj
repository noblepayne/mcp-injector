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
    :pattern #"\b(sk|pk|rk)_(live|test|production)_[a-zA-Z0-9]{24,}\b"
    :label "[STRIPE_API_KEY]"}
   {:id :OPENROUTER_API_KEY
    :pattern #"\bsk-or-v1-[a-f0-9]{64}\b"
    :label "[OPENROUTER_API_KEY]"}
   {:id :OPENAI_PROJECT_KEY
    :pattern #"\bsk-proj-[a-zA-Z0-9]{48}\b"
    :label "[OPENAI_PROJECT_KEY]"}
   {:id :ANTHROPIC_API_KEY
    :pattern #"\bant-api-key-v1-[a-zA-Z0-9_-]{90,100}(?![a-zA-Z0-9_-])"
    :label "[ANTHROPIC_API_KEY]"}
   {:id :GOOGLE_GEMINI_API_KEY
    :pattern #"\bAIzaSy[a-zA-Z0-9_-]{33}(?![a-zA-Z0-9_-])"
    :label "[GOOGLE_GEMINI_API_KEY]"}
   {:id :HUGGINGFACE_TOKEN
    :pattern #"\bhf_[a-zA-Z0-9]{34,}\b"
    :label "[HUGGINGFACE_TOKEN]"}
   {:id :BEARER_TOKEN
    :pattern #"\bBearer\s+[a-zA-Z0-9._-]{20,}(?![a-zA-Z0-9._-])"
    :label "[BEARER_TOKEN]"}
   {:id :DATABASE_URL
    :pattern #"\b(postgresql|mysql|mongodb)://[a-zA-Z0-9._%+-]+:[^@\s]+@[a-zA-Z0-9.-]+:[0-9]+/[a-zA-Z0-9._%+-]+(?![a-zA-Z0-9._%+-])"
    :label "[DATABASE_URL]"}
   {:id :SLACK_WEBHOOK
    :pattern #"\bhttps://hooks.slack.com/services/[A-Z0-9]+/[A-Z0-9]+/[a-zA-Z0-9]+\b"
    :label "[SLACK_WEBHOOK]"}
   {:id :PRIVATE_KEY_HEADER
    :pattern #"\b-----BEGIN (RSA|EC|DSA|OPENSSH) PRIVATE KEY-----\b"
    :label "[PRIVATE_KEY_HEADER]"}
   {:id :HEX_64
    :pattern #"\b[a-f0-9]{64}\b"
    :label "[HEX_64]"}
   {:id :GENERIC_SECRET
    :pattern #"\b[A-Za-z0-9+/_-]{48,}\b"
    :label "[GENERIC_SECRET]"}])

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
   Requires at least 4 distinct character classes OR at least 3 classes and 20+ chars."
  [s]
  (let [classes [(when (re-find #"[a-z]" s) :lower)
                 (when (re-find #"[A-Z]" s) :upper)
                 (when (re-find #"[0-9]" s) :digit)
                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]
        num-classes (count (remove nil? classes))]
    (cond
      (>= num-classes 4) true
      (= num-classes 3) (>= (count s) 20)
      :else false)))

(defn- safe-pattern?
  "Check if token matches known-safe patterns (paths, URLs, IPs, hashes, UUIDs).
   These patterns should never be flagged as high-entropy secrets."
  [token]
  (or
   (boolean (re-find #"(?i)^[a-z]:[/\\]" token))
   (boolean
    (and (re-find #"[/\\]" token)
         (not (re-find #"[^/\\a-zA-Z0-9._: -]" token))))
   (re-find #"^https?://" token)
   (boolean
    (and (re-find #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" token)
         (not (re-find #"[^0-9.]" token))))
   (boolean
    (re-find #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$" token))
   (boolean
    (and (re-find #":" token)
         (re-find #"^[0-9a-fA-F:]+$" token)))
   (boolean
    (re-find #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$" token))
   (boolean
    (re-find #"^\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\}$" token))))

(defn- likely-secret-context?
  "Check if token follows assignment-like patterns.
   Uses a proximity window before the token to detect assignment syntax."
  [text start-pos]
  (let [window-size 40 ; Slightly larger window for shell exports
        context-start (max 0 (- start-pos window-size))
        context (subs text context-start start-pos)]
    (some #(re-find % context)
          [#"(?i)secret\s*[:=]"
           #"(?i)api_?key\s*[:=]"
           #"(?i)token\s*[:=]"
           #"(?i)password\s*[:=]"
           #"(?i)credential\s*[:=]"
           #"(?i)auth(entication)?\s*[:=(]"
           #"(?i)[A-Z0-9_]+_KEY\s*[:=]"
           #"(?i)[A-Z0-9_]+_TOKEN\s*[:=]"
           #"(?i)\"?[a-z0-9_-]*(key|token|secret|pass)\"?\s*[:=]" ; JSON keys and lower-case env
           #"(?i)export\s+[A-Z0-9_]+\s*=" ; shell export
           #"(?i)set\s+[a-z0-9_]+\s+" ; set command
           #"\s+[a-z0-9_]+->\s*"])))

(defn- find-all-coordinates
  "Scans text for all patterns and return a list of maps containing:
   {:start <int> :end <int> :id <keyword> :value <string> :label <string>}
   
   Pre-compiled env-matches (Patterns) are used to avoid regex thrashing."
  [text patterns env-patterns-compiled {:keys [entropy-threshold proximity-check-enabled]
                                        :or {entropy-threshold 3.8
                                             proximity-check-enabled true}}]
  (let [;; 1. Pattern matches
        pattern-matches (mapcat (fn [{:keys [id pattern label]}]
                                  (let [matcher (re-matcher pattern text)]
                                    (loop [acc []]
                                      (if (.find matcher)
                                        (recur (conj acc {:start (.start matcher)
                                                          :end (.end matcher)
                                                          :id id
                                                          :value (.group matcher)
                                                          :label label}))
                                        acc))))
                                patterns)
        ;; 2. Pre-compiled Env patterns (Patterns are thread-safe, Matchers are not)
        env-matches (mapcat (fn [{:keys [id value pattern]}]
                              (let [matcher (re-matcher pattern text)]
                                (loop [acc []]
                                  (if (.find matcher)
                                    (recur (conj acc {:start (.start matcher)
                                                      :end (.end matcher)
                                                      :id id
                                                      :value value
                                                      :label (str "[" (name id) "]")}))
                                    acc))))
                            env-patterns-compiled)
        ;; 3. Entropy-based detections
        ;; Scan for density blocks (non-whitespace characters)
        ;; Guards:
        ;;   1. NOT a safe pattern (paths, URLs, IPs, hashes, UUIDs)
        ;;   2. Shannon entropy > threshold
        ;;   3. Character diversity check (4+ classes or 3 classes + 20+ chars)
        ;;   4. Proximity context check (assignment keywords nearby) when enabled
        entropy-matches (let [matcher (re-matcher #"[^\s]{13,}" text)]
                          (loop [acc []]
                            (if (.find matcher)
                              (let [token (.group matcher)
                                    token-start (.start matcher)
                                    token-end (.end matcher)
                                    safe? (safe-pattern? token)
                                    entropy-ok? (> (shannon-entropy token) entropy-threshold)
                                    diversity-ok? (character-diversity? token)
                                    context-ok? (or (not proximity-check-enabled)
                                                    (likely-secret-context? text token-start))]
                                (if (and (not safe?)
                                         entropy-ok?
                                         diversity-ok?
                                         context-ok?)
                                  (recur (conj acc {:start token-start
                                                    :end token-end
                                                    :id :HIGH_ENTROPY_SECRET
                                                    :value token
                                                    :label "[HIGH_ENTROPY_SECRET]"}))
                                  (recur acc)))
                              acc)))]
    (concat pattern-matches env-matches entropy-matches)))

(defn- resolve-coordinates
  "Sorts coordinates and removes overlaps.
   Algorithm:
   1. Sort by start ascending, then by length descending.
   2. Iterate and keep matches that do not overlap with the previous kept match."
  [coords]
  (let [sorted (sort-by (juxt :start (fn [c] (- (:start c) (:end c)))) coords)]
    (reduce (fn [acc next-match]
              (let [last-match (peek acc)]
                (if (or (nil? last-match)
                        (>= (:start next-match) (:end last-match)))
                  (conj acc next-match)
                  acc)))
            []
            sorted)))

(defn- apply-redactions
  "Builds a new string by replacing text at coordinates with labels."
  [text resolved-coords]
  (let [sb (StringBuilder.)]
    (loop [curr-idx 0
           matches resolved-coords]
      (if (empty? matches)
        (do (.append sb (subs text curr-idx))
            (.toString sb))
        (let [{:keys [start end label]} (first matches)]
          (.append sb (subs text curr-idx start))
          (.append sb label)
          (recur end (rest matches)))))))

(defn- compile-env-patterns
  "Helper to pre-compile environment variable values into regex patterns."
  [env]
  (keep (fn [[k v]]
          (when (and (not (empty? v)) (> (count v) 5))
            {:id (keyword (str "ENV_VAR_" k))
             :value v
             :pattern (re-pattern (java.util.regex.Pattern/quote v))}))
        env))

(defn scan-and-redact
  "Scans input text for PII patterns, high-entropy secrets, and env vars.
   Returns {:text redacted-text :detected [label-ids] :matches {label [raw-matches]}}"
  [text {:keys [patterns entropy-threshold env proximity-check-enabled]
         :or {patterns default-patterns
              entropy-threshold 3.8
              proximity-check-enabled true
              env {}}}]
  (let [env-patterns-compiled (compile-env-patterns env)
        scan-config {:entropy-threshold entropy-threshold
                     :proximity-check-enabled proximity-check-enabled}
        all-coords (find-all-coordinates text patterns env-patterns-compiled scan-config)
        resolved (resolve-coordinates all-coords)
        redacted-text (apply-redactions text resolved)
        detected (distinct (map :id resolved))
        matches-map (reduce (fn [acc {:keys [id value]}]
                              (update acc id (fnil conj []) value))
                            {}
                            resolved)]
    {:text redacted-text
     :detected detected
     :matches matches-map}))

(defn generate-token
  "Generate a deterministic, truncated SHA-256 hash token."
  [label value salt]
  (let [input (str (name label) "|" value "|" salt)
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input "UTF-8"))
        hash-str (format "%064x" (BigInteger. 1 digest))
        truncated (subs hash-str 0 24)]
    (str "[" (name label) "_" truncated "]")))

(defn- redact-string-value
  "Redact a single string value using coordinates to prevent offset collisions."
  [v config vault]
  (if (or (not (string? v)) (empty? v))
    [v vault []]
    (let [salt (:salt config)
          patterns (or (:patterns config) default-patterns)
          entropy-threshold (or (:entropy-threshold config) 3.8)
          proximity-check-enabled (or (:proximity-check-enabled config) true)
          env-patterns-compiled (or (:compiled-env config) [])
          scan-config {:entropy-threshold entropy-threshold
                       :proximity-check-enabled proximity-check-enabled}
          coords (find-all-coordinates v patterns env-patterns-compiled scan-config)
          resolved (resolve-coordinates coords)]
      (if (empty? resolved)
        [v vault []]
        (if (>= (count vault) max-vault-size)
          [(str "[VAULT_OVERFLOW_" (name (:id (first resolved))) "]") vault (map :id resolved)]
          (let [sb (StringBuilder.)
                [final-vault final-tokens]
                (loop [curr-idx 0
                       matches resolved
                       current-vault vault
                       tokens []]
                  (if (empty? matches)
                    (do (.append sb (subs v curr-idx))
                        [current-vault tokens])
                    (let [{:keys [start end id value]} (first matches)
                          token (generate-token id value salt)
                          new-vault (assoc current-vault token value)]
                      (.append sb (subs v curr-idx start))
                      (.append sb token)
                      (recur end (rest matches) new-vault (conj tokens value)))))]
            [(.toString sb) final-vault final-tokens]))))))

(defn- redact-impl
  "Recursive helper for immutable vault threading."
  ([data config vault detected]
   (redact-impl data config vault detected 0))
  ([data config vault detected depth]
   (if (>= depth 20)
     ["[RECURSION_DEPTH_LIMIT]" vault detected]
     (cond
       (string? data)
       (redact-string-value data config vault)

       (map? data)
       (reduce
        (fn [[m vault-acc det-acc] [k v]]
          (let [[new-val new-vault new-det] (redact-impl v config vault-acc det-acc (inc depth))]
            [(assoc m k new-val) new-vault (into det-acc new-det)]))
        [(empty data) vault detected]
        data)

       (sequential? data)
       (let [[new-vec final-vault final-det]
             (reduce
              (fn [[v vault-acc det-acc] item]
                (let [[new-item new-vault new-det] (redact-impl item config vault-acc det-acc (inc depth))]
                  [(conj v new-item) new-vault (into det-acc new-det)]))
              [[] vault detected]
              data)]
         [(cond
            (or (list? data) (instance? clojure.lang.ISeq data)) (apply list new-vec)
            (set? data) (into (empty data) new-vec)
            :else new-vec)
          final-vault
          final-det])

       :else
       [data vault detected]))))

(defn redact-data
  "Recursively walk a data structure, redact string values, store in vault.
   Returns [redacted-data new-vault-map detected-labels]"
  ([data config]
   (redact-data data config {}))
  ([data config vault]
   (let [;; PRE-COMPILE ONCE PER REQUEST (Pattern is thread-safe, Matcher is not)
         compiled-env (compile-env-patterns (or (:env config) {}))
         ;; Inject compiled env into config so the recursive walker doesn't recalculate it
         optimized-config (assoc config :compiled-env compiled-env)
         [redacted final-vault detected] (redact-impl data optimized-config vault [] 0)]
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
