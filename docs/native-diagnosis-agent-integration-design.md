# NATIVE 进程内诊断 Agent 暴露与集成详细设计

> 状态：已实施，并完成自动化、制品和真实 OpenAI-compatible Provider 验证
> 日期：2026-07-30
> 适用范围：用户在普通聊天入口选择 NATIVE，以进程内 JAR 运行只读诊断 Agent
> 非目标：替代 Coding Agent、开放写工具、让 NATIVE 成为全局默认

## 1. 摘要

本方案把 agent-langchain4j 中已经存在的诊断引擎作为普通 Maven JAR 集成到
agent-web，而不是把 agentkit-cli 当作子进程启动。

第一阶段的产品语义明确为：

1. 用户可以在桌面端和移动端普通聊天入口手动选择 NATIVE。
2. 用户看到的名称是“诊断 Agent”，NATIVE 仍是后端和持久化中的稳定类型标识。
3. NATIVE 只开放给普通聊天，不允许成为全局默认，也不进入定时任务、Workflow
   和 Knowledge Refinery 的运行时选择。
4. NATIVE 只有在运行时启用、配置合法且目标环境受支持时才允许创建新会话。
5. 已存在的 NATIVE 会话即使运行时后来被关闭，历史仍可读取、分享和反馈；继续发送时给出
   明确的“当前不可用”提示。
6. ChatSession 创建后继续绑定创建时选定的 AgentType，不允许在同一会话中切换运行时。

技术上保留现有 ChatRun、SSE 回放、StreamChunkHandler 和工具调用投影主链路，只在
AgentGateway 后增加运行时路由：

~~~text
AgentGateway
  └── RoutingAgentGateway
      ├── CLI runtime             -> CODEX / CLAUDE
      └── NativeDiagnosisRuntime  -> DiagnoseEngine
~~~

agent-web 直接依赖以下正式 artifact：

~~~xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>agentkit-agent-diagnosis</artifactId>
    <version>${agentkit.version}</version>
</dependency>
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>agentkit-kernel</artifactId>
    <version>${agentkit.version}</version>
</dependency>
~~~

diagnosis artifact 本身也会传递引入 kernel；由于宿主配置适配器直接使用 kernel 的
`AppConfig`、`AgentBudget` 等类型，agent-web 同时显式声明 kernel，避免依赖传递实现细节。
Spring Boot repackage 后二者位于同一个 agent-web 可执行 JAR 的 BOOT-INF/lib 中；不依赖
agentkit-cli，不使用 systemPath，不复制本地 JAR，也不增加 CLI 进程协议。

本设计最重要的两个一致性决定是：

- NATIVE 的多轮上下文使用 RunRequest.history 和诊断状态 checkpoint，不再把同一份历史
  拼进 prompt，避免模型重复看到历史。
- checkpoint 不按 session 保存“最新可变状态”，而是按已成功 ChatRun 的消息边界追加。
  assistant 消息、ChatRun 成功终态和 checkpoint 在一个事务中提交；会话回退后，属于已删除
  消息边界的 checkpoint 必须不可再被读取。

## 2. 目标与非目标

### 2.1 目标

1. 用进程内 Java API 集成诊断引擎，部署物仍是单个 agent-web.jar。
2. NATIVE 复用已有的会话创建、ChatRun Submit、SSE 重连、停止、历史、回退、分享、
   反馈和管理端会话查询。
3. CODEX、CLAUDE 的 CLI 执行和 resume 行为保持兼容。
4. 将“已知 Agent 类型”“产品暴露策略”“能否成为默认”“运行时是否可用”拆成不同概念，
   不再由 AgentType 上单一“可选择”布尔值表达全部规则。
5. 让应用层只依赖 provider-neutral port 和 DTO，AgentKit 类型只能出现在 Spring 装配与
   NATIVE 基础设施适配器中。
6. 保持 Claude-compatible stream-json 事件契约，使当前前端和工具调用跟踪器可以继续工作。
7. 精确保留 RunSummary 中的退出原因、诊断状态和 usage，并以一致的 ChatRun 终态落库。
8. 保证取消、超时、服务关闭、回退会话和运行时关闭场景下没有状态串线。
9. 以显式只读后端、最小权限凭证和网络 allowlist 约束诊断工具。

### 2.2 非目标

1. 第一阶段不让 NATIVE 成为全局默认 Agent。
2. 第一阶段不在定时任务、可配置 Workflow 或 Refinery scoring 中开放
   NATIVE。
3. 不把 AgentKit 改造成 Spring 组件；Spring 生命周期仅由 agent-web 的装配层管理。
4. 不引入独立 AgentKit Server、RPC、插件扫描或 CLI 子进程。
5. 不承诺多实例 ChatRun 接管；agent-web 当前仍按单实例运行。
6. 不承诺 Spring Boot 重启后恢复正在执行的进程内推理；遗留活动 ChatRun 仍由现有恢复器
   标记为 INTERRUPTED。
7. 第一阶段不开放写文件、写数据库、执行任意 shell 或任意目标网络请求。
8. 第一阶段不做每次选择前的模型 Provider 在线探活；available 表示本地运行时已注册且配置
   通过，不代表外部 Provider 此刻一定健康。
9. 第一阶段只绑定一个明确的诊断环境；多环境后端路由见第 13.4 节的后续演进。

## 3. 改造前现状审计

### 3.1 agent-langchain4j 已经是正式的进程内 JAR

agent-langchain4j 当前 Maven 模块为：

| Artifact | 版本 | 职责 | agent-web 是否依赖 |
| --- | --- | --- | --- |
| com.anthropic:agentkit-kernel | 0.2.1 | 通用 Agent 内核、LLM、工具治理、stream-json | 是，配置适配器直接依赖 |
| com.anthropic:agentkit-agent-diagnosis | 0.2.1 | 诊断领域、DiagnoseEngine 门面 | 是，直接依赖 |
| com.anthropic:agentkit-agent-coding | 0.2.1 | Coding Agent | 否 |
| com.anthropic:agentkit-cli | 0.2.1 | 调试壳 | 否 |

宿主公开入口已经存在：

~~~java
DiagnoseEngine.run(
    RunRequest request,
    Consumer<String> onChunk,
    Consumer<RunSummary> onComplete
);
DiagnoseEngine.stop(String sessionId);
DiagnoseEngine.isRunning(String sessionId);
DiagnoseEngine.close();
~~~

其中 run 是阻塞调用，agent-web 应在已有 ChatRun 后台执行线程中调用；onChunk 每次收到一条
Claude-compatible stream-json，onComplete 收到一次结构化 RunSummary。

RunRequest 已包含：

- workingDir；
- userMessage；
- sessionId；
- env；
- timeoutSeconds；
- 类型化 history；
- stateSnapshot。

RunSummary 已包含：

- ExitReason：SUCCESS、STOPPED、TIMEOUT、ERROR、REJECTED；
- 下一轮可恢复的 stateSnapshot；
- input/output/cache-read token usage；
- errorDetail。

因此缺口不在 AgentKit 再增加 CLI，而在 agent-web 正确装配、路由和持久化这些公开语义。

### 3.2 旧的可选源码/profile 集成已经移除

实施前曾用失效的 SNAPSHOT 坐标、额外源码目录和 Maven profile 表达进程内诊断能力。这既不是
当前真实 artifact，也把“编译期依赖”和“运行时是否向用户开放”混在一起。实施后已删除旧
profile 与 build-helper source roots，改为普通正式 JAR 依赖，并只由 `agent.native.enabled`
控制运行时暴露。

### 3.3 当前选择模型不足以表达 NATIVE

AgentType 已有 CODEX、CLAUDE、NATIVE，但：

- AgentType 上旧的单一选择标记对 NATIVE 返回 false；
- 旧的选择解析函数拒绝 NATIVE；
- RuntimeAgentSettings 使用同一个旧解析函数读取全局默认；
- AdminSettingsController 从同一选择标记生成默认模型选项；
- 桌面端和移动端在 App.vue 中硬编码 CODEX、CLAUDE 两个单选项。

直接把旧选择标记改成始终返回 true 会同时让 NATIVE 进入全局默认、后台流程和其他原本只
支持 CLI 的入口，属于错误的概念合并。

### 3.4 当前执行链路

~~~mermaid
flowchart LR
    UI[App.vue / chat-panel.vue] --> CC[ChatController]
    CC --> CS[ChatSession]
    UI --> CRC[ChatRunController]
    CRC --> CRS[ChatRunAppServiceImpl]
    CRS --> CRE[ChatRunExecutor]
    CRE --> AG[AgentGateway]
    AG --> CLI[AgentCliGateway]
    CRE --> SCH[StreamChunkHandler]
    SCH --> EV[ChatRunEvent / SSE]
    CRE --> LC[ChatRunLifecycleService]
    LC --> DB[(SQLite)]
~~~

ChatRunExecutionContext 已经提供 runId、逻辑 sessionId、userMessageId、AgentType、
workingDir、resumeId、env、userId、当前消息和 prior history，足够形成结构化执行请求。

当前 AgentGateway.runStreamWithResult 是宽参数方法，而且把 runId 填到名为 sessionId 的
参数中，用于进程追踪和 stop。接入第二类运行时后必须明确区分：

| 标识 | 语义 | 生命周期 | NATIVE 用法 |
| --- | --- | --- | --- |
| runId | 一次 ChatRun 执行 | 单回合 | 传给 RunRequest.sessionId，作为 stop key |
| conversationId | ChatSession.id | 多回合 | checkpoint/history 边界 |
| resumeId | CLI Provider thread id | Provider 决定 | NATIVE 永远不用 |
| userMessageId | 当前用户消息边界 | 单回合 | 查找前一有效 checkpoint |

### 3.5 当前历史和回退行为

没有 resumeId 时，ChatRunPromptBuilder 会把 prior history 拼为 conversation_history
前缀。NATIVE 已有类型化 RunRequest.history，如果同时保留前缀，模型会收到两份相同历史。

SqliteSessionRepo.truncateFrom 当前会删除 fromId 及之后的消息和 recall，并清空 CLI
resumeId。未来若只按 session 保存一个可变诊断快照，回退后仍可能恢复已删除轮次形成的证据、
假设和结论。因此 checkpoint 必须与消息边界绑定，并参与 truncate/delete 清理。

### 3.6 当前领域模型评分

评分范围 0～3，3 为目标状态。

| 维度 | 当前分数 | 判断依据 |
| --- | ---: | --- |
| 聚合边界清晰度 | 2 | ChatSession 和 ChatRun 已分离，但诊断状态还没有明确边界 |
| 变化收敛度 | 1 | 选择规则、默认规则和 CLI 运行能力仍由 AgentType 布尔值及宽 Gateway 混合表达 |
| 不变量保护 | 1 | NATIVE 只能靠全面拒绝避免越界，尚无按 surface、availability、env 的守卫 |
| 行为与模型一致度 | 1 | AgentType 已知 NATIVE，但真实行为又把它当作不存在 |
| 下一变化支持度 | 1 | 新增进程内运行时会迫使 CLI Gateway、prompt 和终态逻辑出现散落分支 |

## 4. 通用语言

| 名词 | 定义 |
| --- | --- |
| AgentType | 持久化稳定身份：CODEX、CLAUDE、NATIVE；只回答“它是谁” |
| AgentPurpose | 产品用途：GENERAL 或 DIAGNOSIS |
| AgentSurface | 允许出现的业务入口；第一阶段关注 CHAT、DEFAULT、SCHEDULE、WORKFLOW、REFINERY |
| AgentOffer | 某 AgentType 在一个 surface 上的有效产品报价，包含名称、用途、静态策略和运行时可用性 |
| AgentCatalog | 合并静态产品策略与已注册运行时能力后的查询与守卫 |
| Default Eligible | 是否允许作为全局默认；不等同于用户是否能手动选择 |
| Runtime Available | 对应 adapter 已注册、配置校验通过且支持目标环境；不是外部 Provider 在线探活 |
| AgentRuntime | 执行一种 AgentType 的基础设施适配器 |
| AgentRunInvocation | 一次运行的 provider-neutral 完整输入，明确区分 run、conversation、history 和 resume |
| Diagnosis Checkpoint | 一个成功 NATIVE ChatRun 在特定消息边界产生的不可变诊断状态 |
| Typed History | UserTurn、AssistantTurn、ToolResultTurn 组成的 AgentKit 历史，不是 prompt 文本前缀 |
| Stream Event | Agent 产生的一条 NDJSON；经规范化后作为 ChatRun chunk 投影和最终 assistant 原文 |
| Bound Environment | 第一阶段 NATIVE 后端唯一绑定的 agent.envs 环境 key |

## 5. 命令与事件

### 5.1 命令

| 命令 | 发起者 | 负责人 | 关键守卫或结果 |
| --- | --- | --- | --- |
| CreateChatSession | 用户 | ChatAppService | AgentOffer 对 CHAT 可选、运行时可用、环境匹配 |
| ChangeDefaultAgent | 管理员 | RuntimeAgentSettings | AgentOffer 必须 defaultEligible；NATIVE 被拒绝 |
| SubmitChatRun | 用户 | ChatRunAppService | 会话归属、运行时仍可用、每会话最多一个 active run |
| ExecuteAgentRun | 后台执行器 | RoutingAgentGateway | 按 AgentType 路由并注册 runId |
| RequestRunCancellation | 用户 | ChatRunAppService / Router | ChatRun 进入 CANCEL_REQUESTED，stop 到正确 runtime |
| CompleteChatRun | 后台执行器 | ChatRunLifecycleService | 消息、成功终态、可选 checkpoint 原子提交 |
| TruncateConversation | 用户 | ChatAppService | 删除消息边界后的 recall/checkpoint，清空 CLI resumeId |
| DeleteConversation | 用户 | ChatAppService | 删除该会话所有 checkpoint 后删除消息与会话 |
| CloseNativeRuntime | Spring 容器 | NativeDiagnosisConfiguration | 拒绝新 run、停止并 drain 活动 run |

### 5.2 业务事件

- ChatSessionCreated；
- ChatRunSubmitted；
- ChatRunStarted；
- ChatRunCancellationRequested；
- ChatRunSucceeded；
- ChatRunFailed；
- ChatRunCancelled；
- ChatRunInterrupted；
- DiagnosisCheckpointRecorded；
- ConversationTruncated。

这些事件描述业务状态。第一阶段仍按现有聚合与数据库事务落地，不要求新增通用事件总线。

### 5.3 技术流事件

现有 ChatRun SSE 事件保持不变：

| event | 持久化 | 说明 |
| --- | --- | --- |
| run_status | 是 | PENDING、RUNNING、CANCEL_REQUESTED |
| recall | 是 | 当前回合 RAG 投影 |
| chunk | 是 | 规范化 Agent NDJSON；NATIVE 为 Claude-compatible stream-json 直通 |
| terminal | 是 | SUCCEEDED、FAILED、CANCELLED、INTERRUPTED 及公开错误 |
| ping | 否 | SSE 保活 |

AgentKit 的 assistant、user/tool_result、stream_event、result 以及 additive 的
diagnosis_plan、diagnosis_need_info、diagnosis_report、diagnosis_state 都属于 chunk
payload 内的 Agent 协议事件，不应被误建模为 ChatRun 聚合状态迁移。诊断续聊的权威 checkpoint
来自 RunSummary.stateSnapshot，而不是从历史 NDJSON 反向猜测。

## 6. 目标领域边界与不变量

### 6.1 概念图

~~~mermaid
flowchart TB
    AT[AgentType<br/>稳定身份] --> OP[AgentOffer Policy<br/>用途/入口/默认资格]
    RR[Runtime Registry<br/>是否已注册/支持环境] --> CAT[AgentCatalog]
    OP --> CAT
    CAT --> UI[用户可选项]
    CAT --> GUARD[会话创建与默认设置守卫]

    CS[ChatSession<br/>conversationId + AgentType] --> CR[ChatRun<br/>runId + message boundary]
    CR --> INV[AgentRunInvocation]
    INV --> ROUTER[RoutingAgentGateway]
    ROUTER --> CLI[CLI Runtime]
    ROUTER --> NATIVE[Native Diagnosis Runtime]
    NATIVE --> ENGINE[DiagnoseEngine]
    CR --> CP[Diagnosis Checkpoint<br/>append-only projection]
~~~

### 6.2 聚合与一致性边界

#### ChatSession

- 保存 conversationId、AgentType、workingDir、env、CLI resumeId 和消息。
- AgentType 在会话创建后不可变。
- 历史读取不依赖运行时当前是否可用。
- CLI resumeId 只属于 CODEX/CLAUDE；NATIVE 不写入该字段。

#### ChatRun

- 表达一次运行的状态机和用户/assistant 消息边界。
- 同一 ChatSession 同时最多一个 active run，沿用数据库唯一索引保护。
- stop 的技术 key 是 runId，而不是 conversationId。
- 终态恰好一次，现有 ChatRun 聚合继续作最终裁决。

#### AgentOffer

AgentOffer 是策略值对象，不是持久化聚合。建议字段：

~~~java
AgentType type;
String displayName;
AgentPurpose purpose;
Set<AgentSurface> exposedSurfaces;
boolean defaultEligible;
boolean runtimeAvailable;
Set<String> supportedEnvironments;
~~~

它集中提供 requireUserSelectable(surface, env) 和 requireDefaultEligible()。静态产品策略与
动态 runtimeAvailable 的来源不同，但在创建会话的同一个应用服务守卫中合并判断。

#### Diagnosis Checkpoint

Checkpoint 是与成功 ChatRun 对齐的 append-only 投影，不放进 ChatSession 的内存消息集合。
其一致性边界是 CompleteChatRun 事务：

~~~text
保存 assistant message
  + 保存可选 DiagnosisCheckpoint
  + ChatRun -> SUCCEEDED
  + 追加 terminal event
  = 同一数据库事务
~~~

工具调用投影、usage 指标上报可以保持旁路最终一致，但用于下一轮推理的 stateSnapshot 不能先于
可见 assistant 消息独立提交。

### 6.3 必须由模型保护的不变量

1. AgentType 只表示稳定身份，不再用一个选择布尔值同时表达所有策略。
2. NATIVE 对 CHAT 可手动选择，对 DEFAULT、SCHEDULE、WORKFLOW、REFINERY 不可选。
3. 创建新会话时，静态暴露策略、runtimeAvailable 和环境支持三项必须同时成立。
4. NATIVE 永远不能通过“未传 agentType，使用全局默认”的路径被隐式选中。
5. ChatSession.agentType 创建后不可修改，恢复历史时以服务端值覆盖 UI 临时选择。
6. NATIVE 的 RunRequest.sessionId 使用 runId；conversationId 只用于 history 和 checkpoint。
7. Router 必须记录 runId 到具体 runtime 的映射；stop 不允许广播到错误 runtime。
8. stop 发生在 runtime 注册前也不能丢失，Router 必须保留 cancellation tombstone 并在启动前
   重新检查。
9. NATIVE 不读取、不提取、不更新 chat_session.resume_id。
10. prior history 对 NATIVE 只通过 RunRequest.history 传一次，不再进入 conversation_history
    prompt 前缀。
11. 下一轮只能读取当前 userMessageId 之前、且源 user/assistant 消息仍存在的最近 checkpoint。
12. 只有成功且保存了 assistant 消息的 NATIVE ChatRun 才能提交 checkpoint。
13. truncate/delete 后已越过可见消息边界的 checkpoint 必须删除或在查询中不可达。
14. STOPPED 只有在 ChatRun 已是 CANCEL_REQUESTED 时成为 CANCELLED；无用户取消的异常 stop
    不能伪装成用户取消。
15. TIMEOUT 成为明确超时失败，ERROR/REJECTED 成为执行失败；errorDetail 不直接返回浏览器。
16. enabled=true 时缺少模型、密钥、bound env 或后端安全配置必须启动失败；enabled=false 时
    agent-web 可无 AgentKit 凭证正常启动。
17. HTTP/Dubbo 后端不能把空 allowlist 直接传为“允许全部”；若启用对应工具，allowlist 必须
    非空，否则启动失败。
18. 任何 checkpoint、API key、数据库密码或后端 header 都不得出现在日志、SSE terminal 和
    管理统计 API 中。

## 7. 总体组件设计

### 7.1 目标组件流

~~~mermaid
flowchart LR
    Browser -->|GET /api/chat/agents| CatalogController
    CatalogController --> AgentCatalogService
    AgentCatalogService --> OfferPolicy
    AgentCatalogService --> RuntimeRegistry

    Browser -->|POST session / POST runs| ChatAPI
    ChatAPI --> ChatRunExecutor
    ChatRunExecutor --> Router
    Router --> CliRuntime
    Router --> NativeRuntime
    NativeRuntime --> Engine[DiagnoseEngine]
    NativeRuntime --> CheckpointQuery

    Engine -->|stream-json line| StreamChunkHandler
    StreamChunkHandler --> EventStore
    EventStore --> Browser
    Engine -->|RunSummary| NativeRuntime
    NativeRuntime -->|AgentExecutionResult| Lifecycle
    Lifecycle --> SQLite[(assistant + run + checkpoint)]
~~~

### 7.2 应用 Port 重构

新增结构化 AgentRunInvocation，代替 ChatRunExecutor 对宽参数重载的直接调用：

~~~java
public final class AgentRunInvocation {
    String runId;
    String conversationId;
    long userMessageId;
    AgentType agentType;
    String workingDir;
    String prompt;
    String resumeId;
    String env;
    String userId;
    long timeoutSeconds;
    List<AgentHistoryMessage> history;
    Map<String, String> extraEnv;
}
~~~

字段语义要求：

- prompt 已包含当前用户输入、slash command 展开、RAG 和 final-answer instruction；
- history 只包含 userMessageId 之前的消息；
- extraEnv 只给 CLI 子进程；NATIVE 不把它合并进 JVM 全局环境；
- timeoutSeconds 小于等于 0 时由各 runtime 使用其默认期限；
- resumeId 仅 CLI runtime 消费。

新增 provider-neutral AgentExecutionResult，至少包含：

~~~java
AgentStreamResult streamResult;
Optional<AgentStateCheckpointPayload> checkpoint;
AgentUsage usage;
String providerExitReason;
String privateErrorDetail;
~~~

privateErrorDetail 只用于受控日志摘要或内部诊断，不进入 ChatRun.errorMessage。checkpoint payload
携带 stateSnapshot 和可选 schemaVersion，应用层不依赖 RunSummary。

迁移策略：

1. 先给 AgentGateway 增加结构化重载。
2. 旧 runOnce/runStream 宽参数方法暂时作为兼容 delegate，保证定时任务和 Workflow 不被本次
   改造强制迁移。
3. ChatRunExecutor 率先切到结构化调用。
4. 后续其他调用点迁移完成后再删除宽参数重载。

### 7.3 RoutingAgentGateway

Router 按 AgentType 选择 runtime，不在 AgentCliGateway 中增加 if NATIVE 分支。

建议运行时接口：

~~~java
interface AgentRuntime {
    AgentType type();
    AgentRuntimeCapabilities capabilities();
    void run(AgentRunInvocation invocation,
             Consumer<String> onChunk,
             Consumer<AgentExecutionResult> onComplete);
    void stop(String runId);
    List<String> normalizeChunk(String rawLine);
    String extractResumeId(String rawLine);
}
~~~

CODEX 和 CLAUDE 可以共享一个 CliAgentRuntime，通过现有 CliDialect 再按类型细分。NATIVE 的
capabilities 至少为：

~~~text
surface CHAT
history TYPED
resume NONE
output CLAUDE_STREAM_JSON
stateCheckpoint SUPPORTED
~~~

Router 内部维护：

~~~text
activeRuntimeByRunId: runId -> AgentRuntime
pendingCancellation: Set<runId>
~~~

运行顺序必须为：

1. 校验 invocation.agentType 与 runtime 能力；
2. putIfAbsent 注册 activeRuntimeByRunId；
3. 若 pendingCancellation 已存在，则不启动 Provider，返回 STOPPED；
4. 调用 runtime.run；
5. finally 删除 active mapping 和 cancellation tombstone，并把 runId 记录到容量为 4096 的有界
   terminal run cache。

stopStream(runId) 先检查 terminal run cache：已经终态的 run 直接幂等返回；否则先写入
pendingCancellation，再查 active mapping 并调用对应 runtime.stop。这样既覆盖 ChatRun.start 和
runtime 注册之间的小窗口，也避免终态之后的重复 stop 留下永不消费的 cancellation tombstone。

### 7.4 NativeDiagnosisAgentRuntime

该 adapter 是唯一把 provider-neutral 模型映射到 AgentKit API 的位置：

~~~java
RunRequest request = RunRequest.builder()
    .workingDir(invocation.workingDir())
    .userMessage(invocation.prompt())
    .sessionId(invocation.runId())
    .env(invocation.env())
    .timeoutSeconds(effectiveTimeout(invocation))
    .history(historyMapper.map(invocation.history()))
    .stateSnapshot(checkpoints.loadBefore(
        invocation.conversationId(),
        invocation.userMessageId()).orElse(""))
    .build();
~~~

执行：

~~~java
engine.run(
    request,
    onChunk,
    summary -> onComplete.accept(resultMapper.map(summary))
);
~~~

约束：

- normalizeChunk(NATIVE, line) 为单元素直通；
- extractResumeId(NATIVE, line) 永远返回 null；
- Engine 收到的 sessionId 必须是 runId，使 stop(runId) 与 Router 一致；
- 不得把 Engine 输出中的顶层 session_id 写入 chat_session.resume_id；
- adapter 只读取 checkpoint，不提前写 checkpoint；
- onComplete 即使遇到 ERROR 也必须转成一个结果，异常抛出由 ChatRunExecutor 的现有兜底捕获；
- 完成回调只能生效一次，晚到 chunk 或晚到终态必须被隔离。

### 7.5 AgentKit 类型隔离

允许依赖 com.anthropic.agentkit 的包只有：

~~~text
com.example.agentweb.config.nativeagent..
com.example.agentweb.infra.nativeagent..
~~~

domain、app、interfaces 不得引用 DiagnoseEngine、RunRequest、RunSummary、TurnMessage。建议在
ArchitectureTest 增加 ArchUnit 规则，避免 AgentKit DTO 渗入 Controller 或 ChatRun 聚合。

## 8. 用户选择与 API 设计

### 8.1 Agent Catalog

新增：

~~~http
GET /api/chat/agents
~~~

建议响应：

~~~json
{
  "defaultAgent": "CODEX",
  "defaultVersion": 7,
  "agents": [
    {
      "type": "CODEX",
      "displayName": "Codex",
      "purpose": "GENERAL",
      "available": true,
      "userSelectable": true,
      "defaultEligible": true,
      "supportedEnvironments": ["test"]
    },
    {
      "type": "CLAUDE",
      "displayName": "Claude",
      "purpose": "GENERAL",
      "available": true,
      "userSelectable": true,
      "defaultEligible": true,
      "supportedEnvironments": ["test"]
    },
    {
      "type": "NATIVE",
      "displayName": "诊断 Agent",
      "purpose": "DIAGNOSIS",
      "available": true,
      "userSelectable": true,
      "defaultEligible": false,
      "supportedEnvironments": ["test"]
    }
  ]
}
~~~

agents 返回所有已知产品描述，前端新会话选择器只渲染同时满足 userSelectable、available 和
当前 env 在 supportedEnvironments 中的项。因此运行时关闭时，NATIVE 描述仍可用于正确显示
历史会话，但不会作为可点击的新会话选项出现。

保留 GET /api/chat/agent-default 一个发布周期作为兼容接口；App.vue 原子切到新 catalog 后，
再删除旧接口。两个接口共存时默认值和 version 必须来自同一个 RuntimeAgentSettings。

Catalog 查询不访问模型 Provider，也不扫描 CLI 二进制。运行时故障在 ChatRun 终态中表达。

### 8.2 会话创建守卫

POST /api/chat/session 的 agentType 处理规则：

| 输入 | 结果 |
| --- | --- |
| 缺省/空白 | 使用全局默认；默认必须 defaultEligible |
| CODEX/CLAUDE | Catalog 校验 CHAT 可选和 runtime 可用 |
| NATIVE 且 enabled、env 匹配 | 创建 NATIVE ChatSession |
| NATIVE 但 runtime 关闭 | 409，code=AGENT_RUNTIME_UNAVAILABLE |
| NATIVE 但 env 不匹配 | 409，code=AGENT_ENV_UNAVAILABLE |
| 未知枚举值 | 400，code=UNKNOWN_AGENT_TYPE |

会话创建和 ChatRun submit 都要校验 availability。只在创建时校验会留下“用户打开页面后运维
关闭 runtime，但旧页面仍能提交”的竞态；submit 二次校验可以在创建 user message 前失败。

### 8.3 默认 Agent

管理端 GET/PUT /api/admin/settings/agent-models 只返回/接受 defaultEligible=true 的 Agent。
第一阶段 options 仍只有 CODEX、CLAUDE。PUT NATIVE 返回 400，code=AGENT_NOT_DEFAULT_ELIGIBLE。

RuntimeAgentSettings 不再调用含混的旧选择解析函数，改为 AgentCatalog.requireDefaultEligible。
存量配置若意外出现 NATIVE，启动读取时记录无敏感信息的 warning 并回退到硬默认 CODEX。

### 8.4 前端行为

App.vue 的桌面和移动选择器都改为从 catalog 渲染，不能维护两份硬编码枚举。

具体规则：

1. 页面启动先加载 env 和 agent catalog，再解析 localStorage。
2. localStorage 中类型未知、不可用或不支持当前 env 时，新会话选择回退到服务端默认。
3. NATIVE 显示“诊断 Agent”，可附“只读排障”说明。
4. 创建 session 后选择器保持锁定，沿用当前交互。
5. 加载历史 NATIVE session 时，以 session.agentType=NATIVE 覆盖选择器；即使当前不可用，也
   显示“诊断 Agent（当前不可用）”。
6. 不可用历史会话允许浏览、分享、反馈和回退查看，但 composer/send 禁用，并显示可操作提示。
7. defaultVersion 变化只重置“尚未绑定 session 的新对话选择”，不能把已加载会话的 AgentType
   改成新的全局默认。
8. env 改变后重新计算有效 options；已有 session 的 env 与 AgentType 同时锁定。

chat-panel.vue 的 ensureSession 仍提交 workingDir、agentType、env。前端检查只改善体验，
服务端 Catalog 守卫始终是权威。

## 9. JAR、依赖与构建设计

### 9.1 pom.xml

新增固定属性和普通依赖：

~~~xml
<properties>
    <agentkit.version>0.2.1</agentkit.version>
</properties>

<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>agentkit-agent-diagnosis</artifactId>
    <version>${agentkit.version}</version>
</dependency>
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>agentkit-kernel</artifactId>
    <version>${agentkit.version}</version>
</dependency>
~~~

删除旧的可选 profile、失效 SNAPSHOT 坐标和 build-helper source roots。

明确禁止：

- 依赖 agentkit-cli；
- 用 exec-maven-plugin 启动 CLI；
- systemPath 指向 sibling repo target；
- 把 AgentKit 源目录作为 agent-web source root；
- 手工解压或 shade 成平铺 class；
- 使用 SNAPSHOT 作为生产部署依赖。

### 9.2 Artifact 发布

本地开发可先在 agent-langchain4j reactor 中执行：

~~~bash
mvn -pl agentkit-agent-diagnosis -am install
~~~

CI/生产构建必须从团队 Maven 仓库解析 0.2.1 或后续正式版本，并校验 checksum。agent-web
流水线不应依赖 sibling 目录恰好存在。

### 9.3 依赖兼容验证

agent-web 使用 Spring Boot 3.3.13，AgentKit 编译时使用 LangChain4j 1.18.0、Jackson
2.18.2 和 SLF4J 2.0.13。实施时必须执行：

~~~bash
mvn dependency:tree
mvn dependency:analyze
mvn test
mvn package
jar tf target/agent-web-0.1.0-SNAPSHOT.jar
~~~

验收点：

- BOOT-INF/lib 中存在 agentkit-agent-diagnosis-0.2.1.jar 和 agentkit-kernel-0.2.1.jar；
- 不存在 agentkit-cli；
- 只有一个有效 SLF4J binding；
- Jackson、OkHttp/HTTP client、LangChain4j 没有冲突版本导致的 NoSuchMethodError；
- disabled 配置下可直接启动；
- enabled 配置下完成一个使用 mock LLM 的 Spring smoke test。

如果 Spring Boot dependency management 最终选择的 Jackson 版本低于 AgentKit 实际使用 API
要求，应通过 agent-web 的 dependencyManagement 对齐经过双方测试的统一版本，而不是在运行
时报错后增加 classloader 绕行。

## 10. Spring 配置与生命周期

### 10.1 配置结构

新增独立 NativeDiagnosisProperties：

~~~yaml
agent:
  native:
    enabled: false
    bound-environment: test
    provider: OPENAI
    model: ${AGENT_NATIVE_MODEL:gpt-5.6-sol}
    api-key: ${AGENT_NATIVE_API_KEY:}
    base-url: ${AGENT_NATIVE_BASE_URL:}
    timezone: ${AGENT_NATIVE_TIMEZONE:UTC}
    max-tokens: 8192
    timeout-seconds: 1800
    budget:
      max-turns: 20
      max-tool-calls: 50
      max-input-tokens: 400000
      max-output-tokens: 50000
      max-output-characters: 500000
      max-llm-calls: 30
    prompt-packs: ""
    skills-root: ""
    tools:
      http-enabled: false
      allowed-http-hosts: []
      dubbo-enabled: false
      allowed-dubbo-methods: []
    backends:
      es-base-url: ""
      mysql-jdbc-url: ""
      mysql-user: ""
      mysql-password: ""
      redis-host: ""
      redis-port: 6379
      redis-password: ""
      redis-database: 0
      log-query-url: ""
~~~

当前宿主默认模型为 `gpt-5.6-sol`，可由 `AGENT_NATIVE_MODEL` 覆盖；这是 agent-web 的部署默认，
不表示 AgentKit 将某个模型固化为 Provider 通用默认。生产 endpoint 和凭据由环境变量、Secret
Store，或宿主本地的 `data/secrets.properties` 注入，绝不能硬编码进 application.yml。Spring
Boot relaxed binding 对应的常用环境变量为 `AGENT_NATIVE_ENABLED`、`AGENT_NATIVE_PROVIDER`、
`AGENT_NATIVE_MODEL`、`AGENT_NATIVE_API_KEY`、`AGENT_NATIVE_BASE_URL` 与
`AGENT_NATIVE_BOUND_ENVIRONMENT`。NATIVE 与标准 OpenAI/Codex CLI 配置隔离，解析规则如下：

| NATIVE 属性 | 唯一环境变量 | 未配置 |
| --- | --- | --- |
| api-key | `AGENT_NATIVE_API_KEY` | 空；enabled=true 时 fail fast |
| base-url | `AGENT_NATIVE_BASE_URL` | 空；由 Provider SDK 使用默认地址 |

`OPENAI_API_KEY`、`OPENAI_BASE_URL` 只留给 Codex CLI 或官方 OpenAI 客户端，NATIVE 不回退这两个
变量，避免不同账号或 Provider 之间发生隐式串用。密钥只进入宿主进程内的 LLM client，不落库、
不进入 checkpoint、SSE、ChatMessage 或配置文档。测试覆盖标准变量不能影响 NATIVE，以及 NATIVE
专用变量能够独立配置。

宿主通过以下 Spring 配置加载本地 secret 文件：

~~~yaml
spring:
  config:
    import: "classpath:agent-paths.yml,optional:file:./data/secrets.properties"
~~~

`data/secrets.properties` 使用 Spring Properties 语法保存 NATIVE 专用属性，只由 Spring 读取，
不能在启动脚本中 `source`。该文件位于 Git 忽略的 `data/` 目录，部署文件权限必须为 `0600`，
owner 必须是运行 agent-web 的系统用户。环境变量仍有更高优先级；生产环境优先对接 Secret Store。
启动脚本先把工作目录固定到仓库根目录，再启动 Java，因此相对路径不会随调用方目录漂移。文档、
日志、命令行参数、测试 fixture 和制品均不得包含真实 endpoint 或 key。

### 10.2 启动规则

| 条件 | 启动行为 | Catalog |
| --- | --- | --- |
| enabled=false | 不创建 Engine/Native runtime；agent-web 正常启动 | NATIVE available=false |
| enabled=true，配置完整 | 创建 Engine 并注册 runtime | NATIVE available=true |
| enabled=true，缺模型/密钥/env | fail fast | 应用不启动 |
| enabled=true，bound env 不在 agent.envs | fail fast | 应用不启动 |
| HTTP/Dubbo 开启但 allowlist 空 | fail fast | 应用不启动 |
| 某后端配置半完整 | fail fast，并指明字段名，不回显值 | 应用不启动 |

外部 Provider 不做启动期真实调用，避免短暂网络故障阻止服务启动。配置对象的 toString、Actuator
env 暴露和异常消息必须屏蔽 secret。

### 10.3 Engine 装配

NativeDiagnosisConfiguration 是 AgentKit assembly root，逻辑为：

~~~java
AppConfig llmConfig = new AppConfig(
    apiKey, model, maxTokens, baseUrl, permissionMode, provider);

DiagnoseEngine engine = DiagnoseEngineBuilder.create()
    .llm(LlmClientFactories.create(llmConfig))
    .backendConfig(toBackendConfig(properties))
    .toolPolicy(toToolPolicy(properties))
    .budget(toBudget(properties))
    .structuredDiagnosis()
    .promptPacks(optionalPromptPackPath)
    .skills(optionalSkillsRoot)
    .build();
~~~

可选目录为空时不调用对应 builder 方法；配置了路径但不存在或不可读时 fail fast。Engine 作为
单例 Bean，destroyMethod=close。NativeDiagnosisAgentRuntime 引用该单例，不为每个 ChatRun
重新创建 LLM client 或后端连接。

### 10.4 关闭行为

Spring 关闭时：

1. Router 停止接受新的 NATIVE run；
2. DiagnoseEngine.close 拒绝新请求、取消活动 run 并短暂 drain；
3. 未完成 ChatRun 在进程退出后由现有 ChatRunRecoveryService 标记 INTERRUPTED；
4. 未进入成功事务的 RunSummary checkpoint 不落库。

## 11. Prompt、History 与状态映射

### 11.1 History Delivery 变化点

不要在 ChatRunPromptBuilder 内堆叠 provider 名称分支。由 runtime capability 提供
HistoryDeliveryMode：

| Runtime 状态 | HistoryDeliveryMode | prior history 去向 |
| --- | --- | --- |
| CODEX/CLAUDE 有 resumeId | PROVIDER_RESUME | CLI resume |
| CODEX/CLAUDE 无 resumeId | PROMPT_PREFIX | 现有 conversation_history |
| NATIVE | TYPED | RunRequest.history |

ChatRunPromptBuilder 只在 PROMPT_PREFIX 时拼历史。TYPED 模式下仍执行 slash expansion、RAG
注入和 final-answer instruction，但 prompt 只表达当前回合。

### 11.2 类型化历史映射

按 ChatRunExecutionContext.history 的原顺序映射：

- prior user message -> new UserTurn(content)；
- prior assistant NDJSON -> StreamJsonHistoryParser.parse(content.lines())；
- parser 保留 assistant text 和配对完整的 tool_use/tool_result；
- 孤立 tool call 或 tool result 由 AgentKit parser 丢弃；
- 若存量 assistant 内容不是合法 NDJSON 且 parser 结果为空，使用
  StreamOutputExtractor.extractPlainText 后构造无 tool call 的 AssistantTurn 作为兼容兜底；
- 当前 userMessageId 不在 history 中，只通过 RunRequest.userMessage 进入一次。

History mapper 位于 infra.nativeagent，应用层只传 provider-neutral AgentHistoryMessage。

### 11.3 RAG 与 slash command

当前回合执行顺序保持：

~~~text
原始用户消息
 -> RAG recall（可选）
 -> slash command 展开
 -> final-answer instruction
 -> AgentRunInvocation.prompt
~~~

prior history 不参与第二次 RAG，也不被重复加入 prompt。AgentKit skills-root 是进程内诊断
Agent 的 skill catalog；agent-web workspace slash command 仍按现有规则展开为当前 prompt，
两者名称相同也不能被当作同一个持久化机制。

### 11.4 stateSnapshot

stateSnapshot 保存结构化诊断过程，例如已有证据、假设、缺失信息和报告状态。它不是对话全文的
替代物，因此每轮同时传 typed history 和最近有效 snapshot。

禁止：

- 从最后一条 diagnosis_state NDJSON 解析并当作唯一真相；
- 用 chat_session.resume_id 保存 snapshot；
- 只按 sessionId 覆盖一行 latest snapshot；
- 在 Engine onComplete 后立即由 adapter 独立写数据库。

## 12. Checkpoint 持久化与回退

### 12.1 表结构

在 schema.sql 增加：

~~~sql
CREATE TABLE IF NOT EXISTS native_diagnosis_checkpoint (
    run_id                   TEXT PRIMARY KEY,
    session_id               TEXT    NOT NULL,
    user_message_id          INTEGER NOT NULL,
    assistant_message_id     INTEGER NOT NULL,
    state_snapshot           TEXT    NOT NULL,
    snapshot_schema_version  TEXT,
    input_tokens             INTEGER NOT NULL DEFAULT 0,
    output_tokens            INTEGER NOT NULL DEFAULT 0,
    cache_read_input_tokens  INTEGER NOT NULL DEFAULT 0,
    created_at               INTEGER NOT NULL,
    UNIQUE (assistant_message_id),
    CHECK (input_tokens >= 0),
    CHECK (output_tokens >= 0),
    CHECK (cache_read_input_tokens >= 0)
);

CREATE INDEX IF NOT EXISTS idx_native_checkpoint_session_boundary
    ON native_diagnosis_checkpoint(session_id, assistant_message_id DESC);
~~~

当前 chat_run 对 user/assistant message 也没有数据库外键，并且回退会保留 ChatRun 审计行。因此
该表第一阶段同样不增加会改变既有删除顺序的 FK，改由事务清理和查询 JOIN 双重保护。新库通过
schema.sql 建表，老库在 SqliteInitializer 每次执行幂等 schema 时自动得到新表，无历史数据回填。

### 12.2 读取规则

下一轮以 conversationId 和当前 userMessageId 查询：

~~~sql
SELECT c.state_snapshot
FROM native_diagnosis_checkpoint c
JOIN chat_message u
  ON u.id = c.user_message_id
 AND u.session_id = c.session_id
JOIN chat_message a
  ON a.id = c.assistant_message_id
 AND a.session_id = c.session_id
WHERE c.session_id = ?
  AND c.user_message_id < ?
  AND c.assistant_message_id < ?
ORDER BY c.assistant_message_id DESC
LIMIT 1;
~~~

JOIN 保证即使一次显式清理遗漏，已删除消息关联的 checkpoint 仍不可达。显式删除仍是必须的，
用于控制空间并保持审计直观。

### 12.3 完成事务

ChatRunLifecycleService.complete 改接收 CompleteChatRunCommand，其中包含 output、
AgentExecutionResult 和 recallJson。

SUCCESS 且 output 非空时同一事务执行：

1. 插入 assistant chat_message，得到 assistantMessageId；
2. 保存 recall；
3. 若结果含 checkpoint，插入 native_diagnosis_checkpoint，并绑定 runId、userMessageId、
   assistantMessageId；
4. ChatRun.succeed；
5. 追加 terminal event；
6. commit 后才向实时订阅者发布。

checkpoint 插入失败时整个事务回滚，不能形成“历史显示成功但下一轮状态缺失或超前”的半完成。
SUCCESS 但 stateSnapshot 为空时允许保存回答，不过记录 checkpoint_missing 指标和 warning；
下一轮回退到 typed history，不伪造空 checkpoint 行。

STOPPED、TIMEOUT、ERROR、REJECTED 不保存 checkpoint，即使 RunSummary 带有中间 snapshot，
因为没有与之原子对应的成功 assistant 边界。

### 12.4 truncate 与 delete

truncateFrom(sessionId, fromId) 在删除 chat_message 前执行：

~~~sql
DELETE FROM native_diagnosis_checkpoint
WHERE session_id = ?
  AND (user_message_id >= ? OR assistant_message_id >= ?);
~~~

然后沿用 recall 删除、消息删除和 resumeId 清空。删除整个 session 时先按 sessionId 删除全部
checkpoint，再删除消息和 session。保留 chat_run 审计行的现有行为不变。

### 12.5 回退时序

~~~mermaid
sequenceDiagram
    participant U as User
    participant App as ChatAppService
    participant DB as SQLite
    participant Native as Native Runtime

    U->>App: truncate from message M5
    App->>DB: delete checkpoints crossing M5
    App->>DB: delete recall/messages id >= M5
    App->>DB: clear CLI resumeId
    U->>App: submit new message M7
    App->>DB: load latest extant checkpoint before M7
    App->>Native: typed history + checkpoint
    Note over Native: 已删除 M5/M6 的证据不会恢复
~~~

## 13. 环境与诊断工具安全

### 13.1 只读能力

第一阶段只使用 AgentKit DiagnosisToolBackends 中显式配置的只读工具：

- LogQuery；
- ES read；
- MySQL read；
- Redis read；
- HTTP GET；
- Dubbo invoke 的受限诊断方法。

不注册 Bash、FileWrite、FileEdit 或任意写操作。即使底层工具名为 read，后端凭证本身也必须是
只读账号，不能只依赖 prompt 或 Java 参数检查。

### 13.2 allowlist

DiagnosisToolPolicy 的空 Set 在 AgentKit 中表示 allow-all。因此宿主配置层必须执行更严格规则：

- http-enabled=false 时不创建 HTTP backend；
- http-enabled=true 时 allowed-http-hosts 必须非空；
- dubbo-enabled=false 时不创建 Dubbo backend；
- dubbo-enabled=true 时 allowed-dubbo-methods 必须非空；
- 禁止回环地址、link-local、云 metadata 地址和 allowlist 外重定向；
- allowlist 使用规范化 host/method 精确匹配，不接受用户输入的正则。

HTTP reader 使用 JDK HttpClient 的 Redirect.NEVER；3xx 作为原始响应返回调用方，不自动访问
Location。否则即使首跳 host 已通过 allowlist，仍可能经重定向访问未授权内网或云 metadata
地址。

ES、MySQL、Redis、LogQuery 的 endpoint 由服务端配置固定，用户 prompt 不能替换连接地址。

### 13.3 凭证与数据

- API key、数据库密码和带 token 的 headers 只来自环境变量或 secret store；
- 不进入 RunRequest.env、prompt、extraEnv、MDC、异常公开消息或 catalog；
- 日志只记录 provider、model 的非敏感标识、runId、耗时、usage 和退出原因；
- checkpoint 和完整 ChatRun NDJSON 可能包含诊断证据，按聊天数据的访问控制和删除策略保护；
- 管理统计只输出聚合数字，不输出 snapshot、tool input 中的 secret 或 errorDetail；
- 对模型输出和 tool result 继续应用已有大小上限，checkpoint 另设可观测的大小告警。

进程内 JAR 不是安全沙箱。AgentKit 与 agent-web 共享 JVM、文件权限、网络和内存中的凭证，因此
最小权限账户、网络 ACL 和 tool allowlist 是部署必需条件。

### 13.4 环境绑定

AgentKit 当前 RunRequest.env 只是宿主约束字段，诊断 MVP 不用它切换 backend。第一阶段因此
要求 agent.native.bound-environment 对应 agent.envs 中一个明确 key；当前仓库可绑定 test。

Catalog 返回 supportedEnvironments，创建 session 和 submit run 时都检查精确匹配。不能通过
在 prompt 中写“当前是测试环境”来代替后端隔离。

未来开放多环境时，演进为单例 NativeDiagnosisEngineRegistry 管理：

~~~text
env key -> one configured DiagnoseEngine + one set of least-privilege backends
~~~

每个 Engine 按环境独立装配和关闭，Runtime 根据 invocation.env 精确选取；不得让一个固定
backend 的 Engine 仅凭 prompt 在生产/测试之间切换。

## 14. 退出、取消、超时与恢复语义

### 14.1 RunSummary 映射

| AgentKit ExitReason | AgentExecutionResult | ChatRun 结果 |
| --- | --- | --- |
| SUCCESS | completed(0) + optional checkpoint/usage | output 非空则 SUCCEEDED |
| STOPPED | completed(-1) | 已 CANCEL_REQUESTED 则 CANCELLED，否则 FAILED |
| TIMEOUT | terminated(-1, HARD_TIMEOUT) | FAILED，failureCode=HARD_TIMEOUT |
| ERROR | completed(1)，保留 private detail | FAILED，公开通用错误 |
| REJECTED | completed(1)，providerExitReason=REJECTED | FAILED，建议 failureCode=AGENT_REJECTED |

为了不丢失 REJECTED/ERROR 差异，CompleteChatRunCommand 可以新增 providerExitReason；
ChatRun.failureCode 应逐步增加 provider-neutral 的 AGENT_REJECTED、AGENT_EXECUTION_ERROR。
errorDetail 需要截断、脱敏，只进受限日志，不直接写 error_message。

### 14.2 取消限制

LangChain4j 1.18 的 streaming API 没有可由 AgentKit 持有的底层 Provider HTTP cancel handle。
DiagnoseEngine 能保证 cancellation token、工具停止、终态和回调隔离，但底层 HTTP 请求释放是
best effort，可能要等 Provider 返回或超时。

因此产品语义是：

- 用户 stop 后 ChatRun 立即进入 CANCEL_REQUESTED；
- Router/Engine 发出取消；
- 收到 STOPPED 后进入 CANCELLED；
- UI 显示“正在停止”直到 terminal，不虚假承诺连接已瞬时释放；
- timeoutSeconds 和 Provider client timeout 必须有有限上限；
- 晚到 chunk 不得在 terminal 后继续追加事件或形成 checkpoint。

### 14.3 服务重启

进程内运行没有可重连的外部 PID。服务重启后：

- Engine 内存 active map 消失；
- 现有 ChatRunRecoveryService 把数据库活动 run 标记 INTERRUPTED；
- 浏览器重连收到 terminal；
- 最后一个成功 checkpoint 保持有效；
- 被中断回合不产生 checkpoint，用户可重新提交。

## 15. 前端、管理端与投影兼容

### 15.1 stream-json

NATIVE 输出与 Claude-compatible stream-json 对齐，所以：

- StreamChunkHandler 的 fullResponse 继续保存 NDJSON；
- StreamOutputExtractor 可从 assistant/result 提取最终文本；
- ChatRunEventBuffer 继续批量持久化 chunk；
- SSE replay/live 不增加 NATIVE 专用 event；
- 当前前端不认识的 diagnosis 扩展事件可忽略，不能导致解析器停止处理后续 assistant 文本。

### 15.2 工具调用投影

chat_tool_invocation.provider 的 CHECK 已包含 NATIVE。ChatToolInvocationTrackerFactory 继续以
context.agentType=NATIVE 打开 tracker，JsonToolInvocationEventExtractor 应从 NATIVE 的
assistant tool_use 和 user tool_result 中产生既有结构化投影。

必须补测试证明：

- NATIVE tool_use 开始可识别；
- tool_result 成功/失败可闭合；
- invocation_index 和 provider_call_id 幂等；
- history migration 不把 diagnosis_state 当作 tool；
- 管理端筛选和统计显示 NATIVE。

### 15.3 功能兼容矩阵

| 功能 | NATIVE 第一阶段 |
| --- | --- |
| 新建普通聊天 | 支持 |
| ChatRun Submit/SSE/reconnect | 支持 |
| Stop | 支持，底层 HTTP best effort |
| 多轮续聊 | 支持，typed history + checkpoint |
| 历史查看/分享/反馈 | 支持 |
| 回退后续聊 | 支持，checkpoint 按边界失效 |
| RAG recall | 保持当前聊天策略 |
| Admin conversations/tool analytics | 支持显示 NATIVE |
| 全局默认 | 不支持 |
| Scheduled task | 不支持 |
| Workflow | 不支持 |
| Refinery scoring runtime | 不支持 |

## 16. 可观测性

### 16.1 日志

建议结构化字段：

~~~text
runId, conversationId, agentType=NATIVE, boundEnv,
provider, model, durationMs, exitReason,
inputTokens, outputTokens, cacheReadInputTokens,
historyMessages, checkpointLoaded, checkpointBytes
~~~

禁止记录 prompt、stateSnapshot、API key、backend credential、完整 errorDetail。workingDir 沿用
现有 LogSafe 规则。Provider request/response body 日志默认关闭；结构化审计只记录经过长度和字符
约束的 runId/sessionId、environment、逻辑 tool/dataSource、status、duration、resultBytes、outcome
与 usage，不能记录 endpoint、header、日志正文或 snapshot。

### 16.2 指标

生产实现已引入 Spring Boot Actuator、Micrometer Prometheus registry，并只在 loopback 管理端口
暴露 `health`、`info`、`prometheus`。NATIVE 运行时导出以下九类指标：

| Micrometer 名称 | Prometheus 形态 | 关键低基数标签 |
| --- | --- | --- |
| `diagnosis.run.total` | `diagnosis_run_total` | outcome、environment |
| `diagnosis.run.duration` | `diagnosis_run_duration_seconds_*` | outcome、environment |
| `diagnosis.plan.blocked` | `diagnosis_plan_blocked_total` | blocker_type、code、environment |
| `diagnosis.tool.calls` | `diagnosis_tool_calls_total` | tool、data_source、status、environment |
| `diagnosis.tool.duration` | `diagnosis_tool_duration_seconds_*` | 同 tool calls |
| `diagnosis.tool.result.bytes` | `diagnosis_tool_result_bytes_*` | tool、environment |
| `diagnosis.backend.readiness` | `diagnosis_backend_readiness` | environment、data_source、tool |
| `diagnosis.evidence.count` | `diagnosis_evidence_count_total` | source、environment |
| `diagnosis.query.window.seconds` | `diagnosis_query_window_seconds_*` | tool、environment |

Counter 名称经实际 exporter 验证只有一个 `_total` 后缀，告警规则不得使用双
`_total_total`。readiness 的数值合同为 READY=1、DEGRADED=0.5、UNAVAILABLE/NOT_CONFIGURED=0。
指标 tag 只允许逻辑环境、工具、dataSource 和枚举状态，不能使用 endpoint、用户输入、日志正文、
异常 message 或 credential，避免泄漏与高基数。

### 16.3 运维诊断

Catalog 的 available 只表示本地装配状态，不等于 Provider/Backend 此刻健康。受管理员保护的
`/api/metrics/native-diagnosis/readiness` 返回 secret-free 投影，明确区分 `modelStatus`、
`diagnosisMode`、`overallStatus` 与每个 tool/dataSource capability 的 readiness/reason；不得在
Catalog 请求中做实时 Provider 调用，以免每个页面加载制造成本和故障放大。

loopback 管理端口的 `/actuator/health` 与 `/actuator/prometheus` 供同机 collector 抓取，公网
反向代理不得转发。`ops/prometheus/native-diagnosis-alerts.yml` 已覆盖 Backend unavailable、
run failure ratio、Backend rate limited 和 tool failure ratio；生产放量前 collector target 必须
为 UP，并完成一次告警到接收人的演练。

## 17. 文件与包改造清单

以下是已经落地的主要文件。小型 DTO/校验逻辑按现有工程命名合并，没有引入只为匹配设计图而
存在的空壳类型。

### 17.1 新增

~~~text
src/main/java/com/example/agentweb/domain/agentrun/
  AgentPurpose.java
  AgentSurface.java
  AgentOffer.java
  AgentOfferPolicy.java

src/main/java/com/example/agentweb/domain/diagnosis/
  DiagnosisCheckpoint.java
  DiagnosisCheckpointRepository.java

src/main/java/com/example/agentweb/app/agentrun/
  AgentCatalogService.java

src/main/java/com/example/agentweb/app/agentrun/port/
  AgentRuntime.java
  AgentRuntimeRegistry.java
  AgentRunInvocation.java
  AgentHistoryMessage.java
  AgentExecutionResult.java
  AgentUsage.java
  AgentStateCheckpointPayload.java
  HistoryDeliveryMode.java

src/main/java/com/example/agentweb/infra/agentrun/
  RoutingAgentGateway.java
  CliAgentRuntime.java
  SpringAgentRuntimeRegistry.java

src/main/java/com/example/agentweb/infra/nativeagent/
  NativeDiagnosisAgentRuntime.java
  NativeDiagnosisHistoryMapper.java
  NativeRunSummaryMapper.java
  SqliteDiagnosisCheckpointRepository.java

src/main/java/com/example/agentweb/config/nativeagent/
  NativeDiagnosisProperties.java
  NativeDiagnosisConfiguration.java

src/main/java/com/example/agentweb/interfaces/dto/
  AgentCatalogResponse.java
  AgentOfferResponse.java
~~~

可根据现有命名约定合并小 DTO，但不能让 AgentKit 类型越过 infra/config 边界。

### 17.2 修改

~~~text
pom.xml
src/main/resources/application.yml
src/main/resources/schema.sql

domain/shared/AgentType.java
app/ChatAppServiceImpl.java
app/chatrun/ChatRunExecutor.java
app/chatrun/ChatRunPromptBuilder.java
app/chatrun/ChatRunLifecycleService.java
app/agentrun/port/AgentGateway.java
infra/AgentCliGateway.java
infra/SqliteInitializer.java
infra/SqliteSessionRepo.java
infra/setting/RuntimeAgentSettings.java
interfaces/ChatController.java
interfaces/AdminSettingsController.java

frontend/js/App.vue
frontend/js/components/chat-panel.vue
frontend/js/admin/pages/Settings.vue
~~~

### 17.3 测试

重点新增或扩展：

~~~text
domain/agentrun/AgentOfferTest.java
app/agentrun/AgentCatalogServiceTest.java
infra/agentrun/RoutingAgentGatewayTest.java
infra/nativeagent/NativeDiagnosisAgentRuntimeTest.java
infra/nativeagent/NativeDiagnosisHistoryMapperTest.java
infra/nativeagent/SqliteDiagnosisCheckpointRepositoryTest.java
app/chatrun/ChatRunLifecycleServiceTest.java
app/chatrun/ChatRunExecutorTest.java
infra/SqliteSessionRepoTest.java
interfaces/ChatControllerTest.java
interfaces/AdminSettingsControllerTest.java
config/nativeagent/NativeDiagnosisConfigurationTest.java
NativeDiagnosisFlowTest.java
ArchitectureTest.java
frontend unit tests
Playwright desktop/mobile selection tests
~~~

## 18. 分阶段 TDD 实施计划

### 阶段 1：Catalog 与领域策略

先写失败测试：

- NATIVE 对 CHAT 可选；
- NATIVE 对 DEFAULT 不可选；
- runtime unavailable/env mismatch 拒绝新 session；
- 历史描述仍能返回；
- Admin PUT NATIVE 为 400。

再实现 AgentOffer/AgentCatalog，替换 AgentType 上单一选择标记的业务用途。此阶段不接 Engine。

### 阶段 2：正确 JAR 与条件装配

先写 disabled/enabled 配置测试，再替换 pom profile、增加 Properties 和 Engine Bean。用 mock
LlmClient 或测试替身验证 Spring context，不调用真实 Provider。

完成依赖树和可执行 JAR 内容检查。

### 阶段 3：结构化 Port 与 Router

先覆盖：

- CODEX/CLAUDE 仍路由到 CLI；
- NATIVE 路由到进程内 runtime；
- 相同 runId 重复注册被拒绝；
- stop 在注册前、运行中、完成后都幂等；
- NATIVE extractResumeId 为 null、normalize 为直通。

然后让 ChatRunExecutor 使用 AgentRunInvocation，保留旧 overload 兼容其他调用点。

### 阶段 4：History 与 RunSummary

先覆盖 user/assistant/tool pair/malformed history 映射和“不重复注入 history”，再实现
NativeDiagnosisHistoryMapper。

用 fake DiagnoseEngine 验证 SUCCESS、STOPPED、TIMEOUT、ERROR、REJECTED、usage、late
callback 和 exception 映射，不使用 live 模型。

### 阶段 5：Checkpoint 事务与回退

先建立 repository 和 migration 测试，再扩展 complete 事务。必须有故障注入测试证明：

- checkpoint insert 失败时 assistant/run success 一并回滚；
- failed/cancelled run 不写 checkpoint；
- next run 只读最近有效边界；
- truncate 同时跨 user/assistant 边界清理；
- 即使留有 orphan row，JOIN 查询也不会读到；
- delete session 清理全部 checkpoint。

### 阶段 6：前端和管理端

用前端单测覆盖 Catalog、本地选择、defaultVersion 和 unavailable history。Playwright 同时验证
desktop/mobile：

- 可选择“诊断 Agent”；
- session 创建后锁定；
- 刷新恢复 NATIVE；
- runtime unavailable 时不显示为新会话选项；
- Admin 默认列表不出现 NATIVE。

### 阶段 7：集成、灰度与安全验证

使用 mock Provider 做 Spring Flow 测试，使用受控测试 Provider 做一条 live smoke：

~~~text
create NATIVE session
 -> submit
 -> receive stream-json/tool events
 -> reconnect replay
 -> success + checkpoint
 -> second turn uses history/checkpoint
 -> stop another run
 -> rewind and submit
~~~

验证只读凭证、allowlist、超时、日志脱敏和 JAR 内容后才进入灰度。

## 19. 测试矩阵与验收标准

| 场景 | 预期 |
| --- | --- |
| native disabled，无 AGENT_NATIVE_API_KEY | agent-web 启动；Catalog NATIVE unavailable |
| 只设置 OPENAI_API_KEY/OPENAI_BASE_URL | NATIVE 凭据和地址仍为空，不与官方客户端串用 |
| native enabled，缺 model/key | 启动失败，错误不回显 secret |
| enabled，env 配置不合法 | 启动失败 |
| 用户选 NATIVE/test | session.agent_type=NATIVE |
| 用户选 NATIVE/其他 env | 409 AGENT_ENV_UNAVAILABLE |
| Admin 默认设 NATIVE | 400 AGENT_NOT_DEFAULT_ELIGIBLE |
| 普通 NATIVE run | 不创建子进程，Engine 收到 runId 和 typed history |
| CODEX/CLAUDE 回归 | 命令、resume、normalize、stop 保持现状 |
| NATIVE chunk | 可 SSE live/replay，最终 NDJSON 可解析 |
| NATIVE tool call | chat_tool_invocation.provider=NATIVE，开始/结果闭合 |
| NATIVE SUCCESS | assistant、run success、checkpoint 同事务 |
| 用户 stop | CANCEL_REQUESTED -> CANCELLED，无 checkpoint |
| Engine timeout | FAILED/HARD_TIMEOUT，无 checkpoint |
| Engine error/rejected | 公开错误脱敏，内部 reason 可观测 |
| stop 早于 runtime 注册 | 不启动 Provider 或立即 stop，无丢取消 |
| 第二轮 | history 只出现一次，加载上一成功 checkpoint |
| 回退后第二分支 | 不读取被删分支 checkpoint |
| 删除 session | checkpoint 无残留 |
| runtime 关闭后读历史 | 可读/分享/反馈，send 被阻止并提示 |
| 浏览器刷新/断网 | 复用既有 ChatRun SSE cursor，不重复运行 |
| Spring 重启 | active run -> INTERRUPTED，已成功 checkpoint 保留 |
| 旧同步 `/message` 调 NATIVE | 在保存 user message 前拒绝，不留下半回合 |
| Workflow 保存 NATIVE | 400 AGENT_SURFACE_UNAVAILABLE，不调用 Workflow service |
| Refinery 处理 NATIVE 会话 | 跳过，不调用 CLI scorer、不 embedding、不写状态 |
| 可执行 JAR | 包含 diagnosis/kernel，不包含 agentkit-cli |
| 安全 | 无写工具；HTTP/Dubbo 无空 allowlist；日志无 secret/snapshot |

### 19.1 实施验收记录

实现完成后增加了两层可重复验收：

1. `NativeDiagnosisFlowTest` 使用真实 Spring HTTP Controller、ChatRun、SQLite、Router、
   `NativeDiagnosisAgentRuntime`、checkpoint 与 SSE replay，只以进程内可控 `DiagnoseEngine`
   替身隔离外部模型。测试覆盖两轮续聊、typed history、checkpoint 加载、cursor 续传、回退后
   分支续聊、用户 stop、无效 checkpoint 清理和删除会话。
2. Maven `validate` 阶段由 Enforcer 禁止直接或传递依赖 `agentkit-cli`；`verify` 阶段检查 Spring
   Boot fat JAR 中 diagnosis/kernel 各恰好一个且 CLI 为零。该门禁把“普通 JAR 集成而非 CLI”
   从人工检查升级为构建不变量。

同时补齐三个容易绕过 CHAT surface 的服务端边界：Workflow 创建命令与聚合双重拒绝 NATIVE，
Refinery 对 NATIVE 会话直接跳过，旧同步 one-shot `/message` 在落库前拒绝。前端隐藏入口只负责
交互，不能替代这些服务端守卫。

本地自动化验收不需要外部 Provider 凭据。真实 Provider 验证必须由进程环境临时注入，不允许把
key 写入 application.yml、测试 fixture、文档、临时源码或测试报告；缺少凭据时不得用 mock
结果冒充 live smoke。

### 19.2 AgentKit 修复与真实验收记录

真实 Provider 首次验收暴露了三处 AgentKit 结构化输出问题，已按 Red-Green-Refactor 修复并以
`0.2.1` 发布：

1. Planner 的 `hypotheses`、`steps` 原 schema 只声明 array，模型会生成 string array；现在
   `items` 完整描述 HypothesisDto/StepDto 对象、必填字段和 StepStatus enum。
2. Reporter 的 `rootCauseCandidates` 同样缺少对象 schema；现在完整描述 candidate 对象，其他
   ID/action/information 集合也显式声明为 string array。
3. Reporter 原请求只有问题文本，没有 plan/evidence ledger，模型无法引用合法 evidence ID；现在
   发送 question、plan 与 evidence 的安全投影，并明确禁止编造 evidence/hypothesis ID。没有证据
   时，所有 evidence ID 数组必须为空且 candidate 必须 `confirmed=false`。

2026-07-30 的验收结果：

| 层级 | 结果 |
| --- | --- |
| AgentKit clean verify/install | Kernel 单测 693（skip 2）+ IT 4；Diagnosis 128；Coding 29；CLI 71，全部通过 |
| AgentKit 真实 DiagnoseEngine | `reason=SUCCESS`，7 个 chunk，checkpoint 非空，Planner/Reporter 结构化校验通过 |
| agent-web Spring Flow | HTTP、SQLite、Router、SSE replay、两轮 history/checkpoint、rewind、stop、cleanup 全部通过 |
| agent-web 默认后端 | 1429/1429 通过 |
| 前端 Agent Catalog 单测 | Vitest 4/4 通过，覆盖 NATIVE offer、可选性与默认 Agent 约束 |
| 前端 NATIVE 选择 E2E | Playwright 3/3 通过，覆盖桌面提交 `NATIVE/test`、移动端选择与 runtime disabled 历史只读 |
| 可执行 JAR | diagnosis 0.2.1 与 kernel 0.2.1 各一个；agentkit-cli 为零 |
| 真实 agent-web 第一轮 | Catalog NATIVE available；run SUCCEEDED；7 chunk + 1 terminal；cursor replay 成功 |
| 真实 agent-web 第二轮 | run SUCCEEDED；7 chunk + 1 terminal；引擎收到 `historySize=2, hasSnapshot=true` |
| Web 持久化 | 4 条消息、2 条非空 assistant 消息；两轮 Reporter validation passed |

真实 Provider 验收时凭据仅注入临时进程，没有写入仓库、checkpoint、消息或日志。最终配置进一步
要求通过 `AGENT_NATIVE_API_KEY`/`AGENT_NATIVE_BASE_URL` 独立注入，并由测试证明标准 OpenAI
变量不能影响 NATIVE。Provider 的自动工具选择和完整工具结果回传均可用。该 Provider 对“强制指定
function”的 `tool_choice` 结构存在非标准差异，但当前 AgentKit 使用自动工具选择且未发送该字段，
因此不影响本阶段；未来若引入强制 tool choice，应在 Provider adapter 中显式建模 capability，不能
污染通用 schema。

完成定义：

1. 上表自动化场景和本次真实 Provider live smoke 均通过；
2. mvn test、前端 unit、Playwright 目标用例通过；
3. dependency tree 和 fat JAR 内容经 CI 断言；
4. disabled 模式可作为即时回滚路径；
5. NATIVE 不能从任何非 CHAT 入口触发；
6. 真实 Provider 完成多轮与 SSE replay；stop、rewind 和 cleanup 由真实 Host 边界的确定性
   Spring Flow 覆盖。

### 19.3 最终真实日志诊断与生产能力验收

在第 19.2 节的结构化 schema 和两轮上下文验收之后，又通过 agent-web 的真实 CHAT 入口执行了
一轮“定位最近两个小时 agent-web 线上错误”的完整诊断。该轮不是直接调用 AgentKit 的演示程序，
而是经过 Catalog、ChatSession、ChatRun、`RoutingAgentGateway`、`NativeDiagnosisAgentRuntime`、
DiagnoseEngine、真实 OpenAI-compatible Provider、本地只读日志后端、SQLite 持久化和 SSE replay
的端到端验收。

真实 smoke 依次暴露并修复了六类仅靠 mock 难以发现的问题：

1. **replan 丢失宿主诊断范围并破坏状态机。** replan 后的 scope 曾退化为 unknown，继而误报
   `NO_LOG_DATASOURCE`；case 已进入 BLOCKED 后，晚到的工具回调又尝试写 Evidence，最终被 Web
   映射成通用“Agent 执行失败”。现在 replan 始终保留 conversation context、宿主时间与时区、
   environment、service、data source 和 capability/resource generation；主 Agent prompt 注入
   host-approved environment、service、绝对起止时间、工具和标识符。replan 返回 NEED_INFO/BLOCKED
   时立即取消 Agent loop，晚到回调不能再向非 RUNNING case 写入 Evidence。
2. **本地日志全局预算饿死后续文件。** 旧的 `LocalFileLogQueryClient` 按 `Files.walk()` 顺序从文件
   开头读取，第一个大日志可能耗尽 10,000 行全局预算，使时间范围内的后续日志完全不可见。现在
   候选文件确定性排序，按剩余文件公平分配 line/byte budget，并从各文件最近尾部读取；real-path、
   symlink、allowlist、deadline 和脱敏边界保持不变。
3. **Reporter 只能看到 Evidence summary。** 工具结果已经包含异常堆栈时，Reporter 仍可能因为
   summary 只有文件头而声称“没有日志正文”。现在 Reporter context 同时投影受限长度的
   `rawExcerpt`、`toolUseId` 和 metadata，并把它们显式标记为不可信诊断数据；Evidence 截断使用
   头尾保留策略，使位于文件尾部的真正异常堆栈不会被静默丢弃。
4. **checkpoint 时间格式不是 ISO-8601。** `DiagnosisStateCodec` 已注册 Java Time module 但仍写
   数字 timestamp。现在新 snapshot 显式禁用 timestamp，start/end 为 ISO-8601 字符串；codec
   继续接受旧 v2 数字值并在下一次编码时迁移，避免升级后丢失历史状态。
5. **NATIVE invocation 的 `input_json` 变成字面量 `null`。** 原 stream start 不携带 input，delta
   又只靠 ThreadLocal block-index 关联；跨线程消费时 callId 丢失。AgentKit 现在在每个
   `input_json_delta` 附带非敏感 `tool_use_id`，Web extractor 优先按 ID 关联并保留旧 Claude
   fallback，缺失 start input 规范化为 `{}`。三线程投影测试证明最终持久化的是完整 JSON object。
6. **并行工具结果 producer 存在竞态。** 多个 `onToolUseEnd` 会同时修改 listener 的 init、usage、
   content-block 状态并并发调用 host consumer，SQLite 中间态可能乱序。listener 的全部状态变更
   与 emit、host tracker 的 accept/finish 现已串行化；四线程测试证明 consumer 从不并发进入，
   每个 tool_result 只出现一次，真实 run 最终 9/9 invocation 全部收敛为 SUCCEEDED。

agent-web 的 OPERATIONAL 装配同时启用 `PlanGuardMode.ENFORCE`。因此模型生成的每个 `LogQuery`
都必须服从宿主批准的环境、服务、绝对时间范围和工具 allowlist；不能靠 prompt 自觉越过后端边界。

最终真实 Provider 验收记录如下（运行标识、数据库行和 fixture 已清理，文档不保留真实连接值）：

| 项目 | 验收结果 |
| --- | --- |
| Session | 受控隔离会话；验收后删除 |
| ChatRun | 受控新建 ChatRun，终态 `SUCCEEDED`；验收后删除 |
| Engine / model | `reason=SUCCESS`；`gpt-5.6-sol` |
| 宿主批准范围 | `environment=test`，`service=agent-web`，host Clock/ZoneId 解析的最近两小时绝对半开区间 |
| 工具执行 | 9 次真实 `LogQuery`，均为 `NATIVE / LIVE / SUCCEEDED`，且使用同一批准范围 |
| Evidence 可追溯性 | 9/9 Evidence 的 `toolUseId` 均可回连唯一 `chat_tool_invocation.provider_call_id`；metadata 包含 environment、service、dataSourceId、queryStart/queryEnd、matched/returned/truncated、retryCount、backendStatus、success 和 offPlan |
| invocation input | 9/9 `input_json` 均为可解析 JSON object，不含字面量 `null`；service/startTime/endTime 全部与 Plan 一致，9 个 input delta 均携带唯一 `tool_use_id` |
| checkpoint | schemaVersion 2、status DONE；start/end 是 ISO-8601 字符串，窗口精确 7200 秒；9 条 Evidence 的环境、服务和查询范围与 Plan 完全一致 |
| Reporter | 生成 1 个结构化 `diagnosis_report` 并检出受控连接池耗尽/超时 Evidence；报告中的 3 条 missingInformation 是后续补证建议，不改变成功终态 |
| SSE | 持久化 1001 个事件：2 个 run status、998 个 chunk、1 个 terminal；含 9 个 tool-use start、9 个显式 tool_use_id delta、9 个 tool_result、异常 marker 与 1 个 diagnosis report |
| 用户体验 | 不再反问日志平台、环境、地域、时区或要求用户复制已有日志正文，也不再降级成通用执行失败 |

最终质量与制品门禁：

- AgentKit：kernel 700、diagnosis 252，合计 952 项，0 failure/error、2 skipped；kernel contract IT
  另有 4/4 通过；
- agent-web：完整非 live `verify` 1552/1552，ArchitectureTest 8/8、JaCoCo 全部规则通过；启动脚本
  使用最新 AgentKit 再执行默认后端 1468/1468；
- 生产前端 typecheck/lint/build 通过；独立测试工程 typecheck 通过，15 个文件、138 项测试全绿；
- Spring Boot 可执行 JAR 只包含 `agentkit-agent-diagnosis-0.2.1.jar` 与
  `agentkit-kernel-0.2.1.jar`，不包含 `agentkit-cli`；
- 新增 Java 文件的 JavaDoc `@author alex` 审计结果为 AgentKit 70/70、agent-web 64/64；
- PMD/P3C 扫描成功退出，但 `failOnViolation=false`，当前 704 条告警是非阻断存量基线，不能把
  此结果表述为“零告警”；
- `scripts/service.sh` 确实执行 Vite production build，Maven 将 50 个 `frontend/dist` 资源复制到
  fat JAR；target JAR 与 `app/agent-web.jar` SHA-256 一致；
- Provider secret 对两仓 1959 个源码/未忽略文件、测试报告、JAR、32 个普通/gzip 日志、消息、
  ChatRun event、工具调用、checkpoint 和 SQLite 活跃列/主库/WAL/SHM 的扫描命中均为 0；一次只
  存在于已释放索引单元的历史 endpoint 残留经 REINDEX/VACUUM 物理清除，`integrity_check=ok`；
  真实 endpoint 和 key 未写入本文档；
- smoke session、ChatRun 审计数据、临时用户和受控日志 fixture 均已清理，验收 ID 仅保留在设计
  期间的受控验证记录中，不写入仓库。

并行测试还暴露了 SQLite shared-cache 的瞬态表锁：`chat_run` 写入、
`chat_tool_invocation` 完成态写入以及启动孤儿恢复的 active-run 读取现在共用同一有界重试器。
它只识别 SQLite base code 5/6（因此覆盖 BUSY、LOCKED 及其扩展码），最多尝试 6 次，按
10/20/40/80/80ms 退避；constraint、语法和连接失败不重试，乐观锁 version 只在成功写入后推进。
仓储聚焦测试 15/15 和完整 1552/1552 并行验证均通过，不再留下 `STARTED` 工具或启动失败上下文。

本地统一 release gate 已使用真实 OpenAI-compatible Provider 和目标模型通过；GitHub Provider
smoke 把原始 Maven 输出写入权限受限临时目录，扫描 credential、配置 endpoint 与 HTTP payload，
只上传脱敏 Failsafe 报告，失败时控制台只输出安全分类。Provider key 不作为外部命令参数。

最终制品由 Java 21 启动，验收 PID 为 2389464。`scripts/service.sh` 在启动前固定工作目录，并用
`nohup setsid java ...` 创建独立 session，避免启动命令结束后后台 JVM 被会话回收；health 与
Prometheus 为 HTTP 200，匿名 readiness 为 401，管理员 readiness 显示 test / CONFIGURED /
OPERATIONAL / READY，LogQuery capability READY。真实 metrics 为 run=1、tool calls=9、Evidence=9、
query-window count=9/sum=64800 秒、readiness=1；不存在 `_total_total`，本次无真实 blocker，因此
不导出 blocked sample。结构化 audit 只有 logical ID、环境、工具/数据源、状态、耗时、结果字节和
usage，不含 endpoint、header、body、日志正文或 snapshot。

最终事件中有 1 个 `diagnosis_need_info` 扩展事件来自 report.missingInformation，而不是 Planner
的 USER_INPUT_REQUIRED：最终 Plan 的 missingInputs/blockers 均为 0，case 为 DONE，result 与
ChatRun 均成功。`parseStreamJson` 只渲染 `stream_event` 的 text/tool/tool_result，忽略该非文本扩展，
因此不会把报告补证建议显示成阻塞提问；真正等待用户仍必须由 Plan NEED_INFO 与 run outcome 表达。

以下事项保留为后续优化，不是本轮生产能力验收的 blocker：

1. 并行工具的 Evidence 目前逐条触发 replan；4 个并行结果可能形成 4 次 Provider replan，可在
   后续增加小窗口聚合，降低耗时和 token 成本。
2. case 已为 DONE 时，最终 plan projection 中仍可能存在 PENDING step；不影响终态与报告，但应
   后续统一 case、plan 和 checkpoint 的投影一致性。
3. session delete 按当前产品设计保留 ChatRun 审计行；正式部署需配置明确的 retention policy 和
   cleanup job，避免审计表无限增长。
4. LangChain4j 底层 streaming HTTP 没有稳定的 cancel handle，Provider 请求取消仍是 best effort；
   当前通过有限 timeout、回调隔离和终态幂等保证不会污染会话。

## 20. 发布、回滚与兼容

### 20.1 发布顺序

1. 发布 agentkit-agent-diagnosis 0.2.1 到内部 Maven 仓库。
2. 部署包含新表、Catalog 和 adapter 但 agent.native.enabled=false 的 agent-web。
3. 验证 schema、依赖、Catalog、CODEX/CLAUDE 回归。
4. 注入测试环境 Provider/后端 secrets 和 allowlist。
5. 对内部用户开启 enabled=true，观察错误率、耗时、usage、checkpoint 大小和 stop 延迟。
6. 扩大流量；第一阶段始终保持 NATIVE 非默认。

### 20.2 回滚

首选运行时回滚：

~~~text
agent.native.enabled=false
restart agent-web
~~~

效果：

- 不再允许创建/继续 NATIVE run；
- 历史 NATIVE session 和 checkpoint 保留可读；
- CODEX/CLAUDE 不受影响；
- 无需降级数据库。

若必须回退应用版本，新表是 additive，旧版本会忽略；不要在回滚时删除 checkpoint 表。重新升级
后仍可按消息边界恢复。

### 20.3 版本兼容

stateSnapshot 是 AgentKit 拥有的 opaque payload。agent-web 不解析业务字段，只保存可选
schemaVersion。升级 AgentKit 前必须验证旧 snapshot 可由新版本 decode；若 AgentKit 声明
不兼容，应通过 adapter 明确忽略旧版本 checkpoint 并只用 typed history，不能让反序列化错误
导致整个历史会话不可读。

## 21. 变化点与反模式审计

### 21.1 变化点收敛

| 变化原因 | 收敛位置 | 不应出现的位置 |
| --- | --- | --- |
| 用户是否看见某 Agent | AgentOfferPolicy/Catalog | App.vue 硬编码、AgentType enum 分支 |
| 能否成为默认 | AgentOffer.requireDefaultEligible | RuntimeAgentSettings 自行枚举 |
| runtime 是否可用 | RuntimeRegistry | Controller 探活、数据库 AgentType |
| CLI/进程内执行差异 | AgentRuntime adapter | ChatRun 聚合、ChatController |
| history 传递方式 | HistoryDeliveryMode/runtime capability | provider 名称散落在 PromptBuilder |
| stream 方言 | runtime normalize | 前端按 NATIVE 特判 |
| stop 实现 | Router + runtime | Controller 广播 stop |
| 诊断状态恢复 | DiagnosisCheckpointRepository | chat_session.resume_id、prompt |
| AgentKit 终态翻译 | NativeRunSummaryMapper | ChatRun 从 NDJSON 文本猜测 |
| 安全后端装配 | NativeDiagnosisConfiguration | 用户输入 endpoint |

### 21.2 出现即应阻止合并的信号

- AgentCliGateway 中新增 if type == NATIVE；
- 把 AgentType 上单一选择标记无条件改成 true；
- 前端第三个硬编码 radio，而不引入 Catalog；
- Admin 默认选项直接遍历 AgentType.values；
- Native adapter 写 chat_session.resume_id；
- prompt 同时含 conversation_history 且 RunRequest.history 非空；
- 按 sessionId upsert 单行 snapshot；
- adapter 在 lifecycle complete 前独立写 checkpoint；
- app/domain/interfaces import com.anthropic.agentkit；
- 依赖 agentkit-cli 或启动 java -jar AgentKit；
- HTTP/Dubbo enabled 且空 allowlist；
- 以 RunRequest.env 提示词代替 backend 环境隔离；
- terminal 后仍接受 chunk 或写 checkpoint。

## 22. 目标模型评分

| 维度 | 当前 | 设计目标 | 依据 |
| --- | ---: | ---: | --- |
| 聚合边界清晰度 | 2 | 3 | ChatSession、ChatRun、Offer、Checkpoint 职责和事务边界明确 |
| 变化收敛度 | 1 | 3 | 选择归 Catalog，执行归 runtime，状态翻译归 adapter |
| 不变量保护 | 1 | 3 | surface/default/env/availability、history、checkpoint 均有服务端守卫 |
| 行为与模型一致度 | 1 | 3 | NATIVE 从“枚举存在但不可用”变成有明确用途和生命周期的运行时 |
| 下一变化支持度 | 1 | 2 | 可增加新 runtime/多环境，但单 JVM和底层 HTTP best-effort cancel 仍是边界 |

目标总分 14/15。下一变化支持度暂不评 3，是因为多环境 Engine registry、多实例运行和可取消的
Provider HTTP handle 尚不在第一阶段范围内。

## 23. 设计复核问题与结论

1. NATIVE 是否应该成为全局默认？
   结论：第一阶段不允许；只接受用户在 CHAT surface 手动选择。
2. 运行时关闭后是否隐藏所有 NATIVE 痕迹？
   结论：新会话选择器不显示为可选，但 Catalog 保留描述，历史会话保持可读并标记当前不可用。
3. NATIVE 的多轮状态使用 resumeId 还是 history？
   结论：使用 typed history + 消息边界 checkpoint，resumeId 永远为空。
4. checkpoint 是否可以由 adapter 先保存？
   结论：不可以；必须与 assistant message 和 ChatRun success 同事务。
5. 第一阶段如何避免 env 字段“看似隔离、实际共用后端”？
   结论：只允许一个 bound environment，创建与 submit 双重校验；多环境改为每 env 一个 Engine。
6. 用户点击 stop 是否等于底层 HTTP 已立即断开？
   结论：不等于；UI 表达 CANCEL_REQUESTED，AgentKit 保证回调隔离，底层 HTTP 释放为 best effort。
