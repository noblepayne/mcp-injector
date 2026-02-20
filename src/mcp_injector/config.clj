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
   :log-level "info"
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

(defn build-tool-directory [mcp-config]
  (for [[server-name server-config] (:servers mcp-config)
        tool-name (:tools server-config)]
    {:name (str (name server-name) "." (name tool-name))
     :server (name server-name)}))

(defn inject-tools-into-messages [messages tool-directory]
  (let [tool-lines (map #(str "- " (:name %) " (server: " (:server %) ")")
                        tool-directory)
        directory-text (str "Available tools:\n" (str/join "\n" tool-lines))
        system-msg {:role "system" :content directory-text}]
    (cons system-msg messages)))

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
                  "http://localhost:8080")
     :mcp-config (:mcp-config env)
     :max-iterations (:max-iterations env)
     :log-level (:log-level env)
     :timeout-ms (:timeout-ms env)
     :fallbacks (:fallbacks file)
     :virtual-models (:virtual-models file)}))

(defn get-llm-url
  "Get LLM URL: env var overrides config file"
  [mcp-config]
  (or (env-var "MCP_INJECTOR_LLM_URL")
      (get-in mcp-config [:llm-gateway :url])
      "http://localhost:8080"))
