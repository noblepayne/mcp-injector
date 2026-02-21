(ns mcp-injector.mcp-session-test
  "Tests for Streamable HTTP transport support.
   Verifies that mcp-injector correctly handles sessions, handshakes, and headers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.mcp-client :as mcp]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

(def ^:dynamic *test-mcp* nil)
(def ^:dynamic *test-llm* nil)
(def ^:dynamic *injector* nil)

(defn session-integration-fixture
  [test-fn]
  (let [mcp-server (test-mcp/start-server :require-session true)
        llm-server (test-llm/start-server)
        injector-server (core/start-server {:port 0
                                            :host "127.0.0.1"
                                            :llm-url (str "http://localhost:" (:port llm-server))
                                            :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                                             :tools [:retrieve_customer]}}
                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
    (try
      (binding [*test-mcp* mcp-server
                *test-llm* llm-server
                *injector* injector-server]
        (test-fn))
      (finally
        (core/stop-server injector-server)
        (test-llm/stop-server llm-server)
        (test-mcp/stop-server mcp-server)))))

(use-fixtures :each session-integration-fixture)

(defn clear-mcp-requests-fixture
  [test-fn]
  (when (and *test-mcp* (:received-requests *test-mcp*))
    (reset! (:received-requests *test-mcp*) []))
  (test-fn))

(use-fixtures :each clear-mcp-requests-fixture)

(deftest mcp-session-handshake-works
  (testing "Injector performs initialize handshake and sends session ID in subsequent calls"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)

    ;; Setup a tool call turn to force MCP interaction
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__stripe__retrieve_customer"
                                       :arguments {:customer_id "cus_123"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Discovered tool!"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user" :content "What can stripe do?"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          mcp-requests @(:received-requests *test-mcp*)]

      (is (= 200 (:status response)))

      ;; MCP server should have received:
      ;; 1. initialize
      ;; 2. notifications/initialized
      ;; 3. tools/list (triggered by get_tool_schema)
      (when (< (count mcp-requests) 3)
        (println "DEBUG: mcp-requests count:" (count mcp-requests))
        (println "DEBUG: mcp-requests:" mcp-requests))
      (is (>= (count mcp-requests) 3))

      (let [init-req (first mcp-requests)
            initialized-notif (second mcp-requests)
            ;; The one with the session ID
            list-req (last mcp-requests)
            session-id (get-in (first (filter #(get-in % [:headers "mcp-session-id"])
                                              mcp-requests))
                               [:headers "mcp-session-id"])]

        (is (= "initialize" (get-in init-req [:body :method])))
        (is (= "notifications/initialized" (get-in initialized-notif [:body :method])))
        (is (= "tools/list" (get-in list-req [:body :method])))

        ;; Subsequent requests MUST have the session ID
        (is (some? session-id))
        (is (= session-id (get-in list-req [:headers "mcp-session-id"])))

        ;; Protocol version header should be present (lowercase in http-kit server)
        (is (= "2025-03-26" (get-in list-req [:headers "mcp-protocol-version"])))))))
