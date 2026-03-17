(ns mcp-injector.governance-integration-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.core :as core]
            [mcp-injector.config :as config]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

;; ── pure function tests (fast, no servers) ──────────────────────

(deftest deep-merge-preserves-false
  (is (= {:pii {:enabled false}}
         (config/deep-merge {:pii {:enabled true}} {:pii {:enabled false}}))
      "deep-merge must not convert false to true")
  (is (= {:audit {:enabled false} :pii {:enabled true}}
         (config/deep-merge {:audit {:enabled true} :pii {:enabled true}}
                            {:audit {:enabled false}}))
      "deep-merge of partial map preserves other keys"))

(deftest resolve-governance-preserves-false
  (let [result (config/resolve-governance
                {:governance {:pii {:enabled false} :audit {:enabled false}}}
                {:audit-log-path "logs/test.ndjson"})]
    (is (false? (get-in result [:pii :enabled])))
    (is (false? (get-in result [:audit :enabled])))))

(deftest resolve-governance-nix-pattern
  ;; Nix puts governance at top level of EDN, not under llm-gateway
  (let [result (config/resolve-governance
                {:governance {:pii {:enabled false}}
                 :llm-gateway {:url "http://localhost:8080"}}
                {:audit-log-path "logs/test.ndjson"})]
    (is (false? (get-in result [:pii :enabled])))))

;; ── integration tests (spin up real servers) ────────────────────

(defn- make-injector [llm mcp governance-override]
  (core/start-server
   {:port 0
    :host "127.0.0.1"
    :llm-url (str "http://localhost:" (:port llm))
    :mcp-servers {:servers {:test {:url (str "http://localhost:" (:port mcp))
                                   :tools ["echo"]}}
                  :llm-gateway {:url (str "http://localhost:" (:port llm))
                                :governance (merge {:policy {:mode :permissive}}
                                                   governance-override)}}}))

(defn- last-user-message-seen-by-llm [llm]
  (let [received @(:received-requests llm)
        last-req (last received)]
    (-> last-req :messages last :content)))

(deftest pii-enabled-redacts-messages
  (testing "pii.enabled true (default) — email is redacted before reaching LLM"
    (let [llm (test-llm/start-server)
          mcp (test-mcp/start-test-mcp-server)
          injector (make-injector llm mcp {:pii {:enabled true}})]
      (try
        (test-llm/set-next-response llm {:role "assistant" :content "ok"})
        @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                    {:body (json/generate-string
                            {:model "test"
                             :messages [{:role "user" :content "email is wes@example.com"}]})
                     :headers {"Content-Type" "application/json"}})
        (let [msg (last-user-message-seen-by-llm llm)]
          (is (not (str/includes? msg "wes@example.com")) "email must be redacted")
          (is (str/includes? msg "EMAIL_ADDRESS") "redaction token must be present"))
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm)
          (test-mcp/stop-server mcp))))))

(deftest pii-disabled-passes-messages-unchanged
  (testing "pii.enabled false — email reaches LLM unchanged"
    (let [llm (test-llm/start-server)
          mcp (test-mcp/start-test-mcp-server)
          injector (make-injector llm mcp {:pii {:enabled false}})]
      (try
        (test-llm/set-next-response llm {:role "assistant" :content "ok"})
        @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                    {:body (json/generate-string
                            {:model "test"
                             :messages [{:role "user" :content "email is wes@example.com"}]})
                     :headers {"Content-Type" "application/json"}})
        (let [msg (last-user-message-seen-by-llm llm)]
          (is (str/includes? msg "wes@example.com") "email must NOT be redacted")
          (is (not (str/includes? msg "EMAIL_ADDRESS")) "no redaction tokens"))
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm)
          (test-mcp/stop-server mcp))))))

(deftest audit-disabled-writes-no-file
  (testing "audit.enabled false — no audit file written"
    (let [audit-path (str "/tmp/mcp-test-audit-" (System/currentTimeMillis) ".ndjson")
          llm (test-llm/start-server)
          mcp (test-mcp/start-test-mcp-server)
          injector (core/start-server
                    {:port 0
                     :host "127.0.0.1"
                     :audit-log-path audit-path
                     :llm-url (str "http://localhost:" (:port llm))
                     :mcp-servers {:servers {}
                                   :llm-gateway {:url (str "http://localhost:" (:port llm))
                                                 :governance {:pii {:enabled false}
                                                              :audit {:enabled false}
                                                              :policy {:mode :permissive}}}}})]
      (try
        (test-llm/set-next-response llm {:role "assistant" :content "ok"})
        @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                    {:body (json/generate-string
                            {:model "test"
                             :messages [{:role "user" :content "hello"}]})
                     :headers {"Content-Type" "application/json"}})
        (is (not (.exists (io/file audit-path)))
            "Audit file must NOT exist when audit.enabled is false")
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm)
          (test-mcp/stop-server mcp)
          (let [f (io/file audit-path)] (when (.exists f) (.delete f))))))))

(deftest audit-enabled-writes-file
  (testing "audit.enabled true — audit file is written"
    (let [audit-path (str "/tmp/mcp-test-audit-enabled-" (System/currentTimeMillis) ".ndjson")
          llm (test-llm/start-server)
          mcp (test-mcp/start-test-mcp-server)
          _ (io/make-parents (io/file audit-path))
          injector (core/start-server
                    {:port 0
                     :host "127.0.0.1"
                     :audit-log-path audit-path
                     :llm-url (str "http://localhost:" (:port llm))
                     :mcp-servers {:servers {}
                                   :llm-gateway {:url (str "http://localhost:" (:port llm))
                                                 :governance {:pii {:enabled false}
                                                              :audit {:enabled true
                                                                      :path audit-path}
                                                              :policy {:mode :permissive}}}}})]
      (try
        (test-llm/set-next-response llm {:role "assistant" :content "ok"})
        @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                    {:body (json/generate-string
                            {:model "test"
                             :messages [{:role "user" :content "hello"}]})
                     :headers {"Content-Type" "application/json"}})
        (let [f (io/file audit-path)]
          (is (.exists f) "Audit file must exist when audit.enabled is true")
          (is (pos? (.length f)) "Audit file must have content"))
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm)
          (test-mcp/stop-server mcp)
          (let [f (io/file audit-path)] (when (.exists f) (.delete f))))))))