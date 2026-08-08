# Agent Runtime 三类接入统一设计（Review 修正版）

状态：Phase 0/1、Claude 公共 CLI 路径、NATIVE Profile/Handle 主链以及 Phase 3 的旧链路收口代码已完成；`AgentCliGateway` façade 和 `AgentRuntime.run(AgentRunInvocation, ...)` 已删除。应用配置的公共 Chat 开关默认值已翻转为 `true`；真实 CLI/AgentKit endpoint E2E、默认开启后的受控验收和旧 `AgentGateway` 回滚端口的最终退役仍待完成。

适用范围：Chat、Local Development Workbench，以及后续复用 Agent Run 生命周期的入口。本文是可实施的首期设计，不把尚未验证的能力提前做成基础设施。

## 1. 结论先行

系统不再新增 `AgentRuntimeAdapter`。代码中已有的 `AgentRuntime` 就是 Provider Adapter 抽象，`RoutingAgentGateway` 就是按 `AgentType` 路由的实现原型。新 Runtime 路径应扩展并复用它们，而不是再建一套同形接口。

保留 `AgentExecutionGateway`，原因不是再增加一层 Provider Adapter，而是它是当前应用服务共同依赖的异步 Run 生命周期端口：`start(plan, sink)`、`requestStop(handle)`、`observe(handle)`。它是应用侧稳定门面；`AgentRuntime` 承担 Provider 协议、流解码、历史投递和 Provider Handle。两者的生命周期边界不同，迁移期不能直接删除应用端口。

目标结构：

```text
ChatRunRuntimeLauncher / WorkbenchRun...
                 │
                 ▼
        AgentExecutionGateway       ← 统一异步 Run 端口
        （最终由 RoutingAgentGateway 实现）
                 │
                 ▼
        RoutingAgentGateway          ← AgentType 路由和 stop 语义
                 │
                 ▼
           AgentRuntime              ← 现有 Provider Adapter
          ┌───────────────┬────────────────────┐
          │               │                    │
   CliAgentRuntime   NativeDiagnosis...   （后续其他实现）
          │
          ▼
   AgentProcessKernel                ← CLI 进程生命周期内核
      ├ Codex CLI
      └ Claude CLI
```

这里明确选择一条路径：`RoutingAgentGateway` 只实现新的 `AgentExecutionGateway`，统一维护 `AgentRuntime` 注册和 Run → Runtime 路由，转发异步任务、句柄、Stop 和 Observe。Provider Handle 由具体 `AgentRuntime` 持有，`AgentProcessKernel` 不再作为第二个 `AgentExecutionGateway` Bean，而是由 CLI `AgentRuntime` 使用的进程生命周期组件；其现有 workspace/capability materialization 保留为计划执行协作者，不承担 AgentType 路由。旧 `AgentGateway` 仅作为显式标注的回滚端口，由 `CliAgentRuntime` 在兼容窗口内实现；它不再参与公共 Runtime 路由。

同一个 `AgentType` 的不同 URL/Key 通过选择不同 Profile 实现；模型和思考强度既有 Profile 默认值，也可在单次调用中按白名单覆盖。调用方不能提交任意明文 Key 或任意 URL。

## 2. 对现有代码的确认

### 2.1 已有 Adapter 模式

- [`AgentRuntime`](../src/main/java/com/example/agentweb/app/agentrun/port/AgentRuntime.java) 只定义 `supportedTypes`、`start(plan, sink)`、`requestStop(handle)` 和 `observe(handle)` 公共 Runtime 契约；旧 Invocation 执行方法已删除。
- [`CliAgentRuntime`](../src/main/java/com/example/agentweb/infra/agentrun/CliAgentRuntime.java) 已声明支持 `CODEX`、`CLAUDE`，协议差异集中在 CLI 实现内。
- [`NativeDiagnosisAgentRuntime`](../src/main/java/com/example/agentweb/infra/nativeagent/NativeDiagnosisAgentRuntime.java) 是 NATIVE 实现。
- [`RoutingAgentGateway`](../src/main/java/com/example/agentweb/infra/agentrun/RoutingAgentGateway.java) 已按 `AgentType` 建索引，并维护活动 Run、延迟 Stop 和终态缓存。
- [`AgentRuntimeRegistry`](../src/main/java/com/example/agentweb/app/agentrun/port/AgentRuntimeRegistry.java) / `SpringAgentRuntimeRegistry` 已能投影当前可用 Agent。

因此，本方案不创建以下平行概念：`AgentRuntimeAdapter`、`AgentRuntimeAdapterRegistry`、`CodexCliRuntimeAdapter`、`ClaudeCliRuntimeAdapter`、`NativeDiagnosisRuntimeAdapter`。如果后续某个 Provider 需要不同协议，新增的仍是 `AgentRuntime` 实现或现有 CLI 实现内的协作者。

### 2.2 新 Runtime 当前边界

新公共 Runtime 已有：

- [`AgentExecutionGateway`](../src/main/java/com/example/agentweb/app/runtime/port/AgentExecutionGateway.java)：异步启动、停止和观察端口；
- [`AgentExecutionPlan`](../src/main/java/com/example/agentweb/app/runtime/port/AgentExecutionPlan.java)：一次执行所需的不可变事实；
- [`AgentProcessKernel`](../src/main/java/com/example/agentweb/infra/runtime/AgentProcessKernel.java)：本地进程生命周期内核；
- [`ChatExecutionPlanProvider`](../src/main/java/com/example/agentweb/app/runtime/ChatExecutionPlanProvider.java) 和 [`WorkbenchExecutionPlanProvider`](../src/main/java/com/example/agentweb/app/runtime/WorkbenchExecutionPlanProvider.java)：按 `RunOrigin` 组装计划。

当前仍有一项受控差异：Codex 的 Runtime 事件安全投影沿用现有 `CodexEventNormalizer`，Claude 已通过 `ClaudeCliDialect` 接入命令和基础事件投影。迁移工作是让这些现有组件读取 `AgentType`/Profile 绑定，并复用已有 `AgentRuntime` 能力；不是重新定义 Provider Adapter。

当前实现已完成这次装配切换：`AgentProcessKernel` 只作为 CLI 进程生命周期组件，`CommonRuntimeConfiguration` 只暴露由 `RoutingAgentGateway` 实现的公共 `AgentExecutionGateway`。Kernel 仍负责 workspace/capability materialization、进程监控和清理；Routing Gateway 负责 AgentType 路由及 RuntimeHandle 的 Stop/Observe。应用层只依赖 `RuntimeProfileSelector` 等 app 端口，Profile Catalog 是 infra 实现，不向应用层泄漏。

Chat 的 Start/Stop 端口缺口也已在 Phase 0/3 修复：`ChatRunRuntimeLauncher` 和 `ChatRunAppServiceImpl.stop` 只使用 `AgentExecutionGateway`；Handle 尚未绑定时先持久化取消状态，绑定后再次检查并补发 Stop，已终止进程则由公共终态观察和清理规则收口。Chat Stop 不再回退到 `AgentGateway.stopStream(runId)`。默认开关关闭时，`ChatRunExecutor` 仍是显式的 legacy 启动/事件兼容路径；它与公共路径共享 `CliAgentRuntime` 内的同一 `AgentProcessKernel`，不构成第二套进程管理。`AgentCliGateway` façade 已删除。

### 2.3 当前业务 Surface 约束

[`AgentOfferPolicy`](../src/main/java/com/example/agentweb/domain/agentrun/AgentOfferPolicy.java) 是 Surface 级策略：`CODEX`、`CLAUDE` 可作为通用 Agent；`NATIVE` 当前只允许 Chat。统一 Runtime 不改变这项产品策略。

## 3. 边界和职责

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| Chat/Workbench 应用服务 | Owner、Surface、RunMode、幂等、业务上下文校验；请求 Profile 选择 | CLI 参数、Key 读取、Provider JSON |
| `ExecutionPlanProviderRegistry` | 按 `RunOrigin` 选择 Chat 或 Workbench 计划 Provider | 按 AgentType 路由 |
| `AgentExecutionGateway`（最终由 `RoutingAgentGateway` 实现） | 统一 `start/stop/observe`、`RuntimeHandle` 和异步事件入口 | 业务授权、Profile 管理 |
| `RoutingAgentGateway` | 承载公共异步生命周期；按 `AgentType` 找到 `AgentRuntime`，维护活动运行和 Stop 竞态语义 | 旧 `AgentGateway` 兼容调用、ChatSession、Workbench 聚合、Profile 选择 |
| `AgentRuntime` | Provider/CLI/NATIVE 协议、命令参数、公共事件/Resume 解码、Provider 停止语义 | 旧 Invocation 门面、业务规则、仓储、用户授权 |
| `AgentProcessKernel` | 进程启动、stdin/stdout、watchdog、超时、进程树清理，以及 CLI 进程句柄的底层观察 | AgentType 路由、Run 级 Stop/Observe 和 Provider 业务判断 |
| Runtime 应用层 Profile 选择器 | 按 `AgentType + Surface + RunMode` 选择并校验 Profile，合成并持久化 `RuntimeSelection` | Secret Store、Provider 协议 |
| 配置/基础设施 | 从 `data/secrets.properties` 加载 Profile 和 Key，在进程启动前按 `profileId` 取 Key、注入并立即清理 | 把 Key 写入 Run 持久化或日志 |

`AgentExecutionGateway` 的现有消费者（`ChatRunRuntimeLauncher`、恢复与事件处理服务、`WorkbenchRunCancellationCoordinator` 等）继续依赖该端口。不要为消除表面重复而让每个应用服务直接持有 Registry 和凭据读取逻辑，这会扩散 Stop、Observe 和句柄路由。

## 4. Profile 与调用绑定

### 4.1 首期 Profile

首期 Profile 和 API Key 统一放在 Git 忽略的 `data/secrets.properties`。`application.yml` 不保存 Profile 内容或 Key；Runtime 配置组件按固定路径加载该文件。不引入配置数据库、独立 Revision 表或管理 UI：

```text
AgentRuntimeProfile
├── profileId
├── agentType                 # CODEX / CLAUDE / NATIVE
├── endpoint                  # 允许的 Provider 地址
├── apiKey                    # 只存在 data 文件和内存 Profile，不进入 Run
├── defaultModel
├── allowedModels
├── defaultReasoningEffort
├── allowedReasoningEfforts
├── supportedSurfaces         # CHAT / WORKBENCH ...
├── supportedRunModes         # READ / WRITE 等
└── enabled
```

同一 `agentType` 可以有多个 Profile，例如 `codex-openai`、`codex-compatible`、`claude-prod`。每个 Profile 直接配置自己的 endpoint 和 Key；调用时选择 Profile 即可切换 URL/Key 组合，不需要开放任意 endpoint/key override。

示例：

```properties
agent.runtime.profiles.codex-openai.agent-type=CODEX
agent.runtime.profiles.codex-openai.endpoint=https://api.openai.com/v1
agent.runtime.profiles.codex-openai.api-key=<local-secret>
agent.runtime.profiles.codex-openai.default-model=gpt-5.6
agent.runtime.profiles.codex-openai.allowed-models=gpt-5.6,gpt-5.6-mini
agent.runtime.profiles.codex-openai.default-reasoning-effort=medium
agent.runtime.profiles.codex-openai.allowed-reasoning-efforts=low,medium,high
agent.runtime.profiles.codex-openai.supported-surfaces=CHAT,WORKBENCH
agent.runtime.profiles.codex-openai.supported-run-modes=DISCUSS_READ_ONLY,MODIFY_WORKSPACE
agent.runtime.profiles.codex-openai.enabled=true
```

文件约束：

- 使用既有 Git 忽略文件 `data/secrets.properties`，新增 Profile 前执行 `git check-ignore -q data/secrets.properties`。
- `data/` 目录权限为 `700`，文件权限为 `600`；启动时权限过宽则 fail-closed。
- Key 只存在本地文件、内存 Profile 和子进程环境；禁止进入数据库、Prompt、SSE、异常消息、命令行参数或日志。
- Profile 文件不生成模板副本、不进入 Git；文档和测试夹具只能使用 `<local-secret>` 等占位符。
- 依赖 CLI 用户登录态的 Profile 可以不配置 `api-key`；显式配置 Key 时，启动进程前由 Kernel 注入方言要求的环境变量。

鉴权策略变更说明：`AGENTS.md` 的默认安全规则仍然禁止读取、复制或改写 `~/.codex`、`~/.claude`；只有用户明确选择 Profile API Key 鉴权时，才允许把 Key 写入 Git 忽略的 `data/secrets.properties`。本设计已经作出并落地这一明确决策：当 Profile 显式配置 `api-key` 时，运行时使用该 Key；未配置 `api-key` 的 Profile 继续使用 CLI 本机默认登录态。`application.yml` 与 `data/secrets.properties` 的注释已同步说明两种路径，不能再按“CLI Key 一律不进 secrets 文件”理解。

Phase 0/1 的同步维护项（已完成）：

- 更新 `application.yml` 约第 367 行的注释，说明 Profile API Key 是可选的显式鉴权路径；
- 更新 `data/secrets.properties` 第 2 行的注释，说明该文件现在允许保存 Profile API Key，但 CLI 默认登录态仍可保留；
- 保留 `AGENTS.md` 的安全例外和禁止读取 `~/.codex`/`~/.claude` 认证目录规则；实现不得为了 Profile Key 去读取、复制或改写用户级认证目录。

### 4.2 选择规则

运行请求包含 `agentType`、`surface`、`runMode`，可选 `profileId`、`model`、`reasoningEffort`。

```text
(agentType, surface, runMode, optional profileId)
        → enabled Profile
        → 校验 AgentOfferPolicy 和 Profile policy
        → 应用 model/reasoning 单次 override
        → 将 RuntimeSelection 冻结到 Run 执行输入
```

- 指定 `profileId`：Runtime 层校验 Profile 存在、启用、`agentType` 一致，且支持该 Surface/RunMode。
- 未指定 `profileId`：只有一个符合条件的启用 Profile 时自动选择；候选数为 0 或大于 1 都 fail-closed。首期实现就是一次计数判断，大于 1 记录 `MULTIPLE_ENABLED_PROFILES` 内部原因并拒绝，不引入排序、权重或复杂选择算法。
- `model`、`reasoningEffort` 只能取 Profile 的允许列表；不支持时使用默认值。
- `runtimeEnvironment` 优先使用 Profile 的固定值；Profile 未配置时沿用来源 Run 的环境标识。NATIVE 必须在选择阶段验证该值属于 `NativeDiagnosisAgentRuntime` 的已绑定环境。
- endpoint 和 credential 的调用时切换通过选择另一个 Profile 完成。首期不接受任意 URL 或明文 Key；将来如需临时 endpoint/key，另行设计权限、审计和 Secret 生命周期。

### 4.3 Run 上持久化的 RuntimeSelection

提交 Run 时，将解析后的 `RuntimeSelection` 作为 Run 执行输入的一部分持久化。它是现有值对象的冻结投影，不新建独立 Binding 类型；`runId` 已由 `ChatRun` 持有。

```text
Persisted RuntimeSelection (owned by Run)
├── profileId
├── agentType
├── endpoint
├── model
├── reasoningEffort
├── runtimeEnvironment     # NATIVE 环境标识，可为空
└── runtimeVersionPolicy
```

持久化的 `RuntimeSelection` 是运行事实来源：Profile 后续修改不影响已提交 Run，也不需要 Profile Revision 表。Chat 和 Workbench 各自把这些字段写入已有的 Run 执行投影/快照；恢复时反序列化同一个 `RuntimeSelection`，不重新解析 Profile。若需要审计变更，先记录配置文件提交；只有出现合规需求并确认查询场景后，才考虑历史表。

首期不增加 `bindingHash`。当前本地 Profile 文件 + SQLite Run 持久化没有跨服务校验、审计对账或完整性验证消费方，Hash 既不能替代数据库权限，也不能防止可写数据库的攻击者重算。未来出现明确消费方时，再单独设计完整性字段。

由于首期不持久化 Key，进程启动时按 `RuntimeSelection.profileId` 从当前内存 Profile 读取 Key。修改 `data/secrets.properties` 并重载服务后，尚未启动或需要重试的 Run 会使用该 Profile 的新 Key；本方案只冻结 endpoint/model/reasoning 等非秘密选择，不承诺 Key 的历史版本语义。若产品需要后者，再单独引入受控 Secret 版本机制。

### 4.4 运行时对象的字段归属

首期不新建平行的 `AgentInput`、`AgentRuntimeSelection` 或 Binding 类型。在现有类型上做最小扩展，字段归属如下：

| 对象 | 首期字段 | 来源/用途 |
| --- | --- | --- |
| `AgentRuntimeProfile` | `profileId`、`agentType`、`endpoint`、`apiKey`、默认/允许的 `model`、默认/允许的 `reasoningEffort`、`runtimeEnvironment`（可选）、`supportedSurfaces`、`supportedRunModes`、`enabled` | 从 `data/secrets.properties` 加载；Key 只留在内存 Profile，不进入 Run |
| Run 持久化的 `RuntimeSelection` | `profileId`、`agentType`、`endpoint`、已解析 `model`、已解析 `reasoningEffort`、`runtimeEnvironment`、现有 `runtimeVersionPolicy` | Run 提交时冻结非秘密选择；恢复时原样反序列化，不创建独立 Snapshot 类型 |
| 现有 `RuntimeSelection` | 与上行 Run 持久化字段相同 | 应用层从 Run 执行投影组装 `AgentExecutionPlan` 时携带的执行选择 |
| 现有 `AgentExecutionPlan` | `ExecutionIdentity`、`RuntimeSelection`、`PromptPayload`、`resumeId`、Workspace、Capability、Limits、Attachment 等 | 一次执行的完整事实；不再增加 Profile 查询或默认值推断 |
| 现有 `AgentRunInvocation`（仅旧兼容路径） | 保持现有 Run/Prompt/History 字段；继续承载 `conversationId`、`userMessageId`、`resumeId`、`env`、`userId`、`history`、`extraEnv` | 只供旧 `AgentGateway` 回滚端口使用；新公共路径不再把完整 Plan 压缩成该对象 |
| 现有 `PromptPayload` | `finalPrompt`、`promptHash`、`historyDelivery`，增量增加可选 `typedHistory` | Prompt 和历史投递，不承载 URL、Key、模型或思考强度 |

`allowedModels`、`allowedReasoningEfforts` 只在 Profile 选择阶段使用，校验通过后不重复塞进 `RuntimeSelection`。`enabled`、Surface/RunMode 策略同样属于选择阶段；Provider 只接收已解析的单值。`apiKey` 不属于 `RuntimeSelection`，实际读取只发生在基础设施进程启动前。

### 4.5 Plan 字段来源和历史投递

`ExecutionIdentity` 首期扩展为以下明确字段，不再让 Gateway 猜测旧 Invocation 所需的身份：

```text
ExecutionIdentity
├── executionId       # ChatRun.id；也是 RuntimeHandle.executionId
├── ownerId           # Chat userId 或 Workbench ownerId
├── originReference   # chat:<sessionId> 或 Workbench snapshot 的 origin reference
├── conversationId    # Chat sessionId；Workbench 使用关联的 stage conversation/sessionId
└── userMessageId     # ChatRun.userMessageId；Workbench 使用该 Run 的输入消息 ID
```

`AgentExecutionPlan` 增加一个可空的 `resumeId` 字段。它不是 Profile 绑定：

- Chat 从 `ChatRunExecutionContext.resumeId` 读取；当前实际来源是 `chat_session.resume_id`，由已有的 `SessionRepository` 维护。
- Workbench 从其已持久化的 stage conversation continuation 输入读取；尚未支持 Resume 的来源在计划准备阶段 fail-closed，不传空值伪造续接。
- `RoutingAgentGateway` 不生成、不提取、不替换 `resumeId`；Provider 只消费 Plan 中的值。新的 Resume ID 由 CLI `CliDialect.extractResumeId` 写回现有 Chat `SessionRepository.updateResumeId`，或由 NATIVE checkpoint 持久化到其已有 continuation 存储，供下一次 Run 使用。

`RuntimeSelection.runtimeEnvironment` 对应旧 `AgentRunInvocation.env`：Chat 使用 `ChatRunExecutionContext.env`，NATIVE 必须使用已绑定的 AgentKit 环境名，普通 CLI 可为空。Profile Key 的读取和注入不进入 `RuntimeSelection` 或 `extraEnv`：

1. `RoutingAgentGateway` 只转发 Plan，不读取 `data/secrets.properties` 或 Secret。
2. `CliAgentRuntime` 按 `RuntimeSelection.profileId` 从已加载的 Profile 配置索引取当前 `apiKey`，并将 Key 交给 `AgentProcessKernel`；这不是独立的 CredentialResolver/Lease 抽象。
3. Kernel 在 `ProcessBuilder.start()` 前调用 `CliDialect.credentialEnvironmentVariable()` 获取 CLI 目标变量名（如 `OPENAI_API_KEY`），只把当前 Profile Key 写入子进程环境，启动后立即清理临时映射。
4. 旧 `AgentRunInvocation.extraEnv` 继续供旧 Chat 的非 Profile 环境扩展使用；新公共路径不把 Profile Key 放入该字段。

历史投递规则固定为：

- `PROMPT_PREFIX`：`PromptPayload.finalPrompt` 已包含历史前缀，`AgentExecutionPlan` 不再重复携带 `history`。
- `TYPED`：`PromptPayload.typedHistory` 保存结构化历史，`NativeDiagnosisAgentRuntime` 在内部映射为 AgentKit history。
- `PROVIDER_RESUME`：`finalPrompt` 只包含当前输入，`resumeId` 负责续接，history 列表为空。

因此新公共路径不需要从 Plan 还原旧 `AgentRunInvocation.history`。只有旧兼容路径继续从 `ChatRunExecutionContext.history` 构造该列表。

## 5. Runtime 端口如何复用

### 5.1 两个端口的明确差异

| 端口 | 输入/输出 | 适用职责 |
| --- | --- | --- |
| `AgentRuntime` | 直接接收 `AgentExecutionPlan` + `RuntimeEventSink`，并通过 `RuntimeHandle` 提供 Stop/Observe | 旧 Invocation 兼容端口、Provider 适配、协议解码、历史/Resume、Provider 句柄 |
| `AgentExecutionGateway` | `AgentExecutionPlan` + `RuntimeHandle`/`RuntimeObservation` | 应用统一的异步 Run 生命周期 |

新公共路径不再做 `AgentExecutionPlan → AgentRunInvocation` 的信息压缩。Phase 3 已从 `AgentRuntime` 删除旧的 `run(AgentRunInvocation, ...)`、`stop(String)`、Chunk/Resume 方法；这些兼容能力只保留在显式的 `AgentGateway` 回滚端口及其 `CliAgentRuntime` 实现中。这样 Workspace、Capability、Limits、Attachment、Selection 和 resumeId 原样到达 Provider，字段不会在私有转换器中丢失。

最终由升级后的 `RoutingAgentGateway` 实现 `AgentExecutionGateway`：它只按 `plan.getRuntimeSelection().getAgentType()` 路由到一个 `AgentRuntime`，转发 `start/stop/observe`。它不读取 Profile、不 materialize Workspace/Capability，也不把 Plan 重组为旧 Invocation。`AgentRuntime` 的新 `start` 方法返回并拥有 Provider 句柄，`RoutingAgentGateway` 只维护 `executionId → AgentRuntime` 的路由表和已完成 Run 的最小终态缓存；旧 `AgentGateway` 兼容端口不再由它承载。

这样保留 `AgentExecutionGateway` 是应用侧稳定端口，而不是再造 Provider Adapter：应用服务统一通过它启动、停止和观察；所有新的 Provider 执行都已经落到现有 `AgentRuntime` 接口。旧 `AgentGateway` 仅用于 legacy Chat Executor 和 Scheduled Task 的受控回滚窗口，待这些消费者迁移后再单独评估其退役。

`AgentProcessKernel` 的目标职责是 CLI 进程生命周期；其现有的 Workspace/Capability materialization 仍由 Kernel 在 `AgentExecutionPlan` 上执行，不移动到 `RoutingAgentGateway`。Kernel 接收 `CliAgentRuntime` 已选择的 CLI 方言，负责命令启动、stdin、stdout 监控、watchdog、进程树停止、清理和底层进程观察；它不负责 AgentType 路由、Profile 选择或 Run 级 Stop/Observe。旧 `AgentGateway` 调用方在迁移窗口内直接由 `CliAgentRuntime` 的兼容端口承载；不新建 `GenericCliProcessRunner`。

### 5.2 Provider 差异的落点

- Codex/Claude CLI：`CliAgentRuntime.start(plan, sink)` 按 `AgentType` 选择现有 `CliDialect`，把完整 Plan 交给 `AgentProcessKernel`；不要在应用层增加 `if (CODEX)`/`if (CLAUDE)`。
- CLI 方言：现有 `CliDialect`（Codex/Claude）负责命令、Resume 参数、session ID 提取和回合结束判断；Codex 的 `RuntimeCommandFactory` 作为 provider 内部命令协作者保留，Claude 由 `ClaudeCliDialect` 构造命令。`RuntimeEventDecoder` 继续负责公共事件脱敏、限长和 Workbench 语义投影：CODEX 使用现有 `CodexEventNormalizer`，非 CODEX（当前为 Claude）使用所选方言的归一化方法。两者共用同一进程生命周期，不形成第二套 Gateway。
- `CliAgentRuntime` 从 `RuntimeSelection` 消费 endpoint、model、reasoning；endpoint/model/reasoning 通过方言支持的受控参数或环境变量传入，Key 按 `profileId` 从已加载的 `data/secrets.properties` Profile 配置读取，由 Kernel 在启动前注入，绝不进入 `AgentRunInvocation.extraEnv`。
- NATIVE：`NativeDiagnosisAgentRuntime.start(plan, sink)` 直接管理 AgentKit engine，不经过 `AgentProcessKernel`；其配置边界和句柄规则见 §5.3。
- Resume：`AgentExecutionPlan.resumeId` 直接传给 CLI 方言或 NATIVE 请求。首期 Resume 使用 Run 持久化的 `RuntimeSelection` 和同一 `resumeId`，需要改变模型或思考强度时新建 Run，不对象化 `ContinuationPolicy`。

### 5.3 NATIVE 句柄与终态映射

NATIVE Profile 与现有 `NativeDiagnosisProperties` 分工明确，不能同时保存同一组 Provider 连接字段：

| 配置 | 保留内容 | 不再作为 NATIVE Profile 的来源 |
| --- | --- | --- |
| `AgentRuntimeProfile`（`data/secrets.properties`） | `profileId`、`endpoint`、`apiKey`、已解析 model、`runtimeEnvironment`、Surface/RunMode policy | — |
| `NativeDiagnosisProperties`（`agent.native.*`） | AgentKit 引擎级配置：budget、tools、backends、checkpoint、超时和其他 engine options | `apiKey`、`baseUrl`、`model`、`boundEnvironment` |

`NativeDiagnosisAgentRuntime.start(plan, sink)` 已从 Plan 的 `RuntimeSelection` 读取 endpoint、model 和 `runtimeEnvironment`，并按 `profileId + model` 路由到 Profile-specific AgentKit Engine；Profile API Key 在配置装配时用于构建对应 Engine，不进入 Run。`NativeDiagnosisProperties` 只保留 budget、tools、backends、checkpoint、超时等引擎级配置。若旧 `agent.native.api-key`、`base-url`、`model` 与选定 Profile 同时配置且值不同，配置装配 fail-closed；一致时允许迁移期兼容，后续可删除旧连接字段。当前 AgentKit 的 `RunRequest`/`AppConfig` 没有 reasoning-effort 参数，因此 NATIVE 使用 Profile 默认值；调用方提交不同 reasoning override 时在 Profile 选择阶段拒绝，不静默忽略。公共 Handle/Observe/Stop/终态映射已接入，但真实 AgentKit endpoint/key E2E 仍待受控验收。

`NativeDiagnosisAgentRuntime` 使用公共 Runtime 自有的显式句柄状态表，不再维护旧 Invocation 路径的 `activeEngines`：

```text
executionId (= plan.executionIdentity.executionId)
        └── RuntimeHandle(handleId = generated UUID)
                └── NativeExecution(engine, state, outputBytes, termination)
```

- `start(plan, sink)` 生成 `handleId`，以 `executionId` 注册 `NativeExecution`，把 `DiagnoseEngine.run` 提交到 NATIVE Runtime 的执行器后立即返回 Handle；返回的 `RuntimeHandle` 与 `ChatRunRuntimeHandleStore` 持久化绑定，Engine 回调异步更新状态。
- `requestStop(handle)` 先按 `handleId` 找到 `NativeExecution`，原子地标记 `STOP_REQUESTED`，再调用 `engine.stop(executionId)`；找不到句柄返回幂等成功或由 Gateway 返回 `NOT_FOUND`，不按环境猜测 Engine。
- `observe(handle)` 读取同一状态表：运行中返回 `RUNNING`/`STOP_REQUESTED`，回调完成后返回 `TERMINATED`；未知句柄返回 `NOT_FOUND`。
- Engine completion callback 删除 `activeEngines` 中的 engine，同时写入 `RuntimeTermination`：正常 summary → `exitCode=0, COMPLETED`，主动停止 → `exitCode=-1, REQUESTED_STOP`，异常 → `exitCode=-1, PROCESS_FAILURE`。状态表保留到 Gateway 的终态缓存淘汰，保证恢复服务可观察已结束 Run。
- NATIVE 不伪造 CLI Chunk；它只通过 `RuntimeEventSink` 发出统一的 `STARTED`、诊断输出和终态事件。

### 5.4 CLI 迁移的具体拆分

首期不把 `AgentProcessKernel` 拆成新的公开组件，而是收窄现有类的依赖方向：

```text
RoutingAgentGateway.start(plan, sink)
        → AgentRuntime.start(plan, sink)
        → CliAgentRuntime 选择 CliDialect
        → AgentProcessKernel.start(plan, dialect, sink)
             1. materialize workspace/capability
             2. dialect.buildCommand(...)
             3. spawn / write stdin / monitor stdout
             4. dialect.extractResumeId / normalizeChunk / isTurnEnd
             5. watchdog / stop / cleanup
```

- Kernel 在迁移期提供两个入口，但只保留一个私有 spawn/monitor 循环：新入口 `start(plan, dialect, sink)` 消费完整 Plan；兼容入口 `runLegacy(invocation, dialect, callbacks)` 消费旧 Invocation 并使用旧路径已有的 workingDir/timeout/extraEnv。二者在 `ProcessBuilder.start()` 前汇合。Phase 3 已删除 `AgentCliGateway` façade 和 `AgentRuntime` 上的旧 Invocation 方法；`runLegacy` 目前只由 `CliAgentRuntime` 的显式 `AgentGateway` 回滚端口调用。
- Workspace 和 Capability materialization 继续留在 `AgentProcessKernel`，因为它们消费完整 `AgentExecutionPlan`，且 `RoutingAgentGateway` 不应知道这些边界。
- `CliDialect` 是 Claude 命令、目标 Credential 环境变量、Resume 和 Provider 事件解析的事实来源；Codex 的 sandbox/capability/workspace token 仍由现有 `RuntimeCommandFactory` 作为 provider 内部命令协作者生成，Claude 由 `ClaudeCliDialect` 构造命令。两者都不拥有进程生命周期，也不构成第二套 Gateway。扩展现有 `BuildContext`，加入 endpoint、reasoning、已 materialize 的 workspace/capability 参数，禁止放入 Key 值。
- 在现有 `CliDialect` 五个方法基础上新增 `credentialEnvironmentVariable()`：`CodexCliDialect` 返回 `OPENAI_API_KEY`，`ClaudeCliDialect` 返回 `ANTHROPIC_API_KEY`；该方法只返回目标变量名，不返回 Key 值。
- `RuntimeEventDecoder` 不删除：它继续负责输出脱敏、长度限制、命令安全判断和 Workbench 语义事件。CODEX 继续复用 `CodexEventNormalizer`，Claude 通过 `CliDialect.normalizeChunk` 做基础识别；`normalizeChunk` 等旧方言能力只服务 `CliAgentRuntime` 的兼容端口。后续若要把 Provider 事件完全统一为内部值，应另行评估，不作为首期新增公共抽象。
- `CliAgentRuntime.start(plan, sink)` 是新公共路径的唯一 CLI 入口：它不重新解析或选择 Profile，也不拼业务 Prompt；它从启动时加载的 Profile 配置索引按 `plan.runtimeSelection.profileId` 读取当前 API Key，选择方言并把 Key/完整 Plan 交给 Kernel。Profile 文件加载、权限校验和候选选择仍由配置组件与 Runtime 应用层负责。
- `CliAgentRuntime` 的 `AgentGateway.runStream(...)`/`runStreamWithResult(...)` 是旧兼容入口，内部调用同一个 Kernel 和同一套 `CliDialect`；`AgentRuntime` 本身不再声明 Invocation 方法，`AgentCliGateway` façade 已删除。
- Kernel 维护底层进程注册表；`RoutingAgentGateway` 维护 `executionId → AgentRuntime` 和 Provider RuntimeHandle 路由；不再存在独立的 `AgentCliGateway.runningProcesses` 或第二个 Gateway 路由表。

因此迁移期只在明确标注的 `AgentGateway` 回滚端口保留旧方法签名，不保留两套 CLI 命令构造、事件解码或进程生命周期实现。

改造成本按组件明确如下：

| 组件 | 改造 | 成本/主要风险 |
| --- | --- | --- |
| `CliAgentRuntime` | 注入已加载的 Profile 配置索引；按 `profileId` 读取 API Key，增加 `start(plan, sink)`，选择方言并委托 Kernel；在兼容窗口内实现旧 `AgentGateway` 端口 | 中；自身不重写进程循环，单测需覆盖公共 Plan 与兼容 Invocation 两条入口 |
| `AgentProcessKernel` | 去掉公共 Gateway Bean 身份，接收方言；保留 materialize/process/cleanup | 中高；Stop、超时、事件顺序必须回归 |
| `AgentCliGateway` | Phase 3 已删除 façade；兼容执行由 `CliAgentRuntime` 的显式 `AgentGateway` 端口承载 | 已完成；后续只需完成旧消费者迁移和回滚窗口验收 |
| `CliDialect/BuildContext` | 增加 endpoint、reasoning、workspace/capability 和 Runtime 事件解析；新增 `credentialEnvironmentVariable()`，两个方言分别返回 `OPENAI_API_KEY`/`ANTHROPIC_API_KEY` | 中；Codex/Claude 命令兼容 |
| `RuntimeEventDecoder` | 保留 CODEX `CodexEventNormalizer` 和 Claude 方言归一化，统一做脱敏、限长、命令安全与 Workbench 语义投影 | 中；Workbench 语义事件不能回退 |
| Profile 配置加载/索引 | 启动时读取 `data/secrets.properties`，校验权限和 Profile 唯一性；要求显式 API Key 的 Profile 再校验 Key 存在性，允许 CLI 本机登录态的 Profile 不配置 Key；按 `profileId` 提供内存查询 | 中；不得把 Key 投影到 Run 或日志 |
| `RoutingAgentGateway` | 增加新 `AgentExecutionGateway` 路由和 RuntimeHandle 所属 Runtime 映射 | 中；不得持有进程和 Workspace 细节 |

Phase 1 的完成门禁是 CLI 不再拥有重复进程循环和 `runningProcesses`，且 Chat 的公共 Handle Stop、Handle 绑定前取消和恢复路径均通过 `AgentExecutionGateway` 验收。Phase 3 进一步删除了 `AgentCliGateway` façade、`AgentRuntime.run(AgentRunInvocation, ...)` 以及 Chat Stop 的 legacy fallback；旧 `AgentGateway` 端口仅保留给尚未迁移的显式 rollback 消费者。

## 6. Workbench 边界

Workbench Stage 只表达自身语义：

```text
AgentType + RunMode + Capability policy
Repository/Workspace scope + Sandbox policy + Attachment policy
```

Stage 不保存 `allowedRuntimeProfiles`，也不直接依赖 Runtime Profile ID。Workbench 计划 Provider 将 Stage 语义传给 Runtime 应用层，由 Runtime 选择满足约束的 Profile：

```text
Stage(agentType, runMode, surface=WORKBENCH)
        → Profile(agentType 相同且支持 WORKBENCH/runMode)
        → AgentExecutionPlan
```

若 HTTP 请求显式传 `profileId`，仍由 Runtime 层完成一致性校验；Workbench 只负责自己的仓库、沙箱、附件和高影响命令策略。`AgentOfferPolicy.supportsSurface` 是 AgentType 级产品开关，不能被 Profile 选择绕过。NATIVE 继续 Chat-only。

Claude 首次进入 Workbench Write Run 前必须验证：workspace scope、sandbox、MCP/工具清单、附件读取权限和高影响命令确认。这个验证属于 Workbench 计划 Provider/Capability policy，不下沉到 `AgentRuntime`。

## 7. 幂等、失败与恢复

### 7.1 幂等

`Idempotency-Key` 的语义是“同一个意图的网络重试只创建一个 Run”。提交时先按调用方和会话/Stage 查找既有 Run；命中后直接返回既有 Run 及其已持久化的 `RuntimeSelection`，不重新解析当前 Profile。

如需校验请求是否改变了显式语义，`requestHash` 只覆盖调用方显式提交的业务字段：

- message、runMode、attachments；
- 显式 `profileId`（若 API 暴露）；
- 显式 model、显式 reasoningEffort。

不覆盖解析后的 endpoint、Profile 中的 Key 值、Secret 指纹或版本。Profile 内部事实变化不应使网络重试变成冲突。若同一 Key 改了显式 model/reasoning，则拒绝为同一幂等操作改变请求语义，并返回已有 Run 或冲突，具体以接口约定为准。

Chat 当前已有按 `sessionId + idempotencyKey` 查重；Workbench 已有 request hash 快照。统一时复用现有存储，不为 Runtime 另建幂等表。

### 7.2 首期错误码

只定义首期可观测且可处理的四类错误：

| 错误码 | 触发 |
| --- | --- |
| `RUNTIME_PROFILE_NOT_FOUND` | 指定 Profile 不存在、停用或没有唯一候选 |
| `RUNTIME_ADAPTER_UNAVAILABLE` | `AgentType` 没有已注册的 `AgentRuntime` |
| `RUNTIME_PREFLIGHT_FAILED` | endpoint、CLI 版本、模型或 Workbench 能力预检失败 |
| `RUNTIME_START_FAILED` | 进程/Native Run 无法启动 |

能力不支持、凭据读取失败等首期场景映射到上述错误并保留内部原因；待真实客户端需要稳定区分时再增加错误码。

### 7.3 Stop、Observe、恢复

- 提交事务成功后才调用 `AgentExecutionGateway.start`；启动失败更新 Run 终态，不回退到旧链路。
- Chat 公共 Runtime 的 Stop 路径必须与 Start 路径使用同一端口：`ChatRunAppServiceImpl.stop()`、`ChatRunRuntimeLauncher` 的取消竞态和 Chat 恢复停止优先读取 `ChatRunRuntimeHandleStore` 后调用 `AgentExecutionGateway.requestStop(handle)`。Handle 未绑定时只持久化取消状态，绑定后由 launcher 补发 Stop；Handle 已终止时由公共 Observe/终态收口。Chat 应用服务不再回退到 `AgentGateway.stopStream(runId)`。默认开关仍为 `false` 时，legacy `ChatRunExecutor` 的事件缓冲区和异常收口仍可调用兼容 `AgentGateway.stopStream(runId)`；切换 `AGENT_COMMON_RUNTIME_CHAT_ENABLED=true` 后，新建 Chat Run 不再进入该 legacy 分支。Workbench 已由 `WorkbenchRunCancellationCoordinator` 使用同一端口。
- Stop 与 Start 的竞态按 Run 状态和持久化 Handle 处理：Stop 提交后若尚无 Handle，只持久化取消状态；`ChatRunRuntimeLauncher` 在 `start` 前检查取消状态，在 `start` 后绑定 Handle 并再次检查，发现已取消立即调用 `requestStop(handle)`。因此不依赖另一套 `AgentGateway` pending cancellation，也不会因 Handle 尚未写入而漏停。
- 恢复服务只读取 Run 的 Handle 和已持久化的 `RuntimeSelection`，不重新选择 Profile，不读取当前默认模型；下一次 Resume 由 Chat session continuation 或 Workbench continuation 存储提供 `resumeId`，再由对应 Plan Provider 写入 Plan。
- `observe` 对已终止句柄返回终态；句柄丢失时由 Run 事件/终态记录进行补偿，而不是再次启动 Provider。

## 8. 配置与安全

首期 Profile 配置固定放在 Git 忽略的 `data/secrets.properties`，其中同时保存 endpoint、Key、模型和思考强度策略。`application.yml` 不保存 Profile 内容；Runtime 配置组件在服务启动时按固定路径加载一次，不做文件热更新，修改后通过受控重启生效。管理员 UI、配置数据库和外部 Secret Store 均不在首期范围；未来只有在需要在线管理、审计或多租户隔离时，才单独设计从本地文件迁移到数据库的方案。

安全约束：

1. 普通调用方只能选择已发布 Profile 和允许列表中的模型/思考强度。
2. endpoint 必须命中受信任地址策略（至少禁止本地回环、链接本地和任意内网探测，具体由部署环境配置）。
3. 显式配置的 API Key 只从 `data/secrets.properties` 读取，日志只能记录 Profile ID 和“是否存在”，禁止打印 Key 值；未配置 Key 的 CLI Profile 继续使用 CLI 本机默认登录态。
4. Run 上持久化的 `RuntimeSelection`、事件、SSE 和异常消息不得包含 Key。
5. Profile 禁用只影响新 Run；已持久化 `RuntimeSelection` 的 Run 继续使用原选择，但进程启动仍需通过当前安全开关和额度检查。

## 9. 迁移计划

### Phase 0：基线和契约（小步完成）

- 扩展现有 `RuntimeSelection`：加入 `profileId`、`endpoint`、已解析 `model`、已解析 `reasoningEffort`、`runtimeEnvironment`，保留 `agentType` 和 `runtimeVersionPolicy`；不放 `apiKey`，直接把非秘密选择持久化到 Run 执行输入，不新建 BindingSnapshot 或 bindingHash。
- 扩展 `ExecutionIdentity` 的 `conversationId`、`userMessageId`，在 `AgentExecutionPlan` 增加可空 `resumeId`，在 `PromptPayload` 增加可选 `typedHistory`。
- 扩展现有 `AgentRuntime` 的 `start(plan, sink)`、`requestStop(handle)`、`observe(handle)`；旧 Invocation 方法不属于该公共契约，Phase 3 已删除。
- 定义最小 `AgentRuntimeProfile` 配置、Profile 文件加载/索引和 Run 上 `RuntimeSelection` 的持久化映射；Profile 索引同时提供按 `profileId` 读取当前 API Key 的单一内部入口，不引入 CredentialResolver。
- 扩展现有 `CliDialect`，新增 `credentialEnvironmentVariable()`，并在 Codex/Claude 两个实现中固定目标变量名；补充方言方法单测。
- 明确四个错误码和幂等字段。
- 将 Chat 的 Stop 调用从 `AgentGateway.stopStream(runId)` 切换为 `ChatRunRuntimeHandleStore + AgentExecutionGateway.requestStop(handle)`，并补上 Handle 尚未绑定时的“取消状态 + 启动后再次检查”竞态测试。
- 保持现有 Codex 行为不变，补充 Profile 选择、Run Selection 恢复、Resume 字段和历史投递测试。

### Phase 1：Codex 走 Profile 和公共计划

- `ChatExecutionPlanProvider`、`WorkbenchExecutionPlanProvider` 从 Run 持久化的 `RuntimeSelection` 和执行输入组装完整 Plan；删除 Codex-only 守卫，改为按 Profile/AgentType 校验。
- 将 `RoutingAgentGateway` 扩展为唯一的 `AgentExecutionGateway` 实现：它直接把完整 Plan 路由到 `AgentRuntime.start`，并把 Stop/Observe 路由回拥有该 Handle 的 Runtime；不再做 Plan → Invocation 转换。
- 修改 `CommonRuntimeConfiguration`：不再把 `AgentProcessKernel` 暴露为 `AgentExecutionGateway`；Kernel 只由 `CliAgentRuntime` 调用。
- 保留 `RuntimeCommandFactory` 作为 Codex provider 内部命令协作者，扩展 `CliDialect/BuildContext` 处理 Claude 命令和公共运行参数；它不持有进程生命周期。保留 `RuntimeEventDecoder` 的公共安全投影，CODEX 复用 `CodexEventNormalizer`、Claude 使用方言归一化。Phase 3 已删除 `AgentCliGateway` façade；`CliAgentRuntime` 的显式 `AgentGateway` 回滚端口继续委托同一个 Kernel，旧 `runningProcesses` 和重复 watchdog 均不恢复；Codex 结果保持兼容。
- `agent.runtime.chat-enabled` 的 `application.yml` 默认值当前为 `true`，可通过 `AGENT_COMMON_RUNTIME_CHAT_ENABLED=false` 受控回滚；`application-e2e-workbench.yml` 等测试配置继续显式保持 `false`。真实 CLI/endpoint 验收仍需在受控环境完成后再关闭回滚窗口。

### Phase 2：Claude CLI（代码已接入，待真实验收）

- 通过现有 `ClaudeCliDialect` 完成 Claude 的命令、环境、Credential 目标变量和事件方言；`CliAgentRuntime.start(plan, sink)` 与 Codex 共用 `AgentProcessKernel`，不新增 Adapter 接口或第二套进程循环。代码路径已接入，仍需使用受控 CLI stub/真实 CLI 完成协议与 endpoint/key 验收。
- 增加 Claude Profile、Chat 预检和最小 Chat Run 验收。
- Claude Workbench Read/Write 只有在 Capability/Sandbox 验收通过后分别开放。

### Phase 3：NATIVE 和旧链路收口（旧链路代码已完成，真实验收待完成）

- 将已接入的 `NativeDiagnosisAgentRuntime.start(plan, sink)` 完成真实 AgentKit endpoint/key 验收；Profile 提供 endpoint/apiKey/model/runtimeEnvironment，`NativeDiagnosisProperties` 只保留引擎级配置；保持 `AgentOfferPolicy` 的 Chat-only 限制。NATIVE reasoning override 在 AgentKit 增加参数前继续 fail-closed。
- 已删除 `AgentCliGateway` façade 和旧 `AgentRuntime.run(AgentRunInvocation, ...)` 方法；Chat Stop 已移除对 `AgentGateway.stopStream` 的隐式 fallback。兼容开关和 `CliAgentRuntime` 的显式 `AgentGateway` 端口仅用于受控回滚，不改变公共 Runtime 主链。
- 已从公共 `AgentRuntime` 契约删除旧 `normalizeChunk/extractResumeId` 等兼容入口；CLI 兼容端口仍可在 `CliAgentRuntime` 内部使用 Provider 方言和 `AgentProcessKernel.runLegacy`。重复进程管理代码不恢复，Provider-neutral 的 `RuntimeEventDecoder` 保留。

## 10. 实施验收

首期只验收真实要交付的路径：

1. 同一 `AgentType` 在 `data/secrets.properties` 配置两个 Profile，分别使用不同 endpoint/Key；同一 API 可按 `profileId` 启动两个 Run，Run 上持久化的 `RuntimeSelection` 互不覆盖且不包含 Key。
2. Profile 默认模型/思考强度可用；允许列表外的单次 override 被拒绝；允许列表内 override 进入 Run 的 `RuntimeSelection`。
3. Profile 修改或禁用后，已提交 Run 的启动、Stop、Observe、恢复仍使用 Run 上原有的 `RuntimeSelection`；新 Run 按新配置选择。
4. 在 `AGENT_COMMON_RUNTIME_CHAT_ENABLED=true` 的受控环境中，Codex Chat 和 Workbench 通过 `AgentExecutionGateway` 成功启动、流式事件、停止和终态落库；默认值翻转前完成该验收。
5. Claude Chat 通过同一生命周期端口启动并解码最小事件；Claude Workbench 按能力开关验证后再开放。
6. NATIVE Chat 通过公共终态/事件契约可观察，Workbench 仍被 `AgentOfferPolicy` 拒绝。
7. 网络重试命中同一幂等 Run；改变 endpoint/Key 等 Profile 内部事实不会制造 request hash 冲突，也不会泄漏 Secret。
8. Chat 在 Handle 绑定前、绑定后和进程已终止三种时序调用 Stop，均只通过 `AgentExecutionGateway`，不会回退到 `AgentGateway.stopStream`，且不会遗留活动进程。
9. 受控回滚端口的 `CliAgentRuntime` 兼容调用和新 `CliAgentRuntime.start(plan, sink)` 共用 `AgentProcessKernel` 与 `CliDialect`，不存在第二个 `AgentCliGateway`、`ProcessBuilder`/watchdog/process map。
10. NATIVE 验证 `executionId → handleId → engine` 映射、Stop、Observe、完成回调和异常终态；JVM 重启后内存 Handle 返回 `NOT_FOUND`，由现有恢复规则中断并清理持久化 Handle。

本设计不要求首期完成 Profile 管理 UI、外部 Secret Store、Revision 历史、跨 Provider Resume、事件总线或完整能力矩阵。它们只有在对应产品需求出现并能给出消费方、数据保留和运维成本后再单独评审。

## 11. Review 修正对照

| Review 问题 | 本版处理 |
| --- | --- |
| 忽略已有 Adapter | 明确认定 `AgentRuntime`/`RoutingAgentGateway` 为现有 Adapter/路由原型，删除平行接口 |
| Gateway 形状重复 | `AgentExecutionGateway` 只作为迁移期应用端口；它由 `RoutingAgentGateway` 路由到扩展后的现有 `AgentRuntime.start/stop/observe`，新路径不再复制 Plan → Invocation |
| ProcessKernel 与 RoutingGateway 有两条路 | 明确最终只走 `RoutingAgentGateway` 实现 `AgentExecutionGateway`；`AgentProcessKernel` 只被 CLI Runtime 使用，不作为第二个 Gateway |
| Plan → Invocation 信息缺口 | 新公共路径取消该转换，直接把完整 `AgentExecutionPlan` 传给扩展后的 `AgentRuntime`；旧 Invocation 仅保留兼容路径 |
| RuntimeSelection 字段不清 | 增加字段归属表，并明确首期只扩展现有 `RuntimeSelection`，不新建平行输入模型 |
| CLI Key 与现有登录态策略冲突 | 明确认定 Profile `api-key` 是有意的鉴权策略变更；Phase 0/1 同步更新 `application.yml` 和 `data/secrets.properties` 注释，仍禁止读取用户级 CLI 认证目录 |
| NATIVE Profile 与 `NativeDiagnosisProperties` 重叠 | Profile 负责 endpoint/apiKey/model/runtimeEnvironment；`agent.native.*` 只保留 budget/tools/backends 等引擎配置，冲突 fail-closed |
| `CliDialect.credentialEnvironmentVariable()` 未列入变更 | Phase 0 扩展接口和 Codex/Claude 实现，并增加方法单测 |
| CliAgentRuntime Profile 依赖未估 | 成本表增加 Profile 配置索引注入、Key 查询和模拟测试 |
| chat-enabled 默认翻转时机不明 | `application.yml` 当前默认值为 true；通过 `AGENT_COMMON_RUNTIME_CHAT_ENABLED=false` 保留受控回滚，真实 CLI 验收完成后关闭回滚窗口 |
| Chat Start/Stop 分属两套端口 | Phase 0 将 Chat 停止统一到 HandleStore + `AgentExecutionGateway.requestStop`，并明确 Handle 绑定前后的取消竞态 |
| ProcessKernel 拆分不清 | Workspace/Capability 留在 Kernel；`CliDialect` 成为命令/事件唯一事实；Routing 只路由 Run，不参与 materialization |
| CliAgentRuntime 改造成本不明 | 新 `start(plan, sink)` 选择方言并调用 Kernel；Phase 3 已删除 `AgentRuntime.run(invocation)` 和 `AgentCliGateway`，旧 `AgentGateway` 兼容端口明确留在 `CliAgentRuntime` 回滚窗口内 |
| NATIVE Handle 一句话带过 | 明确 executionId/handleId/engine 状态表、Stop、Observe 和终态映射 |
| resumeId 缺位 | 在 `AgentExecutionPlan` 增加 `resumeId`，明确 Chat/Workbench 来源、Provider 消费和新 Resume ID 回写 |
| Profile Revision/Binding 类型过度 | 直接在 Run 执行输入中持久化扩展后的 `RuntimeSelection`，不新建 Binding 类型或 bindingHash |
| Credential 三层过早 | 首期直接从 `data/secrets.properties` 加载 Key，按 Profile ID 在启动前注入，不引入 Resolver/Lease/Secret Store |
| Workbench 依赖 Profile ID | Stage 只保存 AgentType/RunMode/能力策略，由 Runtime 解析 Profile |
| 幂等 Hash 过宽 | 排除 endpoint、Key、指纹和版本；只校验调用方显式业务语义 |
| 多 Profile 选择过度 | 保留 fail-closed 安全守卫，首期只做候选计数，不实现排序、权重或专门选择算法 |
| 错误码、Capabilities、AgentInput、ContinuationPolicy 过多 | 首期分别收敛为四个错误码、最小 Profile policy、增量扩展 PromptPayload；Resume 使用 Run Selection + Plan.resumeId |
| GenericCliProcessRunner 重复 | 直接复用 `AgentProcessKernel`，只抽 Provider 纯协作者 |
| DDD 事件/评分/大矩阵 | 删除仪式性章节，验收仅覆盖当前三条真实迁移路径 |
