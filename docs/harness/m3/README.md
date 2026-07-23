# Harness M3 实现与重新验收完成记录

> 状态：Completed
> 初次实现日期：2026-07-23
> 重新打开日期：2026-07-23（完成性审计发现 Runtime 合同与 M0 ADR 不一致）
> 重新验收完成日期：2026-07-23
> Feature Flag：`agent.harness.enabled=false`
> 详细设计：[M3 MCP 与 Runtime 执行平面](../04-m3-detailed-design.md)
> 验证报告：[M3 自测报告](test-report.md)

## 1. 结论与范围

M3 能力平面与执行平面的重新验收已经完成。Stage Attempt 固化 Prompt/Skill/MCP/Workspace Inventory/Runtime Enforcement，独立 `RuntimeExecution` 在事务提交后启动，取消副作用只会在 `CANCELLING/CANCEL_REQUESTED` 提交后发生。完成性审计发现的 Runtime 激活、工作区旁路、幂等资源和 SQLite 引用保留缺口都已补齐并通过分层、真实 SQLite、CLI Stub 与 Spring 纵向测试。

已经完成的能力：

- M3.0 修复 HarnessRun 更新级联删除 Snapshot 的持久化缺口；
- 管理员可信 MCP 文件 Catalog、专用领域模型和五维授权求交；
- M2/临时 M3/M3.1 Snapshot Schema 兼容与稳定 Hash；
- MCP required、READ Tool allow/WRITE Tool deny、双 timeout、Resource fail-closed 和 Runtime Enforcement 领域规则；
- `WorkspaceRuntimeInventory` 与 `WorkspaceSkillTrustPolicy`，以 Repo Skill ID/入口路径/Entry Hash 阻断未注册或入口变化；
- 独立 `RuntimeExecution` 聚合、事件幂等、取消优先和清理状态；
- Application prepare/activate/launch/cancel/event 编排；
- Harness 专用 Codex Runtime Adapter、隔离环境、Secret 启动期解析和进程树终止；
- 脱敏 JSONL Evidence Store、SQLite 摘要和逻辑 Evidence Reference；
- RuntimeExecution 启动/查询 API 与 Feature Flag 装配；
- CLI Stub/Fake MCP 的纵向成功与取消流程。

重新验收补齐的关键证据：

- Snapshot MCP 必须通过本次命令的 `-c` 覆盖传入，Stub 直接校验参数数组；
- Preflight 阻断祖先 `.codex/config.toml`、未注册/Hash 变化 Repo Skill 和未验证 Codex 版本，并通过 `skills.config` 禁用自动发现；
- STARTED 保存实际 `pid:<pid>`/进程组 Handle；
- 幂等命中返回原资源真实状态与 canonical Location，不同 Stage 复用 key 返回冲突；
- 真实 SQLite 直接证明 Snapshot、Execution 和 Attempt 引用在各类 Run 更新后都不丢失。

M3 仍不实现四阶段自动编排、`WAITING_INPUT` 续跑、Git Diff/追踪矩阵、部署命令治理、重启对账任务、Claude Adapter、写 MCP 或生产 Secret Broker；这些能力按里程碑进入 M4—M6。

## 2. DDD 分层与聚合边界

| 层 | M3 落点 | 责任 |
| --- | --- | --- |
| Interface | `HarnessExecutionController`、Capability 请求 DTO | 参数和幂等键校验、HTTP 202/Location、脱敏 DTO |
| Application | `HarnessExecutionPreparer`、`HarnessExecutionLauncher`、`HarnessRuntimeEventService` | 事务编排、事务外 Runtime 副作用、事件入口；不比较领域状态重组规则 |
| Domain | `HarnessRun`、`CapabilitySnapshot`、`McpAuthorizationPolicy`、`RuntimeExecution` | Attempt/Snapshot/Execution 绑定、MCP 授权、不变量、状态迁移和取消语义 |
| Infrastructure | 文件 MCP Catalog、SQLite Repository/QueryService、Codex Adapter、Secret Resolver、Evidence Store | 文件/JDBC/Process/JSONL/临时目录/Secret 明文等技术细节 |
| Config | `config/harness/*Properties`、`HarnessRuntimeConfig` | Catalog、Runtime、Security 配置及 Spring 托管的有界监控线程池 |

`HarnessRun` 与 `RuntimeExecution` 分开建模：前者决定交付 Stage/Attempt 的业务状态，后者记录一次外部技术执行。Runtime `SUCCEEDED` 只表示 Agent 执行完成，不自动把 Stage 标为 `PASSED`；Stage 仍需正式 Artifact、确定性 Gate 和 Approval。

Repository/CQRS 边界保持分治：

- `HarnessRunRepository`、`CapabilitySnapshotRepository`、`RuntimeExecutionRepository` 位于 Domain；
- `RuntimeExecutionQueryService` 位于 Application，Infrastructure 直接投影 `RuntimeExecutionView`；
- Query API 不返回半截聚合，也不返回 Runtime Handle、启动命令、Secret Reference、完整环境或物理临时路径。

## 3. MCP Catalog 与授权结果

MCP 使用专门模型表达 Server、Capability、Secret Reference、拒绝原因和 Runtime Enforcement，没有把 Tool/Resource/Stage/Risk 压进通用字符串分支：

```text
McpServerDefinition
McpCapability
McpSecretReference
McpSelectionRequest
McpAuthorizationPolicy
SelectedMcpServer / RejectedMcpServer
RuntimeEnforcementProfile
```

有效 Server 必须同时属于：

```text
管理员可信 Catalog
∩ Stage 允许范围
∩ Run 显式 Grant
∩ 环境 Server Allowlist
∩ Runtime 实际 Enforcement
```

当前 M3.1 领域规则：

- 首版只启用 `READ` Tool；混合 Server 的 READ Tool 进入 allowlist、WRITE Tool 进入 denylist，只有 WRITE Tool 的 Server 整服拒绝；
- Tool 型 Server 要求 Runtime 能强制精确 Tool allow/deny，否则整服拒绝；
- 当前兼容矩阵不能独立限制 MCP Resource，含 Resource 的 Server 默认拒绝；
- Server 必须兼容当前 Stage 和 Codex Runtime；
- 未显式 Grant 或不在环境 allowlist 中均拒绝；
- required Server 缺失、版本冲突或被任一维度拒绝时，能力解析阶段 fail-closed；
- Adapter 再次校验配置隔离和 Tool allowlist，作为不能放宽 Domain 结果的技术防线。

完成性审计发现的领域缺口已按红—绿 TDD 修订：

- `McpAuthorizationPolicy` 把 required 固化进选中结果；
- Domain 产出稳定排序的 `enabledToolNames` 与 `disabledToolNames`，Adapter 不遍历 Capability 自行推断；
- 启动超时与 Tool 调用超时分开建模并参与 Snapshot Hash；
- 只允许获准 READ Tool，WRITE Tool 显式 deny；若 Runtime 不能精确 allow/deny 则整服拒绝；
- 当前兼容矩阵未证明 Resource 级隔离，含不可隔离 Resource 的 Server 默认拒绝。

Catalog Manifest 中可以保存命令模板、环境变量名和逻辑 Secret Reference，但禁止 Secret 明文。API 只展示 MCP ID、版本、Capability 和配置 Hash。

## 4. Capability Snapshot Schema 修订

当前 `M3.1` Snapshot 已固化：

- Prompt Pack、资源 Hash、Prompt Parts、最终 Prompt 和 Prompt Hash；
- Selected/Rejected Skill、Package Hash 和文件/命令授权结果；
- Selected/Rejected MCP Server、required、稳定 Tool allow/deny、双 timeout、Capability 和配置 Hash；
- Runtime Enforcement Profile 的 Adapter/实际 Runtime/兼容矩阵/Sandbox/隔离与取消事实及 Profile Hash；
- 工作区边界、项目配置扫描结论、Repo Skill ID/规范入口路径/Entry Hash 和 Inventory Hash；
- Capability Policy Version、Snapshot Schema Version 和 Snapshot Hash。

上述字段已经进入规范序列化与 Hash，`CapabilitySnapshotReference` 只允许 `M3.1` 生成 Runtime Execution 引用。Infrastructure Preflight 已采集实际 CLI 版本和工作区清单，启动前再次复核 Profile/Inventory Hash，并把 Snapshot 事实完整映射到本次命令。

兼容规则：

- 新 Snapshot 使用 `schemaVersion=M3.1`；MCP required/allow/deny/双 timeout、Repo Skill 指纹与实际 Runtime Enforcement 参与新 Hash；
- 旧 M2 Snapshot 以 `schemaVersion=M2`、MCP 空列表和 legacy Enforcement 读取；
- M2 原 Hash 不重算、不回写；
- 已固化 M2 或缺少安全字段的临时 M3 Attempt 不允许原地升级为新 Runtime Execution，需要执行时必须创建新 Attempt；
- 一个 Attempt 最多绑定一个完整 Snapshot 和一个 RuntimeExecution。

## 5. RuntimeExecution 状态机

已实现九个状态：

```text
PREPARED
→ STARTING
→ RUNNING
→ SUCCEEDED | FAILED | TIMED_OUT | LOST

STARTING | RUNNING
→ CANCEL_REQUESTED
→ CANCELLED
```

`PREPARED` 在进程启动前取消时可直接进入 `CANCELLED`。核心不变量包括：

- Execution 必须绑定当前 Attempt 的可执行 Schema Snapshot Hash 和 Prompt Hash；
- 一个 Attempt 不接受第二个 Execution；
- 终态 Execution 不能重新启动；
- Runtime Event 以 `(executionId, sequence)` 幂等，重复 Sequence 不重复迁移或落事件；
- `CANCEL_REQUESTED` 优先于退出码，后续成功/失败退出都保持 `CANCELLED`；
- 取消后管道中已经产生的 OUTPUT 仍可接收，不改变最终取消语义；
- 已启动但终态不明的 Execution 只能进入 `LOST`/人工对账，不能自动重放。

Run/Stage/Attempt 对活动执行使用 `CANCELLING` 中间态。Runtime `FAILED/TIMED_OUT/LOST` 映射为交付失败；Runtime `CANCELLED` 只有在已持久化取消意图时才能确认 Run 取消。

## 6. 提交后启动与提交后取消

启动分为事务组件和非事务外壳：

```text
HTTP start execution
→ HarnessExecutionPreparer.prepare（事务）
   → 校验 Run/Attempt/可执行 Schema Snapshot
   → HarnessRun 签发 ExecutionPermit
   → 绑定 Execution Reference
   → 保存 PREPARED RuntimeExecution
→ commit
→ HarnessExecutionPreparer.activate（新事务）
   → STARTING
→ commit
→ HarnessExecutionLauncher 调用 AgentRuntimeGateway.start
```

取消顺序：

```text
HTTP cancel
→ prepareCancellation（事务）
   → Run/Stage/Attempt = CANCELLING
   → RuntimeExecution = CANCEL_REQUESTED
→ commit
→ AgentRuntimeGateway.cancel
→ 终态回调
→ RuntimeExecution 与 Run = CANCELLED
```

Application 的 Mockito 单测验证调用编排；真实 Spring 事务 + SQLite 测试进一步证明 Gateway 被调用时没有活动事务，且数据库已经能读取 `STARTING` 或 `CANCEL_REQUESTED` 状态。仅凭 `save()` 调用顺序不作为 commit 证据。

## 7. Codex 隔离、Secret 与清理

Harness 使用独立 `CodexHarnessRuntimeGateway`，不复用聊天 `AgentGateway` 或同步文本 `AgentCliInvoker` 的业务合同。Adapter 已实现：

- 仅支持 Codex；
- 使用 Snapshot Prompt，通过 stdin 传入；
- 为单次 Execution 创建隔离 `HOME/CODEX_HOME/XDG_CONFIG_HOME`；
- 只从配置 allowlist 继承进程环境，默认只保留 `PATH`；
- MCP 配置素材只来自 Snapshot，并仅通过本次命令的 `-c` 参数下发；
- 不创建 `CODEX_HOME/config.toml`；命令只携带 Secret 对应的环境变量名，不携带 Secret 值；
- Secret Reference 仅在启动前由 Infrastructure 解析，并只注入目标子进程；
- 临时目录权限为 `700`、文件权限为 `600`；
- 处理 JSONL、结构化失败、退出码、输出上限、idle timeout、最大运行时长和取消；
- 终止整个进程树；
- 成功、启动失败、运行失败、超时和取消均清理临时目录；清理失败落 `cleanupStatus=FAILED`，不会伪装成功。

`turn.failed` 即使伴随退出码 0 也归一化为 `FAILED`；若取消意图已经提交，退出码 0 仍归一化为 `CANCELLED`。

重新验收已确认：

- 用单次 `-c` 同时下发 command/args/env_vars/required/启动超时/Tool timeout/enabled_tools/disabled_tools；
- 用 `skills.config` 禁用工作区所有自动发现 Repo Skill，并在 Snapshot 前阻断未注册/Hash 不匹配项；
- 有界执行 `codex --version`，只接受兼容清单版本，并在启动前复核版本和工作区指纹；
- STARTED 保存至少 `pid:<pid>` 的不透明 Handle；
- CLI Stub 直接检查命令参数，不再以读取临时 `config.toml` 证明真实加载。

真实安装版本的 Codex CLI 不进入默认测试；命令行参数和 MCP TOML 兼容性仍必须在目标环境通过显式 `live` 或手工验证确认。

## 8. Runtime Evidence Store

原始 JSONL 不写入 SQLite 大字段。`FileSystemRuntimeEvidenceStore` 通过现有 `ArtifactStore` 将脱敏正文保存为：

- `ArtifactType.TEST_EVIDENCE`；
- `ArtifactClassification.SENSITIVE`；
- 逻辑引用 `artifact:*`。

SQLite Runtime Event 只保存固定非敏感摘要与 Evidence Reference；API 不暴露 Artifact 物理路径。Adapter 在事件与 Evidence 写入前按本次已解析 Secret 做替换，Artifact 中使用 `[REDACTED]`。Evidence 持久化失败会把 Runtime 结果转成明确失败，避免执行成功却丢失审计证据。

## 9. SQLite、迁移与增量持久化

M3.0 已将 `SqliteHarnessRunRepository` 从删除/重插子表改为：

- Stage、Attempt 按主键 upsert；
- Artifact、Gate、Event 增量 `INSERT OR IGNORE`；
- Approval 只更新有效性；
- 保留 HarnessRun 乐观锁；
- 只有删除整个 Run 时才允许外键级联清理。

新增：

```text
harness_stage_attempt.snapshot_hash
harness_stage_attempt.execution_id
harness_capability_snapshot.schema_version
harness_capability_snapshot.selected_mcp_servers_json
harness_capability_snapshot.rejected_mcp_servers_json
harness_capability_snapshot.runtime_enforcement_json
harness_capability_snapshot.workspace_runtime_inventory_json
harness_runtime_execution
harness_runtime_event
```

约束：

- `(run_id, stage, attempt_number)` 最多一个 Execution；
- `(run_id, idempotency_key)` 唯一；
- `(execution_id, sequence)` 唯一。

旧 M2 表迁移测试手工创建旧 Schema、插入 M2 Snapshot，再连续执行两次 `SqliteInitializer.init()`；新列/新表存在，旧 Hash、无 MCP 语义和 M2 读取行为保持不变。

真实 SQLite 回归已在外键开启状态下绑定 M3.1 Snapshot 与 RuntimeExecution，随后执行 Runtime 回写、Artifact、Gate、Approval 和取消更新；Snapshot 行、RuntimeExecution 行、`Attempt.snapshot_hash` 与 `Attempt.execution_id` 均保持存在且不变。

## 10. API、配置与 Feature Flag

M3 新增：

```text
POST /api/harness/runs/{runId}/stages/{stage}/executions
GET  /api/harness/runs/{runId}/stages/{stage}/attempts/{attemptNumber}/execution
```

启动接口要求 `Idempotency-Key`，返回 `202 Accepted` 和 Execution 查询 `Location`。Capability Snapshot 请求新增 `explicitMcpServerIds`、`requiredMcpServerIds`、`grantedMcpServerIds`。

启动响应从持久化 Execution 返回真实状态。相同 key + 相同 Stage 返回原 Execution 的真实状态和资源自身 Location；相同 key + 不同 Stage 返回 `409 Conflict`；同步启动失败已落库时 Body 返回 `FAILED`。

核心环境变量：

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `AGENT_HARNESS_ENABLED` | `false` | Harness 总开关 |
| `AGENT_HARNESS_ARTIFACT_ROOT` | `data/harness/artifacts` | Artifact/Evidence 受控根 |
| `AGENT_HARNESS_MCP_SERVER_ROOT` | `src/main/resources/harness/mcp-servers` | 管理员可信 MCP Catalog 根 |
| `AGENT_HARNESS_CODEX_COMMAND` | `CODEX_CMD` / `codex` | Harness 专用 Codex 命令 |
| `AGENT_HARNESS_RUNTIME_TEMP_ROOT` | `data/harness/runtime` | 单次执行隔离临时根 |
| `AGENT_HARNESS_ALLOWED_MCP_SERVER_IDS` | 空 | 环境 MCP allowlist；空即全部拒绝 |

Feature Flag 关闭时，Execution Controller、MCP Catalog、Codex Gateway、Runtime Repository/QueryService、Evidence Store 和 Runtime 线程池均不注册，不影响现有 Chat、Workflow 或 Diagnose。

## 11. 回滚与下一步

运行期回滚只需保持：

```yaml
agent:
  harness:
    enabled: false
```

关闭时不主动删除 SQLite Snapshot/Execution/Event 或 Artifact，便于恢复和审计；也不需要修改现有 Workflow/Chat 数据。

M3 全部门禁已经关闭，下一步进入 M4 四阶段纵向切片。真实 Codex `live` 兼容性验证仍不进入默认测试集，但必须在 M4 真实试点前完成；在此之前保持 Harness 默认关闭，不开放测试或生产部署能力。
