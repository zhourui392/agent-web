# Harness MCP Server Catalog

该目录只保存管理员审核的 MCP Server Manifest，不保存 Secret 明文。M3 默认没有启用任何
Server；部署环境必须同时配置 `agent.harness.security.allowed-mcp-server-ids`，并在每次
Capability Snapshot 请求中显式选择和授权，Server 才可能进入 Runtime。

`local-readonly-fixture/1.0.0` 只用于 M4 目标机真实 Codex 兼容性验收。它不读取文件、
不访问网络、不接收 Secret，只通过 stdio 暴露返回固定值的 `read_fixture`；Manifest 仅允许
`ANALYSIS + CODEX`。启用时仍必须显式配置：

```text
AGENT_HARNESS_ALLOWED_MCP_SERVER_IDS=local-readonly-fixture
```

其命令路径以被验收的 `agent-web` 源码根为工作目录；打包部署或外置 Catalog 不应依赖该
Fixture，应由管理员提供经过独立评审的绝对命令或 PATH 命令。不要把 Fixture 扩展为文件读取、
网络访问或写工具。
