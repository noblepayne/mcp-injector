(ns mcp-injector.json-error-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [org.httpkit.client :as http]))

(deftest test-invalid-json-response
  (let [injector (core/start-server {:port 0 :llm-url "http://localhost:0" :mcp-servers {:servers {}}})
        port (:port injector)]
    (try
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body "NOT_JSON"
                                  :headers {"Content-Type" "application/json"}})
            body (json/parse-string (:body response) true)]
        (is (= 400 (:status response)))
        (is (= "json_parse_error" (get-in body [:error :type])))
        (is (clojure.string/includes? (get-in body [:error :message]) "Failed to parse JSON body")))
      (finally
        (core/stop-server injector)))))
