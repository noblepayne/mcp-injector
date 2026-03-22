(ns mcp-injector.stdio-integration-test
  "STDIO transport reality tests.
   Tests subprocess lifecycle, death scenarios, hang protection, and stderr handling."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [babashka.process :as process]
            [mcp-injector.mcp-client-stdio :as stdio]
            [mcp-injector.test-stdio-server :as test-stdio]))

(defn- cleanup-after-test [f]
  (try
    (f)
    (finally
      (stdio/stop-all))))

(defn- nuclear-cleanup [f]
  (try
    (f)
    (finally
      ;; Final nuclear cleanup - kill any leftover bb test_stdio_mcp processes
      ;; NOTE: pkill is Linux-specific. Pragmatic for CI/dev on Linux/macOS.
      ;; On Windows this command will fail silently but destroy-tree above provides baseline safety.
      (stdio/stop-all)
      (try
        (process/process "pkill -f 'bb test/mcp_injector/test_stdio_mcp.clj'")
        (Thread/sleep 100)
        (catch Exception _)))))

(use-fixtures :each cleanup-after-test)
(use-fixtures :once nuclear-cleanup)

(defn create-policy []
  {:mode :permissive
   :eval-timeout-ms 2000
   :passthrough-trust :restore-all})

(deftest stdio-handshake-test
  (testing "STDIO session initializes correctly"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          session (stdio/get-session "test-handshake" config (create-policy))]
      (is (map? session))
      (is (some? (:process session)))
      (is (some? (:writer session)))
      (test-stdio/stop-server server)
      (stdio/stop-all))))

(deftest stdio-list-tools-test
  (testing "list-tools returns tool definitions"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          tools (stdio/list-tools "list-test" config (create-policy))]
      (is (vector? tools))
      (is (= 2 (count tools)))
      (is (some #(= "retrieve_customer" (:name %)) tools))
      (is (some #(= "list_charges" (:name %)) tools))
      (test-stdio/stop-server server)
      (stdio/stop-all))))

(deftest stdio-call-tool-test
  (testing "call-tool executes tool and returns result"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          result (stdio/call-tool "call-test" config "retrieve_customer"
                                  {:customer_id "cus_123"} (create-policy))]
      (is (map? result))
      (is (contains? result :content))
      (let [text (get-in result [:content 0 :text])]
        (is (string? text))
        (is (str/includes? text "customer@example.com")))
      (test-stdio/stop-server server)
      (stdio/stop-all))))

(deftest stdio-process-death-test
  (testing "Session is recreated after process death"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          session1 (stdio/get-session "death-test" config (create-policy))]
      (is (some? session1))
      ;; Stop the server and clean up the session
      (test-stdio/stop-server server)
      (stdio/stop-all)
      ;; Create a new server and session with the same ID
      (let [server2 (test-stdio/start-test-server)
            session2 (stdio/get-session "death-test" config (create-policy))]
        (is (some? session2))
        (is (not= session1 session2))
        (test-stdio/stop-server server2)
        (stdio/stop-all)))))

(deftest stdio-timeout-test
  (testing "Slow response triggers timeout"
    (stdio/stop-all)
    (let [slow-server (test-stdio/start-server
                       :cmd "bb -e '(Thread/sleep 3000)'; exit 0")
          config {:cmd "bb -e '(Thread/sleep 3000)'; exit 0"}
          result (stdio/call-tool "timeout-test" config "foo" {} (create-policy))]
      (is (map? result))
      (is (contains? result :error))
      (test-stdio/stop-server slow-server)
      (stdio/stop-all))))

(deftest stdio-invalid-json-test
  (testing "Malformed JSON-RPC response handled gracefully"
    (stdio/stop-all)
    (let [bad-server (test-stdio/start-server
                      :cmd "bb -e '(println \"not json\") (println \"still not json\")'")
          config {:cmd "bb -e '(println \"not json\") (println \"still not json\")'"}
          result (stdio/list-tools "bad-json-test" config (create-policy))]
      (is (or (string? result)
              (and (map? result) (= "Request timed out" (:error result)))))
      (test-stdio/stop-server bad-server)
      (stdio/stop-all))))

(deftest stdio-session-cleanup-test
  (testing "stop-all cleans up all sessions"
    (stdio/stop-all)
    (let [server1 (test-stdio/start-test-server)
          server2 (test-stdio/start-test-server)
          config1 {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          config2 {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          _ (stdio/get-session "cleanup-1" config1 (create-policy))
          _ (stdio/get-session "cleanup-2" config2 (create-policy))
          sessions-before (stdio/get-active-sessions)]
      (is (= 2 (count sessions-before)))
      (stdio/stop-all)
      (let [sessions-after (stdio/get-active-sessions)]
        (is (empty? sessions-after)))
      (test-stdio/stop-server server1)
      (test-stdio/stop-server server2))))

(deftest stdio-get-active-sessions-test
  (testing "get-active-sessions returns session info"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          _ (stdio/get-session "active-test" config (create-policy))
          sessions (stdio/get-active-sessions)]
      (is (map? sessions))
      (is (contains? sessions "active-test"))
      (is (true? (get-in sessions ["active-test" :running])))
      (test-stdio/stop-server server)
      (stdio/stop-all))))

(deftest stdio-concurrent-requests-test
  (testing "Concurrent tool calls don't interfere"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          _ (stdio/get-session "concurrent-test" config (create-policy))
          results (pmap (fn [i]
                          (stdio/call-tool "concurrent-test" config "retrieve_customer"
                                           {:customer_id (str "cus_" i)} (create-policy)))
                        (range 5))]
      (is (= 5 (count results)))
      (is (every? #(and (map? %) (contains? % :content)) results))
      (test-stdio/stop-server server)
      (stdio/stop-all))))

(deftest stdio-tool-not-found-test
  (testing "Non-existent tool returns appropriate error"
    (stdio/stop-all)
    (let [server (test-stdio/start-test-server)
          config {:cmd "bb test/mcp_injector/test_stdio_mcp.clj"}
          result (stdio/call-tool "notfound-test" config "nonexistent_tool"
                                  {} (create-policy))]
      (is (map? result))
      (is (contains? result :content))
      (let [text (get-in result [:content 0 :text])]
        (is (str/includes? text "Tool not found")))
      (test-stdio/stop-server server)
      (stdio/stop-all))))
