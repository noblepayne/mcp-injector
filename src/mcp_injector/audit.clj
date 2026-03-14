(ns mcp-injector.audit
  (:require [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.security SecureRandom]
           [java.io BufferedWriter FileWriter]))

(def ^:private ^String BASE32_ALPHABET "0123456789ABCDEFGHJKMNPQRSTVWXYZ")
(def ^:private random (SecureRandom.))
(def ^:private log-lock (Object.))
(def ^:private last-sig-state (atom ""))
(def ^:private audit-writer (atom nil))

(defn gen-ulid
  "Generates a 26-character ULID (timestamp + randomness).
   Simplified implementation for Babashka."
  []
  (let [now (System/currentTimeMillis)
        ts-part (loop [n now res ""]
                  (if (= (count res) 10)
                    res
                    (let [idx (int (mod n 32))]
                      (recur (quot n 32) (str (nth BASE32_ALPHABET idx) res)))))
        rand-part (apply str (repeatedly 16 #(nth BASE32_ALPHABET (.nextInt random 32))))]
    (str ts-part rand-part)))

(defn hmac-sha256
  "Calculates the HMAC-SHA256 signature of data using the secret key."
  [key data]
  (let [hmac (Mac/getInstance "HmacSHA256")
        secret-key (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")]
    (.init hmac secret-key)
    (.encodeToString (Base64/getEncoder) (.doFinal hmac (.getBytes data "UTF-8")))))

(defn- get-last-sig-from-file
  "Reads the last signature from an append-only NDJSON log file.
   Used only during initialization."
  [log-file]
  (if (and (.exists log-file) (> (.length log-file) 0))
    (with-open [reader (io/reader log-file)]
      (let [lines (line-seq reader)]
        (if (seq lines)
          (or (:sig (json/parse-string (last lines) true)) "")
          "")))
    ""))

(defn init-audit!
  "Initializes the audit system: opens the writer and restores the last signature state."
  [path]
  (locking log-lock
    (let [f (io/file path)
          parent (.getParentFile f)]
      (when parent
        (io/make-parents f)
        (when-not (.exists parent)
          (throw (ex-info (str "Could not create audit log directory: " (.getAbsolutePath parent))
                          {:path path :absolute-path (.getAbsolutePath f)}))))
      (reset! last-sig-state (get-last-sig-from-file f))
      (when-let [old-w @audit-writer] (.close ^BufferedWriter old-w))
      (try
        (reset! audit-writer (BufferedWriter. (FileWriter. f true)))
        (catch java.io.FileNotFoundException e
          (throw (ex-info (str "Audit log file not accessible: " (.getAbsolutePath f))
                          {:path path :absolute-path (.getAbsolutePath f)} e)))))))

(defn close-audit! []
  (locking log-lock
    (when-let [w @audit-writer]
      (.close ^BufferedWriter w)
      (reset! audit-writer nil))))

(defn append-event!
  "Appends a signed event to the log file. Chained via in-memory state and locked for concurrency."
  [secret type data]
  (locking log-lock
    (if-let [w ^BufferedWriter @audit-writer]
      (let [last-sig @last-sig-state
            event {:id (gen-ulid)
                   :ts (str (java.time.Instant/now))
                   :type (name type)
                   :data data
                   :prev-sig last-sig}
            sig (hmac-sha256 secret (json/generate-string event))
            final-entry (assoc event :sig sig)
            entry-string (str (json/generate-string final-entry) "\n")]
        (.write w entry-string)
        (.flush w)
        (reset! last-sig-state sig)
        final-entry)
      (throw (Exception. "Audit system not initialized. Call init-audit! first.")))))

(defn verify-log
  "Verifies the cryptographic integrity of an NDJSON audit log file."
  [log-file secret]
  (if (not (.exists log-file))
    true
    (with-open [reader (io/reader log-file)]
      (loop [lines (line-seq reader)
             expected-prev-sig ""]
        (if-let [line (first lines)]
          (let [entry (json/parse-string line true)
                actual-sig (:sig entry)
                event-data (dissoc entry :sig)
                computed-sig (hmac-sha256 secret (json/generate-string event-data))]
            (if (and (= actual-sig computed-sig)
                     (= expected-prev-sig (:prev-sig entry)))
              (recur (rest lines) actual-sig)
              false))
          true)))))
