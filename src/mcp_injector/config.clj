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
   :timeout-ms 1800000})

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

(defn load-config []
  {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
   :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
   :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
   :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
   :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
   :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
   :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))})

(defn load-mcp-servers [config-path]
  (if-let [file (io/file config-path)]
    (if (.exists file)
      (keywordize-keys (edn/read-string (slurp file)))
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

(defn get-config
  "Unified config: env vars override config file, with defaults as fallback.
    Priority: env var > config file > default"
  [mcp-config]
  (let [env (load-config)
        file (:llm-gateway mcp-config)]
    {:port (:port env)
     :host (:host env)
     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
                  (:url file)
                  (:llm-url env))
     :mcp-config (:mcp-config env)
     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
                                 (:max-iterations file))]
                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
                    (:log-level file)
                    (:log-level env))
     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
                             (:timeout-ms file))]
                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
     :fallbacks (:fallbacks file)
     :virtual-models (:virtual-models file)}))

(defn get-llm-url
  "Get LLM URL: env var overrides config file"
  [mcp-config]
  (or (env-var "MCP_INJECTOR_LLM_URL")
      (get-in mcp-config [:llm-gateway :url])
      "http://localhost:8080"))
