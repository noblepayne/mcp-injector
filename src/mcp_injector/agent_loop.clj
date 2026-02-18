(ns mcp-injector.agent-loop
  "Agent execution loop for mcp-injector."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [mcp-injector.mcp-client :as mcp]))

(defn- parse-tool-call
  "Parse a tool call from LLM response"
  [tool-call-data]
  {:id (:id tool-call-data)
   :name (get-in tool-call-data [:function :name])
   :arguments (json/parse-string (get-in tool-call-data [:function :arguments]) true)})

(defn- execute-tool-call
  "Execute a single tool call against MCP servers"
  [tool-call mcp-servers]
  (let [full-name (:name tool-call)
        args (:arguments tool-call)
        [server-name tool-name] (str/split full-name #"\." 2)]
    (if (= full-name "get_tool_schema")
      ;; Meta-tool: get tool schema
      (let [target-server (:mcp_name args)
            target-tool (:tool_name args)
            server-url (get-in mcp-servers [:servers (keyword target-server) :url])]
        (if server-url
          (mcp/get-tool-schema server-url target-tool)
          {:error (str "Server not found: " target-server)}))
      ;; Regular tool call
      (let [server-url (get-in mcp-servers [:servers (keyword server-name) :url])]
        (if server-url
          (mcp/call-tool server-url tool-name args)
          {:error (str "Server not found: " server-name)})))))

(defn- build-tool-result-message
  "Build tool result message for LLM"
  [tool-call-id result]
  {:role "tool"
   :tool_call_id tool-call-id
   :content (json/generate-string result)})

(defn- call-llm
  "Call LLM via LLM (placeholder - will be implemented in core)"
  [llm-url _messages _model]
  ;; This will be replaced with actual HTTP call in core
  (throw (ex-info "call-llm not implemented" {:llm-url llm-url})))

(defn execute-loop
  "Main agent execution loop
   
   Arguments:
   - llm-url: URL of LLM gateway
   - messages: Initial messages (with tool directory injected)
   - model: Model name
   - mcp-servers: MCP server configuration
   - max-iterations: Maximum number of iterations
   
   Returns:
   Map with :content and optionally :tool-calls"
  [llm-url messages model mcp-servers max-iterations]
  (loop [msgs messages
         iteration 0]
    (if (>= iteration max-iterations)
      {:content "Error: Maximum iterations exceeded"
       :tool-calls nil}
      (let [;; Call LLM (will be implemented to actually call LLM)
            llm-response (call-llm llm-url msgs model)
            tool-calls (get-in llm-response [:choices 0 :message :tool_calls])
            content (get-in llm-response [:choices 0 :message :content])]
        (if (empty? tool-calls)
          ;; No tool calls, return result
          {:content content
           :tool-calls nil}
          ;; Execute tool calls and continue loop
          (let [parsed-calls (map parse-tool-call tool-calls)
                results (map #(execute-tool-call % mcp-servers) parsed-calls)
                tool-messages (map #(build-tool-result-message (:id %1) %2)
                                   parsed-calls
                                   results)
                assistant-message {:role "assistant"
                                   :content content
                                   :tool_calls tool-calls}
                new-msgs (concat msgs [assistant-message] tool-messages)]
            (recur new-msgs (inc iteration))))))))
