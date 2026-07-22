# Harness M0 自测报告

> 执行日期：2026-07-22
> 基线：`master@39ece6f41bc0c0cb1a298ed80b3c6da2441aa179`
> 标签：`harness-m0-baseline-20260722`
> Codex：`codex-cli 0.145.0`
> Node：`v24.14.0`

## 1. 最终命令

```bash
bash -n scripts/harness-m0/self-test.sh
node --check scripts/harness-m0/fake-mcp-server.mjs
node --check scripts/harness-m0/fake-responses-server.mjs
node --check scripts/harness-m0/verify-evidence.mjs
bash scripts/harness-m0/self-test.sh all
```

## 2. 最终结果

| # | 检查 | 结果 |
| --- | --- | --- |
| 1 | Codex/Node 版本采集 | PASS |
| 2 | 临时 CODEX_HOME 的 MCP 注册隔离 | PASS |
| 3 | MCP `enabled_tools`/`disabled_tools` | PASS |
| 4 | MCP 移除且不修改真实 Codex Home | PASS |
| 5 | Repo Skill 元数据发现 | PASS |
| 6 | 未选择 Repo Skill 单次运行禁用 | PASS |
| 7 | 本地确定性 Responses Provider | PASS |
| 8 | 显式 Skill + 只读 MCP + Output Schema | PASS |
| 9 | 必需 MCP 初始化失败关闭 | PASS |
| 10 | MCP Tool Timeout 事件 | PASS |
| 11 | 进程组取消 Codex 与 MCP 子进程 | PASS |

最终输出：

```text
M0 SELF-TEST PASS - mode=all checks=11 codex_home_untouched=/home/ubuntu/.codex
```

## 3. 自测边界

测试中真实执行：

- 本机 Codex CLI 二进制；
- Codex Prompt 构建和 Skill 扫描/展开；
- Codex STDIO MCP Client；
- MCP Tool namespace、allow/deny、timeout；
- Codex JSONL 和 Output Schema；
- Codex 进程及其 MCP 子进程；
- 临时 HOME/CODEX_HOME 创建与清理。

测试替代：

- 模型响应由本机 Fake Responses Provider 提供，保证 Tool Call 序列确定；
- MCP Server 为无第三方依赖的本机只读 Fixture；
- 不调用项目 Java、SQLite、外部 HTTP、真实工单或部署系统。

## 4. 关键观测

### 4.1 Skill 与 Tool 最小暴露

模型请求中包含完整 `m0-harness-skill` 指令和唯一允许的 `read_fixture` MCP Tool；`write_fixture` 和 `slow_read` 未暴露。Fake MCP 日志只出现一次 `read_fixture` Tool Call。

### 4.2 必需 MCP 失败关闭

无效 MCP 命令加 `required=true` 后，Codex 在创建 Thread 时失败，错误包含：

```text
required MCP servers failed to initialize: m0-broken
```

没有静默降级为无 MCP 运行。

### 4.3 Tool Timeout

`slow_read` 配置 `tool_timeout_sec=0.5`、Server 延迟 3 秒后，JSONL 产生：

```text
item.completed
type=mcp_tool_call
status=failed
timed out awaiting tools/call after 500ms
```

随后 Turn 可以生成受控的超时说明并结束。

### 4.4 Cancellation

`slow_read` 进入 `in_progress` 后，对 Codex 进程组发送 `SIGTERM`：

- Codex 进程结束；
- MCP stdin 关闭，MCP 子进程结束；
- JSONL 停在进行中的 MCP Item，没有 Turn 终态；
- Codex 退出码为 0。

因此 Runtime 必须持久化取消意图，不能用退出码 0 或缺失 `turn.failed` 推导成功。

### 4.5 参数位置差异

本机 `codex exec --help` 展示 Approval Option，但实际执行要求：

```text
codex --ask-for-approval never exec ...
```

如果放在 `exec` 后会退出 2。Runtime 契约测试已固定正确顺序。

## 5. 在线 Provider 诊断

使用本机保存的 Codex API Key 做过一次最小真实 Provider Smoke 尝试，OpenAI Responses API 返回 HTTP 401。报告不保存 Key，只记录结论：当前在线凭据不可用于 M4 真实需求验收。

处理策略：

- M1—M3 使用确定性本地 Provider 和 Stub 继续开发；
- M4 真实需求开始前重新登录或替换受控凭据；
- 修复后运行单独标记为 `live` 的最小真实 Provider Smoke；
- 普通快速测试永远不依赖在线凭据。

## 6. 临时文件与外部影响

- 自测默认清理 `/tmp/agent-web-harness-m0.*` 或 `/var/tmp/agent-web-harness-m0.*`。
- 静态和本地 Provider 测试使用隔离 HOME/CODEX_HOME。
- 未修改 `/home/ubuntu/.codex` 配置、Skill 或 MCP 注册。
- 未修改项目数据库。
- 未运行 Maven Package、服务重启或部署。
- 未推送 Git 标签或代码到远端。
