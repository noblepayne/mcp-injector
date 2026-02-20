(ns mcp-injector.discovery-test
  "Tests for Phase 2: Progressive Tool Discovery.
   LLM should:
   1. See a directory of available tools
   2. Call get_tool_schema to discover parameters
   3. Call the actual tool with discovered parameters"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(def ^:dynamic *test-mcp* nil)
(def ^:dynamic *test-llm* nil)
(def ^:dynamic *injector* nil)

(defn integration-fixture
  [test-fn]
  (let [mcp-server (test-mcp/start-test-mcp-server)
        llm-server (test-llm/start-server)
        injector-server (core/start-server {:port 0
                                            :host "127.0.0.1"
                                            :llm-url (str "http://localhost:" (:port llm-server))
                                            :log-level "debug"
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

(use-fixtures :once integration-fixture)

(deftest progressive-discovery-flow
  (testing "Full progressive discovery: Directory -> get_tool_schema -> call"
    (test-llm/clear-responses *test-llm*)

    ;; Turn 1: LLM sees directory and asks for schema
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])

    ;; Turn 2: LLM receives schema and makes the actual call
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__stripe__retrieve_customer"
                                       :arguments {:customer_id "cus_123"}}])

    ;; Turn 3: Final response
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Found customer: customer@example.com"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Find customer cus_123"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)
          requests @(:received-requests *test-llm*)]

      (is (= 200 (:status response)))
      (is (str/includes? (get-in body [:choices 0 :message :content]) "customer@example.com"))

      ;; Verify 3 calls to LLM
      (is (= 3 (count requests)))

      ;; Verify first call had the directory and the meta-tool
      (let [first-req (first requests)
            system-msg (first (filter #(= "system" (:role %)) (:messages first-req)))]
        (is (str/includes? (:content system-msg) "Remote Capabilities"))
        (is (some #(= "get_tool_schema" (get-in % [:function :name])) (get-in first-req [:tools]))))

      ;; Verify second call included the tool result (the schema)
      (let [second-req (second requests)
            tool-result-msg (last (:messages second-req))]
        (is (= "tool" (:role tool-result-msg)))
        (is (str/includes? (:content tool-result-msg) "retrieve_customer")))

      ;; Verify third call included the actual tool result
      (let [third-req (last requests)
            tool-result-msg (last (:messages third-req))]
        (is (= "tool" (:role tool-result-msg)))
        (is (str/includes? (:content tool-result-msg) "customer@example.com"))))))
