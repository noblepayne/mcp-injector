how to handle 502? which might be context issue. how to make sure we communicate it back to openclaw? are we?

- last time I hand stopped the shim an then it seemd to send a "hey do a new session" and that worked, and I bounced the shm

- but... that's not ideal.

- [ ] Add robust configuration validation for mcp-servers.edn (e.g. ensure :url not :uri, check required fields)

- [ ] Add startup check to verify connectivity to all configured MCP servers

- [ ] Automatically refresh tool directory from MCP servers at startup or periodically (Phase 3)

- [ ] Support both :url and :uri in server config for better UX/compatibility




- [ ] look into errors string vs number on llm upstream issues? see prism logs


- [ ] errors on parsing like invalid json, just gives null back to curl?


- [ ] logging how initializing of mcp but not clear on which one...
