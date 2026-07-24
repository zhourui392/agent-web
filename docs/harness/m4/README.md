# Harness M4 实现与验收记录

> 状态：功能集成与受控样例验收完成；M4 Exit 尚未关闭
> 实现日期：2026-07-24
> Feature Flag：`agent.harness.enabled=false`
> 里程碑：[M4：四阶段纵向切片与 MVP 发布](../03-milestones.md#7-m4四阶段纵向切片与-mvp-发布)
> 验证报告：[M4 自测报告](test-report.md)

## 1. 当前结论

M4 的四阶段能力、受控 Runtime、确定性 Gate、独立部署 Approval、local 部署模板、恢复对账、
管理页面和最终追踪报告已经形成完整纵向切片。受控 Codex Stub、真实 SQLite、真实临时 Git
仓库和浏览器流程均已通过。

M4 仍不能标记为 Completed。目标机 Codex CLI 版本为 `0.145.0`，命令参数兼容性已经核对。
M0 使用默认登录态访问真实 Provider 返回 HTTP 401；Harness 自身使用隔离 `HOME/CODEX_HOME`，不会读取该
登录态。M4 已新增显式 Provider Credential Reference，但当前运行环境尚未提供有效引用和凭据。以下强制
退出项因此仍未完成：

- 在线 Provider Prompt 与四阶段 Harness Skill 注入；
- 一个真实需求完成四阶段、经批准 local 部署、业务 AC 和最终报告。

Stub、Fake MCP 和 E2E 部署模板不替代上述真实验收。在提供受控凭据并完成这些步骤前，Harness
继续默认关闭，也不开放 test/production 部署。

真实 `codex-cli 0.145.0` 的离线兼容合同已经复核：现有 M0 自测在隔离 Home 下通过 Skill、只读 MCP、
required fail-closed、Tool timeout 和进程组取消；M4 专用脚本进一步证明 `local-readonly-fixture` 在
ANALYSIS 兼容命令中可调用，而不挂载 Server 时模型侧不可见该 Tool。这些证据使用本地确定性 Provider，
只关闭 CLI/MCP/取消兼容项，不冒充在线 Provider 或真实需求验收。

## 2. DDD 完成性审计

### 2.1 统一语言和领域概念

| 概念 | 类型 | 责任 |
| --- | --- | --- |
| `HarnessRun` | 聚合根 | 固定四阶段、Stage/Attempt、Artifact 元数据、Gate、问题、Approval、失效传播和交付终态 |
| `StageExecution` / `StageAttempt` | 聚合内实体 | 阶段生命周期、单一当前 Attempt、Snapshot/Execution 引用和失败/取消状态 |
| `RuntimeExecution` | 独立聚合根 | 外部 Agent 进程的 PREPARED、启动、事件幂等、取消优先、终态、Evidence 和清理结果 |
| `DeploymentExecution` | 独立聚合根 | 一次 local 外部部署动作、Git 二次 Preflight、终态和人工对账 |
| `CapabilitySnapshot` | 不可变写侧事实 | 固化 Prompt、Skill、MCP、工作区清单、Runtime Enforcement 和全部 Hash |
| `RuntimeArtifactBundle` | 值对象 | 校验 Runtime 成功输出与当前 Stage Artifact 合同完全一致 |
| `RuntimeExecutionOutcome` / `DeploymentExecutionOutcome` | 值对象 | 只携带跨聚合投影需要的 Reference、终态和原因 |
| `HarnessDeterministicGatePolicy` | 领域策略 | 依据 Artifact 正文和 Stage Contract 计算 Gate，不接受调用方自报结果 |
| `DeploymentCommandTemplate` | 值对象 | 固化管理员批准的 token 化 local build/deploy/health/acceptance/rollback 命令 |
| Repository | Domain 端口 | 只管理聚合生命周期；查询列表、时间线、Artifact 正文和部署执行走 QueryService |

主要命令和事件流：

```text
CreateRun → OriginalRequirementFrozen
StartStage / RetryStage → StageAttemptStarted
AskQuestion → WaitingInput → AnswerQuestion → SameAttemptResumed
RuntimePrepared → RuntimeStarted → RuntimeSucceeded | Failed | TimedOut | Cancelled | Lost
ArtifactRegistered → GateEvaluated → ApprovalRequested → Approved | Rejected
DeploymentApproved → DeploymentPrepared → DeploymentSucceeded | Failed | ReconciliationRequired
FinalGatePassed → FinalApproval → RunCompleted
```

### 2.2 不变量和校验点

不变量均由 Domain 守护：

1. Stage 顺序固定为 `ANALYSIS → DESIGN → IMPLEMENTATION → DEPLOYMENT`，上游未通过不能启动下游。
2. 一个 Run 只有一个可写 Attempt；一个 Attempt 最多绑定一个 Snapshot 和一个 RuntimeExecution。
3. 原始需求创建时冻结为 `ORIGINAL_REQUIREMENT`；下游只读取当前已批准上游 Artifact。
4. Artifact 按 Stage + Type 形成逻辑版本；修订使旧 Approval 和所有下游结果失效。
5. Gate 只能读取当前 Attempt 最新 Artifact；所有必需输出和 Gate 通过后才能申请 Approval。
6. 阻断问题使 Run/Stage/Attempt 同步进入 `WAITING_INPUT`，回答后恢复同一 Attempt。
7. `CANCEL_REQUESTED` 优先于退出码；即使进程随后成功退出，也必须投影为取消。
8. Runtime 成功必须提供同 Stage、类型集合完全匹配的 Artifact Bundle，否则转换为显式失败。
9. IMPLEMENTATION 必须从 Run 创建时同一仓库身份捕获 Git 基线；Agent 自报的 Diff/命令证据由实际观察替换或核对。
10. Deployment 只允许 `local`，且必须绑定当前批准输入 Hash、独立部署 Approval 和未变化的 Git 基线。
11. 部署命令只能来自管理员 Catalog 的 token 列表；失败立即停止，rollback 只保存模板、不自动执行。
12. 重启后的未知 Runtime 标记 `LOST`，未知部署标记 `RECONCILIATION_REQUIRED`，均不自动重放。

### 2.3 聚合与一致性边界

- `HarnessRun`、`RuntimeExecution`、`DeploymentExecution` 各自独立持久化，Application 负责事务编排和提交后副作用。
- `HarnessRun` 不注入 Repository，也不再接收其他聚合根；终态映射只消费不可变 Outcome/Execution Reference。
- Runtime/Deployment Repository 接口位于 Domain，签名只包含 Domain 类型；SQLite、Process、文件系统细节留在 Infrastructure。
- CQRS QueryService 接口位于 Application，Infrastructure 返回 View/DTO，不返回半截聚合。
- Artifact 正文由 `ArtifactStore` 保存，Run 聚合只保存 Descriptor、Hash、版本和来源引用。

审计曾发现 `HarnessRuntimeEventService` 直接比较 `RuntimeExecutionStatus` 并解释成功 Bundle，且
`HarnessRun` 直接接收另一个聚合根。领域测试先因缺少 Bundle 校验和 Outcome API 而失败，随后把
规则收回 `RuntimeExecution`，新增不可变 Outcome，并让 Application 只负责编排 Artifact I/O 和
Repository 更新。修订后的 Domain/Application/Recovery/Deployment 聚焦测试均通过。

### 2.4 变化点收敛

| 变化来源 | 收敛点 |
| --- | --- |
| Stage 输入、输出、Gate、Approval 类型 | `StageContract` |
| Gate 规则 | `HarnessDeterministicGatePolicy` |
| Prompt/Skill/MCP 版本和选择 | 文件 Catalog + Domain Policy + `CapabilitySnapshot` |
| Codex/未来 Runtime 差异 | `AgentRuntimeGateway` 端口与 Runtime Adapter |
| 部署环境和命令 | `DeploymentCommandTemplateCatalog`；M4 只接收 local |
| Git 实现差异 | `WorkspaceBaselineGateway` |
| Artifact 脱敏规则 | `ArtifactContentSanitizer` |
| 管理台查询形态 | 独立 CQRS QueryService/View |

首版固定四阶段是有意边界，不抽象任意 DAG；Claude Adapter、自动重启对账、写 MCP、自动 rollback
和生产 Secret Broker 保留到 M5/M6，不在 Application 用条件链预埋。

### 2.5 审计评分

| 维度 | 评分 | 依据 |
| --- | ---: | --- |
| 聚合边界是否清晰 | 3/3 | 三个可变聚合分离，Run 只消费 Outcome/Reference，Repository/CQRS 分治 |
| 变化是否被收敛 | 3/3 | Stage、Gate、Runtime、MCP、部署和 Git 均有明确策略或端口 |
| 不变量是否可被模型守护 | 3/3 | 顺序、版本、Hash、取消、部署许可和恢复语义均在 Domain 测试覆盖 |
| 行为是否与模型一致 | 3/3 | Application 状态判断已下沉，只保留事务、幂等和外部 I/O 编排 |
| 是否支持下一轮变化 | 2/3 | M5 对账/审计接口已留出，但首版 Artifact Bundle 仍有一个崩溃窗口 |

尚待验证的问题不是建模缺口，而是外部验收条件：有效的受控 Codex Provider 凭据、真实试点需求和经
管理员批准的 local 部署模板。

## 3. 四阶段纵向流程

### 3.1 ANALYSIS

- Run 创建时解析真实工作目录、记录仓库根/分支/HEAD/clean/diff Hash，并冻结原始需求；
- Runtime 生成 `REQUIREMENT`、`ACCEPTANCE_CRITERIA`、`IMPACT_ANALYSIS`、`OPEN_QUESTIONS`；
- 阻断问题通过 `WAITING_INPUT` 在同 Attempt 闭环；
- Schema、ID 唯一、AC 可观察、无阻断问题等确定性 Gate 通过后人工批准当前 Hash。

### 3.2 DESIGN

- Prompt 只装配已批准的分析 Artifact；
- 生成 `SOLUTION`、`CHANGE_PLAN`、`TEST_STRATEGY`、`DEPLOYMENT_PLAN`、`ROLLBACK_PLAN` 和 `TRACEABILITY`；
- Gate 验证 Requirement/AC 对设计和测试的覆盖、分层决策和回滚计划；
- Approval 绑定当前设计 Artifact Baseline Hash。

### 3.3 IMPLEMENTATION

- Attempt 启动时保存真实 Git 基线 Artifact；
- Runtime 使用 `workspace-write`，文件根和逻辑命令仍与 Snapshot 授权求交；
- Adapter 观察实际命令，`TEST_EVIDENCE` 必须包含 RED/GREEN 顺序和允许命令；
- Runtime 返回后重新采集 `git status --porcelain`、`git diff --binary`、untracked 内容 Hash；
- Agent 声称的 Changed Files 被真实 Git Evidence 替换，Gate 检查基线、TDD、聚焦测试、追踪和敏感文件。

### 3.4 DEPLOYMENT

- 只允许 `environment=local`；
- Stage Approval 与“执行一次 local 部署”的 Approval 分开，后者绑定当前已批准输入 Hash；
- 事务内保存 PREPARED DeploymentExecution，提交后再次采集 Git 基线并激活；
- Process Gateway 顺序执行 build、deploy、healthCheck、acceptance，任何失败立即停止；
- 保存 Preflight、Build、Deployment Record、AC Result 和 Final Report；
- 最终 Gate 和人工 Approval 通过后 Run 才进入 `COMPLETED`。

## 4. Artifact、追踪和查询

Runtime 使用 Codex `--output-schema` 与 `--output-last-message` 写出
`harness-artifact-bundle@1`。Bundle 在进程结束后进入事务回调，正文先脱敏，再由 Run 分配逻辑版本并
写入 Artifact Store。Implementation 和 Deployment 还会用平台实际观察补充或替换 Agent 声明。

最终报告包含：

```text
Requirement → Acceptance Criteria → Design → Test Evidence
            → Implementation Ref → Deployment AC Result
```

管理 API 提供 Run 列表/详情、事件时间线、Capability/Runtime、部署 Readiness/Execution、Artifact
下载和 Final Report；列表与明细走 CQRS，不需要管理员修改数据库。

## 5. 恢复和故障窗口

应用启动后扫描未完成外部动作：

- PREPARED/STARTING/RUNNING/CANCEL_REQUESTED Runtime → `LOST`，Run/Stage 进入失败，可人工 Retry；
- PREPARED/RUNNING Deployment → `RECONCILIATION_REQUIRED`，必须由管理员核对外部状态后显式关闭为失败；
- 恢复方法幂等，不新建替代 Execution，不自动调用 Runtime 或部署 Gateway。

首版仍有一个已知窗口：子进程已经成功退出并把 Bundle 写入临时目录，但应用在数据库回调提交前崩溃，
重启清理/恢复只能把 Runtime 归类为 `LOST`，临时 Bundle 可能无法恢复。这是 M5 的持久接收箱/自动对账
增强范围；M4 不把它描述为可无损恢复，也不会因此自动重放 Agent 或部署。

## 6. 安全边界

- Feature Flag 默认关闭；关闭时 API、Repository、Catalog、Store、Runtime 和导航入口均不注册/显示。
- 管理写 API 要求数据库 ADMIN 会话和 `Idempotency-Key`。
- 需求、Skill 或 Agent 输出不能提供 MCP command、部署 command、环境变量或文件根。
- MCP 必须来自管理员 Catalog、Run Grant、环境 allowlist 和 Runtime Enforcement 的交集；M4 仍只读。
- `local-readonly-fixture` 不读文件、不访问网络、不使用 Secret；真实 Codex 离线兼容性验收已确认它只在挂载时返回固定值。
- 部署 Catalog 默认没有可执行模板；必须显式覆盖目录，test/production 模板 fail-closed。
- Runtime 只继承环境 allowlist；Provider 凭据必须通过显式逻辑引用解析，只注入单次隔离进程并加入输出脱敏，Secret 值不进入 Snapshot、Prompt、API、SQLite、日志或 Artifact。
- Artifact 下载校验 Run 归属、内容 Hash、路径边界和脱敏结果。

## 7. 开启、配置与关闭

### 7.1 开启前置

1. 保持源码和数据库备份，确认目标 Codex 版本在兼容清单内。
2. 使用管理员维护的外置只读 Prompt/Skill/MCP/Deployment Catalog；不要在 Catalog 保存 Secret。
3. 提供仅 local、token 化且已人工评审的部署模板；仓库默认目录刻意不内置可执行模板。
4. 配置受控 Artifact/Runtime 根目录，并限制服务账户文件权限。
5. 在线 Provider 验收时，把 `AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE` 设为承载有效凭据的服务进程环境变量名；不要把凭据值写进配置、Catalog 或用户认证目录。
6. 先在独立数据库和工作区验证，再设置 `AGENT_HARNESS_ENABLED=true`。

核心配置：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_HARNESS_ENABLED` | `false` | Harness 总开关 |
| `AGENT_HARNESS_ARTIFACT_ROOT` | `data/harness/artifacts` | Artifact 正文根 |
| `AGENT_HARNESS_PROMPT_PACK_ROOT` | 源码资源目录 | Prompt Pack Catalog |
| `AGENT_HARNESS_PLATFORM_SKILL_ROOT` | 源码资源目录 | 平台 Skill Catalog |
| `AGENT_HARNESS_MCP_SERVER_ROOT` | 源码资源目录 | 管理员 MCP Catalog |
| `AGENT_HARNESS_ALLOWED_MCP_SERVER_IDS` | 空 | 环境 MCP allowlist；空即全部拒绝 |
| `AGENT_HARNESS_DEPLOYMENT_TEMPLATE_ROOT` | 空模板目录 | 管理员 local 部署模板 Catalog |
| `AGENT_HARNESS_CODEX_COMMAND` | `CODEX_CMD`/`codex` | Harness 专用 Codex CLI |
| `AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE` | 空 | Codex Provider 凭据的环境变量逻辑名；空时不注入凭据，也不回退用户 `CODEX_HOME` |
| `AGENT_HARNESS_RUNTIME_TEMP_ROOT` | `data/harness/runtime` | 单次隔离目录 |
| `AGENT_HARNESS_DEPLOYMENT_TIMEOUT_SECONDS` | `600` | 单个部署步骤上限 |
| `AGENT_HARNESS_DEPLOYMENT_MAX_OUTPUT_BYTES` | `1048576` | 单个部署步骤输出上限 |

开启后由 ADMIN 访问 `/admin/harness.html`。不要通过修改数据库跳过 Gate/Approval，也不要把
`tests/e2e/fixtures/harness` 当作真实部署模板。

例如由部署系统提供名为 `HARNESS_CODEX_PROVIDER_KEY` 的 Secret 环境变量时，只需把
`AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE` 设置为字符串 `HARNESS_CODEX_PROVIDER_KEY`。Harness 启动
单次 Codex 进程时才解析其值并注入子进程 `OPENAI_API_KEY`；引用和值都不会进入 Snapshot，值还会加入
Runtime Evidence 脱敏集合。不要把 `OPENAI_API_KEY` 加入通用继承环境白名单。

在线 Provider 四阶段 Prompt/Skill 的显式 `live` 门禁为：

```bash
AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE=HARNESS_CODEX_PROVIDER_KEY \
scripts/harness-m4/verify-online-provider.sh
```

`HARNESS_CODEX_PROVIDER_KEY` 必须由服务进程环境或 Secret Store 预先提供；不要把值写进命令、仓库或
报告。脚本只验证四阶段真实 Prompt/Skill 到在线 Provider 的只读合同，不替代后续真实需求、Gate、
Approval 和 local 部署验收。缺少引用、引用不存在或引用名非法时，脚本会在启动 Codex 前失败关闭。

### 7.2 关闭与回滚

设置：

```text
AGENT_HARNESS_ENABLED=false
```

关闭不会删除 Run、Snapshot、Execution、Event 或 Artifact，便于审计；也不会自动结束已经脱离应用的
外部动作。若关闭前存在运行中部署，应先人工核对进程和目标环境。重新开启时恢复服务只标记未知动作，
不会自动重放。

## 8. 下一步和 M4 关闭条件

1. 由用户或部署系统提供有效的受控 Provider 凭据，并配置对应的 `AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE`；不得由 Harness 读取或改写用户级认证目录。
2. 执行 `scripts/harness-m4/verify-online-provider.sh`，验证在线 Provider 收到真实 Prompt 和四个 Stage Skill；CLI/MCP/取消离线兼容合同已经通过，无需重复冒充在线验收。
3. 选择一个真实但风险可控的需求，冻结原始 Requirement，执行完整四阶段和独立 local 部署 Approval。
4. 核对业务 AC、追踪矩阵、最终报告、Artifact/Runtime/Deployment Hash 和恢复记录。
5. 只有 MVP DoD 与 M4 Exit 全部有直接证据后，才能把本文状态改为 Completed 并进入 M5。
