(ns mcp-injector.mcp-client-headers-test
  "Tests for StreamableHTTP transport header compliance.
   Verifies mcp-injector sends correct Accept header per MCP spec."
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

(defn headers-test-fixture
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

(use-fixtures :each headers-test-fixture)

(deftest initialize-request-includes-accept-header
  (testing "MCP initialize request includes required Accept header per StreamableHTTP spec"
    (mcp/clear-tool-cache!)
    (test-llm/clear-responses *test-llm*)
    (reset! (:received-requests *test-mcp*) [])

    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant" :content "ok"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user" :content "test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          mcp-requests @(:received-requests *test-mcp*)
          init-req (some #(when (= "initialize" (get-in % [:body :params :method] (get-in % [:body :method]))) %)
                         mcp-requests)]
      (is (= 200 (:status response)))
      (is (not (nil? init-req)))
      (let [accept (get-in init-req [:headers "accept"] (get-in init-req [:headers :accept]))]
        (is (str/includes? accept "application/json"))))))
