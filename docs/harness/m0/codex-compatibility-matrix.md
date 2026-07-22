# Codex M0 兼容矩阵

> 验证日期：2026-07-22
> 本机版本：`codex-cli 0.145.0`
> 结论：MVP 可用，版本升级必须重跑 M0 契约测试

## 1. 来源

官方资料：

- [Codex Advanced Configuration](https://learn.chatgpt.com/docs/config-file/config-advanced)
- [Codex Build Skills](https://learn.chatgpt.com/docs/build-skills)
- [Codex MCP](https://learn.chatgpt.com/docs/extend/mcp)
- [Codex Non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode)

本机事实：

- `codex --version`；
- `codex --help`、`codex exec --help`、`codex mcp --help`；
- `codex debug prompt-input`；
- [`self-test.sh`](../../../scripts/harness-m0/self-test.sh) 黑盒测试。

官方资料用于确定公开配置语义，本机黑盒结果用于确定 0.145.0 的实际参数位置、事件和退出行为。两者冲突时，MVP 适配器以已验证的本机行为为准，并记录差异。

## 2. 能力矩阵

| 能力 | 官方/CLI 声明 | 0.145.0 实测 | MVP 决策 |
| --- | --- | --- | --- |
| 非交互执行 | `codex exec` | PASS | 使用 |
| JSONL | `--json` | PASS，含 thread/turn/MCP item | Runtime 主事件源 |
| 最终输出文件 | `--output-last-message` | PASS | 保存结构化 Artifact |
| JSON Schema | `--output-schema` | PASS | 阶段输出合同 |
| 临时会话 | `--ephemeral` | PASS | 每个 Attempt 默认启用 |
| 忽略用户配置 | `--ignore-user-config` | PASS | 仍需处理项目配置 |
| 忽略 Exec Policy | `--ignore-rules` | PASS | Harness 使用自己的命令策略 |
| 工作目录 | `-C`/`--cd` | PASS | 使用白名单真实路径 |
| Sandbox | `--sandbox read-only/workspace-write` | PASS | 按 Stage 设置 |
| Approval | `--ask-for-approval` | PASS，但必须在 `exec` 前 | Adapter 固定参数顺序 |
| 自定义 Model Provider | `model_providers.*` | PASS，本地 Responses Provider | 默认测试不依赖外网 |
| Skill 自动发现 | `.agents/skills/*/SKILL.md` | PASS | 进入 Skill Catalog |
| Skill 显式调用 | `$skill-name` | PASS，完整 Skill 进入模型输入 | Stage Prompt 显式指定 |
| Skill 禁用 | `skills.config` | PASS | 禁用未选 Repo Skill |
| MCP STDIO | `mcp_servers.*.command` | PASS | MVP 默认路径 |
| MCP 临时注册 | `codex mcp add/list/get/remove` | PASS，隔离 CODEX_HOME | 仅用于管理/诊断 |
| Tool allowlist | `enabled_tools` | PASS | 必须设置 |
| Tool denylist | `disabled_tools` | PASS | 与 allowlist 同时设置 |
| MCP 必需 | `required=true` | PASS，初始化失败阻断 thread | 必需能力 fail-closed |
| MCP 启动超时 | `startup_timeout_sec` | 配置解析 PASS | 每 Server 显式设置 |
| MCP Tool 超时 | `tool_timeout_sec` | PASS，JSONL status=failed | 可观察并分类 |
| MCP Tool Approval | `default_tools_approval_mode` | 配置解析 PASS | MVP 只读 Tool 使用 `writes` |
| 进程取消 | OS 进程信号 | PASS，进程树停止 | 使用进程组 |
| 取消退出码 | 未保证 | 实测可能为 0 | 禁止用退出码判成功 |
| 取消终态事件 | 未保证 | 实测无 turn terminal event | 使用取消意图补终态 |
| 在线 OpenAI Provider | 保存的本机 API Key | FAIL，HTTP 401 | M4 前修复，不阻塞 M1—M3 |

## 3. 配置隔离结论

### 3.1 可以隔离的内容

- 临时 `HOME` 隔离用户级 `.agents/skills`。
- 临时 `CODEX_HOME` 隔离 `config.toml`、Session、日志和注册表。
- `--ignore-user-config` 避免基线配置参与本次 Run。
- `--ephemeral` 避免保存 Session Rollout。
- CLI `-c` 覆盖可以只挂载 Snapshot 中的 MCP，并禁用未选择 Skill。

### 3.2 不能直接假定隔离的内容

- 官方文档明确说明可信项目可以加载 `.codex/config.toml`。
- `--ignore-user-config` 名称和说明只覆盖 `$CODEX_HOME/config.toml`。
- `--ignore-rules` 只跳过 Exec Policy `.rules`，不是“忽略所有项目配置”。
- Codex 仍会原生读取适用的 `AGENTS.md` 和 Repo Skills。

因此 MVP Preflight 必须：

1. 扫描工作目录到 Repo Root 的 `.codex/config.toml`；存在时默认阻断。
2. 扫描 Repo Skills；未注册 Skill 阻断，已注册但未选择 Skill 显式禁用。
3. 保存文件清单和 Package Hash 后才能生成 Capability Snapshot。
4. Snapshot 形成后检测配置/Skill 文件变化；变化则使 Attempt 失效。

## 4. JSONL 事件合同

MVP 适配器至少识别：

```text
thread.started
turn.started
item.started(type=mcp_tool_call)
item.completed(type=mcp_tool_call, status=completed|failed)
item.completed(type=agent_message)
turn.completed
turn.failed
error
```

事件处理规则：

- `thread.started.thread_id` 保存为 Runtime Execution ID 的组成部分。
- MCP 超时以 `item.completed.status=failed` 和 `error.message` 分类。
- `turn.completed` 不是唯一成功条件；还要验证 Output Schema 和 Stage Gate。
- 收到取消意图后，即使进程退出 0，也不能标记成功。
- 进程结束且没有 Turn 终态时，如果没有取消意图，则进入可诊断失败态。

## 5. 版本升级门禁

升级 Codex CLI 后必须重新运行：

```bash
bash scripts/harness-m0/self-test.sh all
```

并人工核对：

- `--ask-for-approval` 参数位置；
- JSONL Event Type 和字段；
- Skill 全量注入与禁用；
- MCP Namespace Tool Call；
- `required`、Tool allow/deny 和 timeout；
- 取消退出码和终态事件；
- 临时目录清理。

未通过时，Harness Runtime 必须拒绝该版本或保持在已验证版本。
