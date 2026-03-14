(ns mcp-injector.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.policy :as policy]
            [clojure.string :as str]))

(deftest allow-tool-test
  (testing "Nil policy defaults to Permissive (Resilient default)"
    ;; Direct nil is impossible from production config path; tests policy fn in isolation.
    (is (true? (:allowed? (policy/allow-tool? nil "mcp__stripe__list_charges" {})))))

  (testing "Permissive mode"
    (let [p {:mode :permissive}]
      (is (true? (:allowed? (policy/allow-tool? p "mcp__stripe__list_charges" {}))))))

  (testing "Strict mode"
    (let [p {:mode :strict}]
      (is (false? (:allowed? (policy/allow-tool? p "mcp__stripe__list_charges" {}))))
      (let [p {:mode :strict :allow ["mcp__stripe__*"]}]
        (is (true? (:allowed? (policy/allow-tool? p "mcp__stripe__list_charges" {})))))))

  (testing "Privileged tools ignore permissive mode"
    (let [p {:mode :permissive}]
      (is (false? (:allowed? (policy/allow-tool? p "clojure-eval" {}))))
      (is (str/includes? (:reason (policy/allow-tool? p "clojure-eval" {})) "requires explicit")))
    (let [p {:mode :permissive :allow ["clojure-eval"]}]
      (is (true? (:allowed? (policy/allow-tool? p "clojure-eval" {}))))))

  (testing "Deny always wins"
    (let [p {:mode :permissive
             :deny ["mcp__stripe__delete*"]}]
      (is (false? (:allowed? (policy/allow-tool? p "mcp__stripe__delete_customer" {}))))))

  (testing "Wildcard '*' does NOT allow privileged tools"
    (let [p {:mode :strict :allow ["*"]}]
      (is (true? (:allowed? (policy/allow-tool? p "mcp__any__tool" {}))))
      (is (false? (:allowed? (policy/allow-tool? p "clojure-eval" {}))))
      (is (str/includes? (:reason (policy/allow-tool? p "clojure-eval" {})) "explicit (literal)"))))

  (testing "Model rules can allow privileged tools explicitly"
    (let [p {:rules [{:model "gpt-4o" :allow ["clojure-eval"]}]}]
      (is (true? (:allowed? (policy/allow-tool? p "clojure-eval" {:model "gpt-4o"}))))
      (is (false? (:allowed? (policy/allow-tool? p "clojure-eval" {:model "gpt-4o-mini"}))))))

  (testing "Global deny beats model allow"
    (let [p {:deny ["clojure-eval"]
             :rules [{:model "gpt-4o" :allow ["clojure-eval"]}]}]
      (is (false? (:allowed? (policy/allow-tool? p "clojure-eval" {:model "gpt-4o"}))))
      (is (str/includes? (:reason (policy/allow-tool? p "clojure-eval" {:model "gpt-4o"})) "global policy")))))

(deftest sampling-policy-test
  (testing "Sampling whitelist"
    (let [p {:sampling {:trusted-servers ["stripe" "postgres"]}}]
      (is (true? (policy/allow-sampling? p "stripe")))
      (is (true? (policy/allow-sampling? p :postgres)))
      (is (false? (policy/allow-sampling? p "malicious-server"))))))
