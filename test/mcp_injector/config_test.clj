(ns mcp-injector.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.config :as config]))

(deftest resolve-server-config-test
  (testing "Resolves environment variables with prefix/suffix"
    (with-redefs [config/get-env (fn [name]
                                   (case name
                                     "TEST_KEY" "secret-value"
                                     "PWD" "/tmp"
                                     nil))]
      (let [conf {:headers {"Authorization" {:env "TEST_KEY" :prefix "Bearer "}
                            "X-Static" "static"}
                  :env {"DEBUG" "true"
                        "PATH" {:env "PWD" :suffix "/bin"}}
                  :cwd {:env "PWD"}}
            resolved (config/resolve-server-config conf)]
        (is (= "Bearer secret-value" (get-in resolved [:headers "Authorization"])))
        (is (= "static" (get-in resolved [:headers "X-Static"])))
        (is (= "true" (get-in resolved [:env "DEBUG"])))
        (is (= "/tmp/bin" (get-in resolved [:env "PATH"])))
        (is (= "/tmp" (:cwd resolved))))))

  (testing "Handles missing environment variables gracefully"
    (with-redefs [config/get-env (fn [_] nil)]
      (let [conf {:headers {"Auth" {:env "MISSING"}}}
            resolved (config/resolve-server-config conf)]
        (is (nil? (get-in resolved [:headers "Auth"]))))))

  (testing "Handles nested maps without :env"
    (let [conf {:other {:nested "value"}}
          resolved (config/resolve-server-config conf)]
      (is (= conf resolved)))))

(deftest deep-merge-test
  (testing "Nested merge preserves defaults"
    (let [defaults {:audit {:enabled true :path "default.log"}}
          user {:audit {:path "custom.log"}}
          result (config/deep-merge defaults user)]
      (is (= true (get-in result [:audit :enabled])))
      (is (= "custom.log" (get-in result [:audit :path]))))))

(deftest resolve-governance-precedence-test
  (testing "Top-level governance wins over gateway"
    (let [mcp-config {:governance {:mode :strict}
                      :llm-gateway {:governance {:mode :permissive}}}
          env {}
          result (config/resolve-governance mcp-config env)]
      (is (= :strict (:mode result)))))

  (testing "Gateway governance used if top-level missing"
    (let [mcp-config {:llm-gateway {:governance {:mode :strict}}}
          env {}
          result (config/resolve-governance mcp-config env)]
      (is (= :strict (:mode result)))))

  (testing "Defaults used if nothing provided"
    (let [mcp-config {}
          env {}
          result (config/resolve-governance mcp-config env)]
      (is (= :permissive (:mode result)))
      (is (= true (get-in result [:audit :enabled]))))))
