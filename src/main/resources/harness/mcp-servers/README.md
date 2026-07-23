# Harness MCP Server Catalog

该目录只保存管理员审核的 MCP Server Manifest，不保存 Secret 明文。M3 默认没有启用任何
Server；部署环境必须同时配置 `agent.harness.security.allowed-mcp-server-ids`，并在每次
Capability Snapshot 请求中显式选择和授权，Server 才可能进入 Runtime。
