(ns mcp-injector.policy
  "Policy engine for tool access control.
   Implements a 'Deny Wins' strategy with support for privileged tools and model context."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cheshire.core :as json]))

(def privileged-tools
  "Set of tools that always require explicit allow-rules regardless of mode.
   These tools are blocked by default unless they appear in an :allow list by their literal name."
  #{"clojure-eval"})

(defn- match? [pattern tool-name]
  (cond
    (nil? pattern) false
    (= pattern tool-name) true
    (= pattern "*") true
    (str/ends-with? pattern "*") (str/starts-with? tool-name (subs pattern 0 (dec (count pattern))))
    :else false))

(defn- check-rules [rules tool-name context]
  (let [matching-rules (filter (fn [r]
                                 (if-let [m (:model r)]
                                   (and (= m (:model context))
                                        (some #(match? % tool-name) (concat (:allow r) (:deny r))))
                                   false))
                               rules)]
    (if (seq matching-rules)
      (let [any-deny? (some (fn [r] (some #(match? % tool-name) (:deny r))) matching-rules)
            any-allow? (some (fn [r] (some #(match? % tool-name) (:allow r))) matching-rules)]
        (cond
          any-deny? {:allowed? false :reason "Explicitly denied by model-specific rule"}
          any-allow? {:allowed? true}
          :else nil))
      nil)))

(defn allow-tool?
  "Checks if a tool is allowed based on the policy and context.
   
   Logic Flow:
   1. If policy is nil, default to :permissive (Fail Open for resilience).
   2. Global :deny check (Deny wins).
   3. Model-specific rules check (Model Deny wins over Model Allow).
      Note: Rules only match if tool matches one of their :allow or :deny patterns.
      To express 'default deny for model X', you must enumerate all denied tools.
   4. Privileged Guard: High-risk tools must be explicitly allowed by literal name.
   5. Global :allow check.
   6. Fallback to mode default (:permissive allows, :strict denies)."
  [policy tool-name context]
  (let [policy (or policy {:mode :permissive})
        mode (get policy :mode :permissive)
        privileged? (contains? privileged-tools tool-name)

        ;; 1. Check global denial
        global-denied? (some #(match? % tool-name) (:deny policy))

        ;; 2. Check model rules
        model-result (check-rules (:rules policy) tool-name context)
        model-denied? (and (some? model-result) (false? (:allowed? model-result)))
        model-allowed? (and (some? model-result) (true? (:allowed? model-result)))

        ;; 3. Check global allow
        global-allowed? (some #(match? % tool-name) (:allow policy))

        ;; 4. Check for explicit (non-wildcard) allowance for privileged tools
        explicitly-allowed-fn (fn [coll] (some #(= % tool-name) coll))
        privileged-allowed? (or (when model-allowed?
                                  (some (fn [r]
                                          (and (= (:model r) (:model context))
                                               (explicitly-allowed-fn (:allow r))))
                                        (:rules policy)))
                                (explicitly-allowed-fn (:allow policy)))]

    (cond
      global-denied? {:allowed? false :reason "Explicitly denied by global policy"}

      model-denied? model-result

      ;; Privileged tools require LITERAL name in an allow list. '*' is not enough.
      (and privileged? (not privileged-allowed?))
      {:allowed? false :reason (str "Privileged tool '" tool-name "' requires explicit (literal) allow-rule")}

      (or model-allowed? global-allowed?)
      {:allowed? true}

      (= mode :permissive) {:allowed? true}

      :else {:allowed? false :reason (str "No matching allow rule in " (name mode) " mode")})))

(defn validate-policy! [policy]
  (if (nil? policy)
    (println (json/generate-string
              {:level "info" :message "No security policy configured. Running in default-deny mode."}))
    (let [known-keys #{:mode :allow :deny :rules :sampling}
          unknown (set/difference (set (keys policy)) known-keys)
          rule-keys #{:model :allow :deny}]
      (when (seq unknown)
        (println (json/generate-string
                  {:level "warn" :message "Unknown top-level policy keys (possible typos)" :keys unknown})))
      (doseq [r (:rules policy)]
        (let [unknown-rule (set/difference (set (keys r)) rule-keys)]
          (when (seq unknown-rule)
            (println (json/generate-string
                      {:level "warn" :message "Unknown rule keys (possible typos)" :keys unknown-rule :rule r}))))))))

(defn allow-sampling?
  "Checks if an MCP server is trusted to perform sampling (calling back to LLM)."
  [policy server-id]
  (if (nil? policy)
    false
    (let [trusted (get-in policy [:sampling :trusted-servers] [])]
      (boolean (some #(= (name server-id) (name %)) trusted)))))
