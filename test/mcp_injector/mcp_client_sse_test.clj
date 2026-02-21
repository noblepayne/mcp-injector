(ns mcp-injector.mcp-client-sse-test
  "Tests for MCP Streamable HTTP (SSE) response handling.
   Verifies mcp-injector correctly parses tool responses wrapped in SSE."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.mcp-client :as mcp]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.integration-test :as integration]))

(def ^:dynamic *test-mcp-sse* nil)
(def ^:dynamic *test-mcp-json* nil)
(def ^:dynamic *test-llm* nil)
(def ^:dynamic *injector* nil)

(defn sse-test-fixture
  [test-fn]
  (let [mcp-sse (test-mcp/start-test-mcp-server :response-mode :sse)
        mcp-json (test-mcp/start-test-mcp-server :response-mode :json)
        llm-server (test-llm/start-server)
        injector-server (core/start-server
                         {:port 0
                          :host "127.0.0.1"
                          :llm-url (str "http://localhost:" (:port llm-server))
                          :mcp-servers {:servers {:sse-server {:url (str "http://localhost:" (:port mcp-sse))}
                                                  :json-server {:url (str "http://localhost:" (:port mcp-json))}}
                                        :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
    (try
      (binding [*test-mcp-sse* mcp-sse
                *test-mcp-json* mcp-json
                *test-llm* llm-server
                *injector* injector-server]
        (test-fn))
      (finally
        (core/stop-server injector-server)
        (test-llm/stop-server llm-server)
        (test-mcp/stop-server mcp-sse)
        (test-mcp/stop-server mcp-json)))))

(use-fixtures :each sse-test-fixture)

(deftest sse-response-tools-list-works
  (testing "Injector correctly discovers tools from a server responding with SSE"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)

    ;; Trigger tool discovery via warm-up
    (mcp/warm-up! {:servers {:sse-server {:url (str "http://localhost:" (:port *test-mcp-sse*))}}})

    (let [tools (mcp/discover-tools "sse-server" {:url (str "http://localhost:" (:port *test-mcp-sse*))} nil)]
      (is (seq tools))
      (is (some #(= "retrieve_customer" (:name %)) tools)))))

(deftest sse-response-tool-call-works
  (testing "Tool results wrapped in SSE are correctly parsed and returned to LLM"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)

    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__sse-server__retrieve_customer"
                                       :arguments {:customer_id "sse_123"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant" :content "FOUND_IT"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user" :content "Find sse_123"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          mcp-reqs @(:received-requests *test-mcp-sse*)
          call-req (some #(when (= "tools/call" (get-in % [:body :method])) %) mcp-reqs)]

      (is (= 200 (:status response)))
      (is (some? call-req))
      (is (str/includes? (integration/body->string (:body response)) "FOUND_IT")))))

(deftest mixed-servers-json-and-sse
  (testing "Injector handles multiple servers with different response formats (JSON and SSE)"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)

    ;; Call JSON server tool
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__json-server__retrieve_customer"
                                       :arguments {:customer_id "json_123"}}])
    ;; Call SSE server tool
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__sse-server__retrieve_customer"
                                       :arguments {:customer_id "sse_123"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant" :content "BOTH_DONE"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user" :content "Check both"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          mcp-json-reqs @(:received-requests *test-mcp-json*)
          mcp-sse-reqs @(:received-requests *test-mcp-sse*)]

      (is (= 200 (:status response)))
      (is (some #(when (= "tools/call" (get-in % [:body :method])) %) mcp-json-reqs))
      (is (some #(when (= "tools/call" (get-in % [:body :method])) %) mcp-sse-reqs))
      (is (str/includes? (integration/body->string (:body response)) "BOTH_DONE")))))
