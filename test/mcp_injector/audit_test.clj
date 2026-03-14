(ns mcp-injector.audit-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.audit :as audit]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(deftest hmac-signature-test
  (testing "Deterministic signatures"
    (let [key "secret"
          data "some-data"
          sig1 (audit/hmac-sha256 key data)
          sig2 (audit/hmac-sha256 key data)]
      (is (= sig1 sig2))
      (is (string? sig1)))))

(deftest audit-log-chaining-test
  (let [log-file-path "test-audit.log.ndjson"
        log-file (io/file log-file-path)
        secret "test-secret"]
    (when (.exists log-file) (io/delete-file log-file))
    (audit/init-audit! log-file-path)

    (testing "Initial log entry has no previous signature"
      (let [entry1 (audit/append-event! secret :test-event {:foo "bar"})]
        (with-open [r (io/reader log-file)]
          (is (= 1 (count (line-seq r)))))
        (is (some? (:sig entry1)))
        (is (audit/verify-log log-file secret))))

    (testing "Subsequent entry chains to previous signature"
      (let [entry2 (audit/append-event! secret :test-event {:baz "qux"})]
        (with-open [r (io/reader log-file)]
          (is (= 2 (count (line-seq r)))))
        (is (audit/verify-log log-file secret))))

    (testing "Tamper detection"
      (let [lines (with-open [r (io/reader log-file)] (vec (line-seq r)))
            ;; Tamper with the first line's data
            tampered-line (json/generate-string (assoc (json/parse-string (first lines) true) :data {:foo "tampered"}))
            tampered-log (io/file "tampered-audit.log.ndjson")]
        (spit tampered-log (str tampered-line "\n" (second lines) "\n"))
        (is (not (audit/verify-log tampered-log secret)))
        (io/delete-file tampered-log)))

    (testing "Invalid key detection"
      (is (not (audit/verify-log log-file "wrong-secret"))))

    (audit/close-audit!)
    (io/delete-file log-file)))
