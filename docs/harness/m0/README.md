# Harness M0 完成记录

> 状态：Completed，允许进入 M1
> 完成日期：2026-07-22
> 基线分支：`master`
> 基线提交：`39ece6f41bc0c0cb1a298ed80b3c6da2441aa179`
> 基线标签：`harness-m0-baseline-20260722`

## 1. 结论

M0 已完成 Codex、Skill、MCP、隔离配置、结构化输出、失败关闭、工具超时和进程取消的技术验证。Harness 可以进入 M1 领域内核开发。

M0 使用真实 `codex-cli 0.145.0`，模型侧使用本机确定性 Fake Responses Provider，避免依赖外部模型随机性和失效凭据。Codex 的 Prompt 构造、Skill 展开、MCP 初始化/Tool Call、JSONL、Sandbox、超时和取消均为真实 CLI 行为。

当前本机 Codex 保存的 API Key 在真实 OpenAI 请求中返回 401。这个问题不阻塞 M1—M3 的领域和适配器开发，但会阻塞 M4 的真实需求验收，必须在 M4 开始真实需求前修复。

## 2. M0 交付物

| 交付物 | 位置 | 状态 |
| --- | --- | --- |
| 目标架构 | [`01-target-harness-architecture.md`](../01-target-harness-architecture.md) | 已更新 |
| MVP 能力边界 | [`02-mvp-capabilities.md`](../02-mvp-capabilities.md) | 已确认 |
| 里程碑 | [`03-milestones.md`](../03-milestones.md) | M0 已关闭 |
| Codex 兼容矩阵 | [`codex-compatibility-matrix.md`](codex-compatibility-matrix.md) | 已完成 |
| Stage Contract | [`stage-contracts.json`](contracts/stage-contracts.json) | `1.0.0` 初稿 |
| Artifact Schema | [`artifact.schema.json`](contracts/artifact.schema.json) | `1.0.0` 初稿 |
| Codex Runtime ADR | [`ADR-001`](adr/ADR-001-codex-runtime.md) | Accepted |
| Artifact 存储 ADR | [`ADR-002`](adr/ADR-002-artifact-storage.md) | Accepted |
| MCP/Skill 安全 ADR | [`ADR-003`](adr/ADR-003-mcp-skill-security.md) | Accepted |
| Fake MCP | [`fake-mcp-server.mjs`](../../../scripts/harness-m0/fake-mcp-server.mjs) | 可重复 |
| Fake Responses Provider | [`fake-responses-server.mjs`](../../../scripts/harness-m0/fake-responses-server.mjs) | 可重复 |
| Skill/Schema Fixture | [`fixtures`](../../../scripts/harness-m0/fixtures) | 可重复 |
| M0 自测 | [`self-test.sh`](../../../scripts/harness-m0/self-test.sh) | 11 项通过 |
| 测试报告 | [`test-report.md`](test-report.md) | 已完成 |

## 3. 已冻结的首版合同

### 3.1 Runtime

- MVP 只支持 Codex Runtime。
- 已验证版本为 `codex-cli 0.145.0`。
- 使用 `codex exec --json` 和 `--ephemeral`。
- 使用独立 HOME/CODEX_HOME 和 CLI 覆盖构建每次 Attempt 的配置。
- 不复用用户 Skill/MCP/Hook 配置。
- 不使用 `--dangerously-bypass-approvals-and-sandbox`。
- Harness 使用进程组取消，并持久化取消意图。

### 3.2 Prompt 与 Skill

- Prompt Pack 由 Harness 管理并计算 Hash。
- Codex 原生 Skill 目录和渐进加载机制继续使用。
- Stage 只允许 Snapshot 中的 Skill；其他 Repo Skill 显式禁用。
- 工作区未注册 Skill 默认阻断。
- `AGENTS.md` 继续由 Codex 原生加载，Harness 不重复拼接。

### 3.3 MCP

- CLI Native MCP，不在 Java 进程内实现 MCP Client。
- Server 预注册，按 Stage 动态挂载。
- MVP 只读；Tool 同时设置 allowlist 和 denylist。
- 必需 Server `required=true`，初始化失败阻断。
- 配置启动/调用超时。
- `.codex/config.toml` 在 MVP 中默认触发 Preflight 阻断。

### 3.4 Artifact 与 Gate

- SQLite 保存控制面元数据。
- 受控 Artifact Root 保存正文与大型证据。
- Artifact 版本不可覆盖，Approval 绑定版本与 SHA-256。
- Gate 使用机器可读 Artifact 和确定性证据；模型不作为唯一 Gate。

### 3.5 部署

- MVP 只允许本机 `local`。
- 部署命令来自管理员注册模板，不接受 Agent 返回任意 Shell。
- M4 才允许在独立 Approval 后调用现有 `scripts/service.sh` 能力。
- M0 不执行构建、重启或部署。

## 4. Feature Flag、权限与测试分组

### 4.1 Feature Flag

M1 引入 `agent.harness.enabled=false`。关闭时：

- Harness Bean/入口不对普通用户开放；
- 不注册 Harness Scheduler 或 Reconciliation Worker；
- 不创建 Runtime 执行；
- 现有 Chat、Workflow、Diagnose 行为不变。

Schema 迁移保持幂等，Feature Flag 关闭不要求回滚表结构。

### 4.2 首版角色

- ADMIN：创建 Run、开始 Stage、批准 Artifact、批准本机部署、取消和重试。
- 普通用户：默认无写权限；后续只读能力单独设计。
- Agent：执行主体，不是授权主体。

### 4.3 测试分组

- 默认单测：Domain/Application 使用 Stub，不启动真实 CLI/MCP。
- Infrastructure：`@TempDir` + Fake MCP/Fake Responses Provider，可进入默认快速集。
- 真实 Codex 进程契约：标记 `process-integration`，使用本地 Provider，不访问外部模型。
- 真实 OpenAI 模型或外部 MCP：标记 `live`，默认排除。
- 本机服务启停与部署验收：不进入默认 Maven 测试，由 M4 显式执行。

## 5. 与现有能力的复用和隔离

| 现有能力 | M1—M3 处理 |
| --- | --- |
| `PromptAssemblyService` | 复用 Prompt Part/Hash 思想；不把 Harness Stage 状态塞入现有 `AgentRunContext` |
| `CodexCliDialect` | 复用 JSONL 事件知识；Harness 建立独立 Runtime 出站端口和策略 |
| `Workflow` | 不扩充为 Harness；二者聚合和状态机分离 |
| `FileSlashCommandScanner` | 保持现有 Slash Command UI；Harness 新建 Skill Catalog，不复用 `boolean skill` |
| SQLite Infrastructure | 复用技术栈；新增 Harness Repository，写侧接口位于 Domain |
| 路径白名单 | 复用真实路径策略；Artifact Root 和 Capability Root 增加独立白名单 |

## 6. M0 退出门禁

- [x] Codex Runtime 已有证据支持 Prompt、Skill、只读 MCP 和取消。
- [x] 无法强制的能力使用 fail-closed 策略。
- [x] Stage Contract 与 Artifact Schema 无 M1 阻断问题。
- [x] Artifact、Runtime、MCP/Skill 三份 ADR 已 Accepted。
- [x] Fixture 覆盖四阶段合同所需的 Skill/MCP/Runtime 基础能力。
- [x] MVP 文档已根据 Spike 更新，不保留 Codex 参数假设。
- [x] 自测可重复执行并清理临时目录。
- [x] 外部在线鉴权问题已记录为 M4 前置条件。

## 7. M1 准入与后续门禁

允许立即进入 M1：

- Harness Run/Stage/Attempt 领域模型；
- Artifact Descriptor、GateResult、Approval；
- Repository、SQLite 和 QueryService；
- Feature Flag、ADMIN API 和领域测试。

M4 真实需求验收前必须完成：

- 修复或重新登录 Codex 在线凭据；
- 用真实 OpenAI Provider 执行一次最小 `codex exec --json` Smoke Test；
- 确认本机部署命令、端口占用和回滚手册；
- 选择真实需求并冻结原始 Requirement Artifact。

## 8. 自测命令

```bash
# 不调用模型 Provider；验证配置、Skill 发现和禁用
bash scripts/harness-m0/self-test.sh static

# 使用本地确定性 Responses Provider；验证真实 Codex/MCP 回合
bash scripts/harness-m0/self-test.sh live

# M0 完整自测
bash scripts/harness-m0/self-test.sh all
```

设置 `KEEP_M0_TEMP=1` 可以在失败时保留临时证据；默认成功或失败后清理。临时目录只允许匹配 `/tmp/agent-web-harness-m0.*` 或 `/var/tmp/agent-web-harness-m0.*`。
