(ns mcp-injector.mcp-session-test
  "Tests for Streamable HTTP transport support.
   Verifies that mcp-injector correctly handles sessions, handshakes, and headers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
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

(deftest mcp-session-handshake-works
  (testing "Injector performs initialize handshake and sends session ID in subsequent calls"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)
    (reset! (:received-requests *test-mcp*) [])

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
          mcp-requests @(:received-requests *test-mcp*)
          get-method (fn [req] (get-in req [:body :params :method] (get-in req [:body :method])))
          get-sid (fn [req]
                    (let [headers (:headers req)]
                      (or (get headers "mcp-session-id")
                          (get headers :mcp-session-id)
                          (get headers "Mcp-Session-Id")
                          (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v)) headers))))
          init-req (some #(when (= "initialize" (get-method %)) %) mcp-requests)
          initialized-req (some #(when (= "notifications/initialized" (get-method %)) %) mcp-requests)
          list-req (some #(when (= "tools/list" (get-method %)) %) mcp-requests)]

      (is (= 200 (:status response)))

      ;; MCP server should have received the handshake
      (is (some? init-req))
      (is (some? initialized-req))
      (is (some? list-req))

      ;; initialized and list-tools SHOULD have the session ID
      (is (some? (get-sid initialized-req)))
      (is (= (get-sid initialized-req) (get-sid list-req))))))
