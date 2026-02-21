(ns mcp-injector.discovery-test
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

(defn extra-tools []
  [{:name "list_charges"
    :description "List charges"
    :inputSchema {:type "object" :properties {:limit {:type "number"}}}}
   {:name "retrieve_customer"
    :description "Retrieve a customer"
    :inputSchema {:type "object" :properties {:customer_id {:type "string"}}}}])

(defn discovery-fixture
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

(use-fixtures :each discovery-fixture)

(deftest progressive-discovery-flow
  (testing "Full progressive discovery: Directory -> get_tool_schema -> call"
    (mcp/clear-tool-cache!)
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
          requests @(:received-requests *test-llm*)
          first-req (first requests)
          tool-result-msg (get-in (last requests) [:messages])
          tool-result-msg (some #(when (= "tool" (:role %)) %) tool-result-msg)]

      (is (= 200 (:status response)))
      ;; Check that directory was injected
      (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
      
      ;; Check that get_tool_schema was available
      (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))

      ;; Check tool call content
      (is (str/includes? (:content tool-result-msg) "customer@example.com")))))

(deftest tool-discovery-filtering-nil-shows-all
  (testing "When :tools is nil, all discovered tools from MCP server should be shown"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)
    (let [mcp-server (test-mcp/start-server :tools (extra-tools))
          llm-server (test-llm/start-server)
          injector (core/start-server {:port 0
                                       :host "127.0.0.1"
                                       :llm-url (str "http://localhost:" (:port llm-server))
                                       :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                                        :tools nil}}
                                                     :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
      (try
        (test-llm/set-next-response llm-server {:role "assistant" :content "ok"})
        (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                                   {:body (json/generate-string
                                           {:model "gpt-4o"
                                            :messages [{:role "user" :content "test"}]})
                                    :headers {"Content-Type" "application/json"}})
              first-req (first @(:received-requests llm-server))]
          (is (= 200 (:status response)))
          (is (str/includes? (get-in first-req [:messages 0 :content]) "retrieve_customer"))
          (is (str/includes? (get-in first-req [:messages 0 :content]) "list_charges")))
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm-server)
          (test-mcp/stop-server mcp-server))))))

(deftest tool-discovery-filtering-specified-shows-subset
  (testing "When :tools is specified, only those tools should be shown"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)
    (let [mcp-server (test-mcp/start-server :tools (extra-tools))
          llm-server (test-llm/start-server)
          injector (core/start-server {:port 0
                                       :host "127.0.0.1"
                                       :llm-url (str "http://localhost:" (:port llm-server))
                                       :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                                        :tools ["retrieve_customer"]}}
                                                     :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
      (try
        (test-llm/set-next-response llm-server {:role "assistant" :content "ok"})
        (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                                   {:body (json/generate-string
                                           {:model "gpt-4o"
                                            :messages [{:role "user" :content "test"}]})
                                    :headers {"Content-Type" "application/json"}})
              first-req (first @(:received-requests llm-server))]
          (is (= 200 (:status response)))
          (is (not (str/includes? (get-in first-req [:messages 0 :content]) "list_charges")))
          (is (str/includes? (get-in first-req [:messages 0 :content]) "retrieve_customer")))
        (finally
          (core/stop-server injector)
          (test-llm/stop-server llm-server)
          (test-mcp/stop-server mcp-server))))))
