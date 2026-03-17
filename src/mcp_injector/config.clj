(ns mcp-injector.config
  "Configuration and environment variables for mcp-injector."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(def default-config
  {:port 8088
   :host "127.0.0.1"
   :llm-url "http://localhost:8080"
   :mcp-config "./mcp-servers.edn"
   :max-iterations 10
   :log-level "debug"
   :timeout-ms 1800000
   :eval-timeout-ms 5000
   :audit-log-path "logs/audit.log.ndjson"
   :audit-secret "default-audit-secret"})

(defn env-var
  ([name] (System/getenv name))
  ([name default] (or (System/getenv name) default)))

(defn- parse-int [s default]
  (try
    (Integer/parseInt s)
    (catch Exception _ default)))

(defn- keywordize-keys [m]
  (walk/prewalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v]] [(keyword k) v]) x))
       x))
   m))

(defn deep-merge
  "Recursively merges maps. If keys conflict, the value from the last map wins.
   Ensures nested defaults are not wiped out by partial user config.
   If 'new' is nil, the 'old' value is preserved to prevent wiping out defaults."
  [& maps]
  (apply merge-with
         (fn [old new]
           (cond
             (nil? new) old
             (and (map? old) (map? new)) (deep-merge old new)
             :else new))
         maps))

(defn- resolve-audit-path [env-path]
  (let [logs-dir (env-var "LOGS_DIRECTORY")
        state-dir (env-var "STATE_DIRECTORY")
        xdg-state (env-var "XDG_STATE_HOME")
        xdg-data (env-var "XDG_DATA_HOME")
        home (env-var "HOME")
        cwd (.getAbsolutePath (io/file "."))
        in-nix-store? (str/starts-with? cwd "/nix/store")
        default-path (:audit-log-path default-config)]
    (or env-path
        (cond
          logs-dir (str (str/replace logs-dir #"/$" "") "/audit.log.ndjson")
          state-dir (str (str/replace state-dir #"/$" "") "/audit.log.ndjson")
          xdg-state (str (str/replace xdg-state #"/$" "") "/mcp-injector/audit.log.ndjson")
          xdg-data (str (str/replace xdg-data #"/$" "") "/mcp-injector/audit.log.ndjson")
          home (str home "/.local/state/mcp-injector/audit.log.ndjson")
          (and in-nix-store? (not (str/starts-with? default-path "/")))
          (throw (ex-info (str "Cannot use relative audit log path '" default-path "' in read-only directory: " cwd)
                          {:cwd cwd
                           :default-path default-path
                           :suggestion "Set MCP_INJECTOR_AUDIT_LOG_PATH to an absolute, writable path."}))
          :else default-path))))

(defn load-config []
  (let [env-audit-path (env-var "MCP_INJECTOR_AUDIT_LOG_PATH")
        env-audit-secret (env-var "MCP_INJECTOR_AUDIT_SECRET")]
    {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
     :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
     :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
     :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
     :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
     :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
     :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))
     :audit-log-path (resolve-audit-path env-audit-path)
     :audit-secret (or env-audit-secret (:audit-secret default-config))}))

(defn get-env [name]
  (System/getenv name))

(defn- resolve-value
  "Resolve a potentially dynamic value.
   If value is a map with :env, look up environment variable.
   Supports :prefix and :suffix."
  [v]
  (if (and (map? v) (:env v))
    (let [env-name (:env v)]
      (if (or (string? env-name) (keyword? env-name))
        (let [prefix (:prefix v "")
              suffix (:suffix v "")
              env-val (get-env (if (keyword? env-name) (name env-name) env-name))]
          (if env-val
            (str prefix env-val suffix)
            (do
              (println (str "Warning: Environment variable " env-name " not set."))
              nil)))
        v))
    v))

(defn resolve-server-config
  "Recursively resolve dynamic values in a server configuration map.
   Uses post-order traversal: children first, then parent."
  [m]
  (let [resolve-all (fn resolve-all [x]
                      (cond
                        (map? x)
                        (let [resolved (into {} (map (fn [[k v]] [k (resolve-all v)]) x))]
                          (if (contains? resolved :env)
                            (resolve-value resolved)
                            resolved))

                        (vector? x)
                        (mapv resolve-all x)

                        :else x))]
    (resolve-all m)))

(defn load-mcp-servers [config-path]
  (if-let [file (io/file config-path)]
    (if (.exists file)
      (let [raw-config (keywordize-keys (edn/read-string (slurp file)))]
        (update raw-config :servers
                (fn [servers]
                  (into {} (map (fn [[k v]] [k (resolve-server-config v)]) servers)))))
      {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}})
    {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}}))

(defn get-llm-fallbacks
  "Get LLM fallback configuration from MCP servers config.
   Transforms from [{:provider :model}] format to provider/model strings"
  [mcp-config]
  (let [fallbacks-config (get-in mcp-config [:llm-gateway :fallbacks] [])]
    (mapv (fn [fb]
            (if (string? fb)
              fb
              (str (:provider fb) "/" (:model fb))))
          fallbacks-config)))

(defn build-tool-directory
  "Build tool directory from mcp-config. 
   If pre-discovered-tools map provided, use those; otherwise fall back to config :tools list."
  ([mcp-config]
   (build-tool-directory mcp-config nil))
  ([mcp-config pre-discovered-tools]
   (reduce
    (fn [acc [server-name server-config]]
      (let [server-url (or (:url server-config) (:uri server-config))
            cmd (:cmd server-config)
            tool-names (:tools server-config)]
        (if (or server-url cmd)
          (let [tools (if (and pre-discovered-tools (get pre-discovered-tools server-name))
                        (get pre-discovered-tools server-name)
                        (map (fn [t] {:name (name t)}) tool-names))]
            (into acc (map (fn [tool]
                             {:name (str (name server-name) "." (:name tool))
                              :server (name server-name)})
                           tools)))
          acc)))
    []
    (:servers mcp-config))))

(defn get-server-trust
  "Get trust level for a server/tool combination.
   Returns :restore (full restoration), :none (untrusted), or :block.
   Precedence: tool-level :trust > server-level :trust > :none.
   Accepts trust values as either keywords (:restore) or strings (\"restore\")."
  [mcp-config server-name tool-name]
  (let [servers (or (:servers mcp-config) mcp-config)
        server (get servers (keyword server-name))]
    (if-not server
      :none
      (let [server-trust (some-> server :trust keyword)
            tool-configs (:tools server)
            tool-config (cond
                          (map? tool-configs)
                          (get tool-configs (keyword tool-name))

                          (sequential? tool-configs)
                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)

                          :else nil)
            tool-trust (some-> tool-config :trust keyword)]
        (cond
          (= tool-trust :block) :block
          (= server-trust :block) :block
          (= tool-trust :restore) :restore
          (= server-trust :restore) :restore
          :else :none)))))

;; No-op placeholder that gets replaced

(defn get-passthrough-trust
  "Get trust level for a non-prefixed (passthrough) tool.
   Returns :restore, :none, or :block.
   Precedence: :restore-all shortcut > exact match > wildcard '*' > :none.
   Trust values can be strings or keywords."
  [governance tool-name]
  (let [trust-map (:passthrough-trust governance {})
        tool-str (if (keyword? tool-name) (name tool-name) (str tool-name))]
    (cond
      (or (= trust-map :restore-all) (= trust-map "restore-all") (true? trust-map))
      :restore

      :else
      (let [trust (or (get trust-map tool-str)
                      (get trust-map (keyword tool-str))
                      (get trust-map "*")
                      :none)]
        (keyword trust)))))

(defn get-meta-tool-definitions
  "Get definitions for meta-tools like get_tool_schema and native tools"
  []
  [{:type "function"
    :function {:name "get_tool_schema"
               :description "Fetch the full JSON schema for a specific MCP tool to understand its parameters."
               :parameters {:type "object"
                            :properties {:tool {:type "string"
                                                :description "Full tool name with mcp__ prefix (e.g., 'mcp__stripe__retrieve_customer')"}}
                            :required ["tool"]}}}
   {:type "function"
    :function {:name "clojure-eval"
               :description "Evaluate Clojure code in the local REPL. WARNING: Full Clojure access - use with care. Returns the result as a string."
               :parameters {:type "object"
                            :properties {:code {:type "string"
                                                :description "Clojure code to evaluate"}}
                            :required ["code"]}}}])

(defn- extract-tool-params
  "Extract parameter names from tool schema, distinguishing required vs optional.
   Returns [required-params optional-params] as vectors of strings."
  [tool]
  (let [schema (or (:inputSchema tool) (:schema tool))
        properties (get schema :properties {})
        required-vals (get schema :required [])
        required-set (set (map keyword required-vals))
        all-param-names (keys properties)
        required (filterv #(required-set %) all-param-names)
        optional (filterv #(not (required-set %)) all-param-names)]
    [(mapv name required) (mapv name optional)]))

(defn- format-tool-with-params
  "Format a tool as mcp__server__tool [required, optional?]"
  [server-name tool]
  (let [tool-name (:name tool)
        [required optional] (extract-tool-params tool)]
    (if (or (seq required) (seq optional))
      (let [all-params (into required (map #(str % "?")) optional)]
        (str "mcp__" (name server-name) "__" tool-name " [" (str/join ", " all-params) "]"))
      (str "mcp__" (name server-name) "__" tool-name))))

(defn inject-tools-into-messages
  "Inject MCP tools directory into messages.
   If pre-discovered-tools map provided (server-name -> [tools]), use those;
   otherwise fall back to config :tools list."
  ([messages mcp-config]
   (inject-tools-into-messages messages mcp-config nil))
  ([messages mcp-config pre-discovered-tools]
   (let [servers (:servers mcp-config)
         tool-lines (reduce
                     (fn [lines [server-name server-config]]
                       (let [server-url (or (:url server-config) (:uri server-config))
                             cmd (:cmd server-config)
                             tool-names (:tools server-config)]
                         (if (or server-url cmd)
                           (let [discovered (get pre-discovered-tools server-name)
                                 tools (if (and pre-discovered-tools (seq discovered))
                                         discovered
                                         (mapv (fn [t] {:name (name t)}) tool-names))
                                 tools (filter #(some? (:name %)) tools)
                                 formatted (map #(format-tool-with-params server-name %) tools)
                                 tool-str (str/join ", " formatted)]
                             (if (seq tools)
                               (conj lines (str "- mcp__" (name server-name) ": " tool-str))
                               lines))
                           lines)))
                     []
                     servers)
         directory-text (str "## Remote Tools (MCP)\n"
                             "You have access to namespaced MCP tools.\n\n"
                             "### Available:\n"
                             (str/join "\n" tool-lines)
                             "\n\n### Usage:\n"
                             "Get schema: get_tool_schema {:tool \"mcp__server__tool\"}\n"
                             "Call tool: mcp__server__tool {:key \"value\"}\n\n"
                             "### Native:\n"
                             "- clojure-eval: Evaluate Clojure. Args: {:code \"...\"}\n"
                             "  Example: {:code \"(vec (range 5))\"} => \"[0 1 2 3 4]\"")
         system-msg {:role "system" :content directory-text}]
     (cons system-msg messages))))

(defn get-virtual-models
  "Get virtual models configuration from MCP servers config"
  [mcp-config]
  (get-in mcp-config [:llm-gateway :virtual-models] {}))

(defn resolve-governance
  "Unified governance resolution logic. Prioritizes nested :governance block.
   Precedence: top-level :governance > :llm-gateway :governance > defaults.
   Uses deep-merge to preserve nested default settings."
  [mcp-config env-config]
  (let [gateway (:llm-gateway mcp-config)
        gov-user (or (:governance mcp-config) (:governance gateway))
        defaults {:mode :permissive
                  :pii {:enabled true :mode :replace :trust :restore}
                  :audit {:enabled true :path (:audit-log-path env-config)}
                  :policy {:mode :permissive}
                  :passthrough-trust :restore-all}]
    (deep-merge defaults gov-user)))

(defn extract-governance
  "Extract governance config from various possible locations in the config map.
   This handles the 'spread' config pattern where Nix/EDN may place governance
   at different levels depending on how the config is structured.
   
   Precedence: 
   1. Top-level :governance
   2. :mcp-servers :governance  
   3. :base-mcp-servers :governance
   4. :llm-gateway :governance
   
   Returns {:config <governance-map> :source <keyword>} or nil if not found."
  [mcp-config]
  (or (when-let [gov (:governance mcp-config)]
        {:config gov :source :governance})
      (when-let [gov (:governance (:mcp-servers mcp-config))]
        {:config gov :source :mcp-servers})
      (when-let [gov (:governance (:base-mcp-servers mcp-config))]
        {:config gov :source :base-mcp-servers})
      (when-let [gov (:governance (:llm-gateway mcp-config))]
        {:config gov :source :llm-gateway})))

(defn get-config
  "Unified config: env vars override config file, with defaults as fallback.
    Priority: env var > config file > default"
  [mcp-config]
  (let [env (load-config)
        gateway (:llm-gateway mcp-config)
        gov (resolve-governance mcp-config env)]
    {:port (:port env)
     :host (:host env)
     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
                  (:url gateway)
                  (:llm-url env))
     :mcp-config (:mcp-config env)
     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
                                 (:max-iterations gateway))]
                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
                    (:log-level gateway)
                    (:log-level env))
     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
                             (:timeout-ms gateway))]
                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
     :eval-timeout-ms (let [v (or (env-var "MCP_INJECTOR_EVAL_TIMEOUT_MS")
                                  (:eval-timeout-ms gateway))]
                        (if (string? v) (parse-int v 5000) (or v (:eval-timeout-ms env) 5000)))
     :fallbacks (:fallbacks gateway)
     :virtual-models (:virtual-models gateway)
     :audit-log-path (get-in gov [:audit :path])
     :audit-secret (or (get-in gov [:audit :secret])
                       (env-var "MCP_INJECTOR_AUDIT_SECRET")
                       (:audit-secret env)
                       "default-audit-secret")
     :governance gov}))

(defn get-llm-url
  "Get LLM URL: env var overrides config file"
  [mcp-config]
  (or (env-var "MCP_INJECTOR_LLM_URL")
      (get-in mcp-config [:llm-gateway :url])
      "http://localhost:8080"))
