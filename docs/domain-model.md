# Agent Web 领域模型

> 基于实读 `domain/` `app/` `infra/` `adapter/` 源码的领域建模手册,不是从目录结构反推的清单。
> 架构:DDD + 六边形,四层(interfaces / app / domain / infra)+ adapter 端口层,按限界上下文分包。
> 视角:每个限界上下文按「职责 → 聚合根与不变量 → 状态机 → 核心规则归属 → 跨域协作 → 分层健康度」展开,`文件名:行号` 为定位锚点(会随提交漂移,定位以符号名为准)。
> 健康度图例:✅ 收口正确　⚠️ 轻度异味(可容忍/务实折中)　🔴 重度异味(建议重构)。
>
> **重要背景**:诊断(diagnose)与飞书 IM 工单(ticket)两个上下文已在「限界上下文重构」中整体移除。残留的 `RunForm.DIAGNOSE` / `SourceType.DIAGNOSE` / `RequirementSource.TICKET_DERIVED` 仅为存量数据反序列化与召回策略兼容,无活跃生产者。当前平台核心是**需求交付流水线**。

---

## 0. 阅读地图

### 限界上下文一览

| 域 | 类型 | 一句话职责 | 聚合根 / 核心 | 建模健康度 |
|---|---|---|---|---|
| **chat** | 核心 | 驱动「用户↔本地 CLI Agent」会话生命周期 | `ChatSession` | ⚠️ 贫血且越界,一致性边界在 DB |
| **requirement** | 核心 | 需求从接入到交付的 9 态流水线 + Agent run 编排枢纽 | `Requirement` | ✅ 表驱动状态机 + 事件外发,建模最扎实 |
| **refinery** | 核心 | 自建轻量 RAG:评分→向量→召回注入(仅 chat 上游) | `RagChunk` | ⚠️ 重排算法整体泄漏在 app |
| **workflow** | 支撑 | 管理台可复用多步 agent workflow,串行执行 | `Workflow` / `WorkflowExecution` | ⚠️ 贫血 CRUD + 异步 runner,无事务 |
| **verification** | 支撑 | 验证 run 终态防腐翻译 + 轮次熔断 | `VerificationRound` / `RoundBreakerPolicy` | ✅ 防腐 + 资源兜底收口 |
| **workspace** | 支撑 | git worktree 隔离的需求工作区生命周期 | `RequirementWorkspace` | ✅ 释放不变量收口聚合 |
| **delivery** | 支撑 | push 分支 + 草稿 MR + SCM webhook 入站编排 | `DeliveryPolicy` / `MergeRequestRef` | ✅ 白名单 + 凭证链 + fail-closed |
| **git** | 支撑 | 单用户 git 身份/凭证,子进程 env 即时注入 | `UserGitConfig` | ✅ 明文不入聚合,密文静态加密 |
| **knowledge** | 支撑 | 关单收割 + 人工审批收件箱 → issue-log 落盘 | `KnowledgeSuggestion` | ✅ 审批不变量收口聚合 |
| **suggestion** | 支撑 | 用户对系统的建议工单 + admin 处理 | `UserSuggestion` | ✅ 干净,copy-on-write |
| **issuelog** | 支撑 | 结构化经验条目落盘工作目录 `docs/issue-log/` | `IssueLogEntry` | ⚠️ 现由 knowledge inbox 单一驱动 |
| **auth** | 支撑 | 可插拔身份校验 + 数据隔离决策 | `ManualSession` / `CurrentUserProvider` | ✅ 端口/适配最干净 |
| **schedule** | 支撑 | cron 周期触发 prompt,复用 chat 链路 | `ScheduledTask` | 🔴 cron 不变量散落三处 |
| **slashcommand** | 支撑 | 扫描并展开 `/cmd args` 为模板体 | `SlashCommandExpander` | ✅ 支撑域里最干净 |
| **worktree** | 支撑 | 按分支批量建/更/删 git worktree | `BranchNameValidator` | 🔴 整域逻辑堆 app |
| **metrics**（读侧）| 读模型 | 管理台运营总览 + 召回可观测性(纯 CQRS) | 无聚合(只读 DTO) | ✅ 读写分治,infra 直连 |

### 跨域协作骨架

```
                    ┌──────────────────────────────┐
  AgentType(shared) │  AgentGateway / AgentCliInvoker │ ← chat / schedule / requirement-run / workflow 共用驱动 CLI
                    └───────────────┬────────────────┘
                                    │
       接入(BOARD/REST/issue) ──► requirement (编排枢纽·9 态状态机)
                                    │  ├─► workspace  (approve→provision worktree+端口租约)
                                    │  ├─► agentrun   (plan/implement/fix/verify prompt 组装)
                                    │  ├─► verification(verify run 终态 + 轮次熔断)
                                    │  ├─► delivery   (deliver-draft: 凭据链→push→草稿 MR)
                                    │  └─► knowledge  (DELIVERED→收割候选→审批落 issue-log)
                                    ▲
       GitLab webhook ──► delivery ─┘  (MR 合并→markDelivered / pipeline 失败→fix 建议 / issue 打标→建需求)

  git.UserGitConfig ── 个人凭证是 delivery 凭据链第一环,身份 env 注入 CLI 子进程
  refinery ◄── chat 会话(唯一上游,经 ConversationView 防腐层) ──► 召回反注入回 chat 消息
  auth.CurrentUserProvider ── chat / suggestion / git 的数据隔离/身份闸
```

- **`AgentGateway`**(adapter 端口)被 chat / schedule / requirement-run 共用;workflow 走 `AgentCliInvoker`(infra)。`AgentType` 放 `domain/shared` 跨域共享。
- **requirement 是编排枢纽**:所有业务不变量收在聚合 + 少数 domain policy,app 只做 load→method→save,却把 workspace / verification / delivery / knowledge / agentrun 串成一条流水线。
- **`req/<id>` 分支命名空间**是 workspace(唯一定义 `RequirementWorkspace.branchFor`)→ delivery(push 白名单)→ webhook(反解需求号)三域共识事实源(⚠️ webhook 侧自建 `BRANCH_PREFIX` 常量,事实源轻微分叉)。
- **旁路降级模式**贯穿全平台:verification 采集、workspace 通知/磁盘监控、delivery webhook 分发、git 解密、knowledge 收割——全部 try-catch 降级、绝不阻断主流程。
- **`agent.requirement.enabled` 总开关**:requirement / workspace / delivery / verification / knowledge 整组 bean 随之启停(装配集中在 `DeliveryInfraConfig` / `WorkspaceInfraConfig`)。

---

## 1. chat — 会话对话(核心域)

**职责**:管理「用户 ↔ 本地 CLI Agent(Claude/Codex)」的会话生命周期——建会话、追加消息、流式驱动 CLI 并落库回应、支持回退重开 / resume 续接 / 会话级反馈,绑定到一个工作目录 + 一种 agent 类型。

### 聚合根 ChatSession(`domain/chat/ChatSession.java:20`)

- **标识**:`id` UUID。字段:`agentType/workingDir/createdAt/messages/resumeId/title/env/clientIp/userId/userName/feedback`。
- **不变量**:**几乎没有**。构造只做字段赋值 + messages null→空 list。行为方法仅 `requireDeletableBy`(`:84`,创建者删除校验)与 `addMessage`(死代码,见下)。
- **无侵入**:knowledge-harvest / git-config / requirement 都是 app 层横切,**不进聚合**——git 身份经 `session.getUserId()` 透传给 `gateway.runOnce/runStream`,harvest/requirement 在别的域。
- **`addMessage` 是死代码**:应用层全程不调它,消息一律走 `sessionRepository.addMessage(sessionId, msg)` 直插库。rewind / resume / 反馈挂载 / 历史前缀注入——聚合内一个都没有,全散在 app。

### 值对象 / 枚举

- **ChatMessage**:`id/role/content/timestamp`,`role` 是裸 `String`("user"/"assistant"),无枚举约束——app 层满屏 `"user".equals(role)` 的根因。
- **Feedback**:`rating + comment + updatedAt`,值语义 `equals/hashCode`,允许均空。**FeedbackRating**:`CORRECT / PARTIALLY_CORRECT / INCORRECT`。
- **AgentType**(`domain/shared`):`CODEX / CLAUDE`,跨上下文共用。

### 仓储与存储分层

- **写侧 `SessionRepository`**:签名纯 domain 类型,承载 lifecycle。但写口仍残留若干读方法(`findAll` / `findByShareToken` / `findIdsWithLastMessageBefore/After` 供 refinery 调度),非纯写。
- **读侧 `ChatSessionQueryService`**(`app/ChatSessionQueryService.java:15`)✅:CQRS 读接口(`findSummaryPaged` / `findMessageViews` / `findSharedView`),与写侧分治,impl `SqliteChatSessionQueryService`。**读已从 `SessionRepository` 抽出**(旧模型的一处越界已收敛)。
- **L1 `InMemorySessionRepo`**:实现 `SessionCache` 端口,`ConcurrentHashMap` 裸缓存活跃会话。
- **L2 `SqliteSessionRepo`**:**唯一**的 `SessionRepository` 实现。
- **`JsonFileSessionRepo`**:**孤儿 bean**——标 `@Repository` 但不实现任何接口,生产零注入。文档曾宣称的「JSON 备份层」未接线。
- **协作真相**:L1/L2 非经典 read-through/write-through,而是应用层手工编排两端口——`getSession` 先查 cache、miss 查 DB 回填;写入时 cache 与 DB 各写各的,cache 里的聚合会与 DB 失配,truncate 后靠 `sessionCache.remove` 强制失效。

### 端口 AgentGateway(`adapter/AgentGateway.java`)

对选定 `AgentType` 在 `workingDir` 执行 prompt。同步 `runOnce` + 流式 `runStream`(sessionId/resumeId/env/timeout/onChunk/onExit)+ `stopStream`/`isRunning` + 方言适配 `extractResumeId`(抠 CLI 侧 session id)、`normalizeChunk`(一条 raw 事件展开 0..N 条统一前端事件)。实现是 infra `AgentCliGateway`(按 `CliDialect` 策略路由)。

### 应用编排 ChatAppServiceImpl.streamMessage(`:247-332`)

1. `getSession`(cache→DB),null 抛异常。`effectiveEnv = s.getEnv()`——env 创建时写定、之后不可变。
2. **RAG 召回** ✅:`recallEnabled && Optional<RefineryRecaller>.isPresent()` → `traceForChat(message, workingDir)`,命中发 `recall` SSE 帧 + 注入增强 prompt(斜杠命令/0命中/异常静默跳过)。
3. **斜杠展开**:未被 recall 改写时 `commandExpander.expandIfCommand`。截断后 history-prefix 注入 + recall observation 记录(`RecallObservationRecorder`,`Optional` 注入)。
4. 先持久化原文 user 消息(走 repo 直插,不经聚合)→ 建无超时 `SseEmitter(-1L)` + 15s keepalive → `gateway.runStream` 驱动 CLI → `onChunk` 首帧抽 resumeId 落库一次 + normalize + 推 → `onExit` 累积响应存为 assistant 消息。

**事务边界**:**全程无 `@Transactional`**。user 消息 / resumeId / assistant 消息各自 autocommit,无原子性——与「app 做编排 + 事务」的承诺有落差。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 截断/回退删 ≥fromId + 清 resume_id | `SqliteSessionRepo.truncateFrom` + `ChatAppServiceImpl` | 🔴 规则在 app+infra,聚合无 rewind |
| 回退后取 prefill | `ChatAppServiceImpl`(遍历 `s.getMessages()` 找特定 id) | 🔴 典型泄漏 |
| user/assistant 角色分流 | 多处 `"user".equals(getRole())` | 🔴 VO 缺 `MessageRole` 枚举 |
| resumeId 抽取落库(首帧一次) | `StreamChunkHandler` | ✅ 协议适配收在流处理器 |
| assistant 终局文本抽取 | `StreamOutputExtractor` | ✅ 纯协议解析 |
| 会话按 user_id 隔离 | `CurrentUserProvider` 决策 + `SqliteSessionRepo` 拼 WHERE | ✅ 决策在 domain/auth |
| 创建者才可删 | `ChatSession.requireDeletableBy` | ✅ 收在聚合 |

### 分层健康度

**实锤泄漏**:① app 遍历聚合集合找特定 id;② 对 getter 做语义判断(`"user".equals`);③ 聚合方法被架空——`addMessage` 死代码,消息/feedback/resumeId 全绕过聚合直插库,**真正的一致性边界是 DB 不是聚合根**。
**好信号**:聚合无注入 Repository;`getMessages` 返回不可变视图;读侧已拆 `ChatSessionQueryService`;用户隔离决策在 domain/auth。
**重构方向**:rewind/resume/反馈收成 `ChatSession` 业务方法 + `@Transactional`;加 `MessageRole` 枚举;决断 `JsonFileSessionRepo`(接回或删除)。

---

## 2. requirement — 需求交付(核心域)

**职责**:owns 一条交付需求从接入到交付/归档的完整生命周期——9 态状态机(含人工审批门 + 计划门)、工作区挂载、agent 驱动的 implement/fix/verify run、append-only 审计事件流。它是驱动 workspace / delivery / verification / knowledge / agentrun 的**编排枢纽**,却把全部业务不变量收在聚合 + 少数 domain policy。

### 聚合根 Requirement(`domain/requirement/Requirement.java`)

- **标识**:`RequirementId id`(VO 包 string)。工厂 `create(...)`(`:54-67`)起于 `INTAKE` 并记 `TYPE_CREATED`;重建构造器(`:70-102`)收集构造不变量(id/source/title/owner/status 必填,title/owner trim,participants 防御拷贝)。
- **可变态**:`title/description/status/statusBeforeSuspend(suspend 回归目标)/plan(AgentPlan)/participants/workspaceId(跨聚合 ID 引用)/updatedAt` + `pendingEvents` 外发箱。
- **方法内强制的不变量**:
  - 每次迁移先 `RequirementTransitions.assertAllowed(status, action)`。
  - `approve` 要求非空 plan(`PlanRequiredException`)+ owner 授权(`RequirementAccessPolicy.canApprove`)。
  - `startImplement` 要求已挂 workspace(`isBlank(workspaceId)→IllegalStateException`)。
  - `applyVerificationOutcome` 在 BLOCKED/DEPLOY_FAILED 硬置 `statusBeforeSuspend=IMPLEMENTING`,使 resume 回 IMPLEMENTING 而非 VERIFYING。
  - `pullEvents()` 排空外发箱供仓储 flush;重建的聚合外发箱为空。

### 状态机 RequirementStatus + RequirementTransitions

9 态:`INTAKE / PLANNED / APPROVED / IMPLEMENTING / VERIFYING / REVIEW / DELIVERED / SUSPENDED / ARCHIVED`,终态 = `DELIVERED || ARCHIVED`。合法性**数据驱动**在 `RequirementTransitions.buildTable()`(T1–T15):

| From | Action → 方法 | Target | 触发方 |
|---|---|---|---|
| INTAKE | ATTACH_PLAN → `attachPlan` | PLANNED | PlanRunService 完成 / 人工 `POST /plan` |
| PLANNED | ATTACH_PLAN(replace) | PLANNED | 计划重跑 |
| PLANNED | REJECT_PLAN → `rejectPlan` | INTAKE | 人工 |
| PLANNED | APPROVE → `approve` | APPROVED | **仅人工** `POST /approve` |
| APPROVED | ATTACH_WORKSPACE → `attachWorkspace` | APPROVED(不改态) | WorkspaceAppService(actor `system`) |
| APPROVED | START_IMPLEMENT → `startImplement` | IMPLEMENTING | ImplementRunService / 人工 |
| IMPLEMENTING | START_FIX → `startFixRun`(仅审计) | IMPLEMENTING | FixRunService |
| IMPLEMENTING | START_VERIFY → `startVerify` | VERIFYING | VerifyRunService / 人工 |
| VERIFYING | APPLY_VERIFICATION_OUTCOME → `applyVerificationOutcome` | REVIEW(VERIFIED) / SUSPENDED(BLOCKED/DEPLOY_FAILED) | VerifyRunService(`system:verify`) |
| REVIEW | MARK_DELIVERED → `markDelivered` | DELIVERED | webhook(`system:webhook`)/ 人工 `/deliver` |
| REVIEW | REQUEST_CHANGES → `requestChanges` | IMPLEMENTING | 人工 |
| SUSPENDED | RESUME → `resume` | statusBeforeSuspend | 人工 |
| 任意非终态 | SUSPEND / ARCHIVE | SUSPENDED / ARCHIVED | 人工 |
| DELIVERED, ARCHIVED | — | — | T15:一切迁移拒绝 |

非迁移审计钩子(不改态):`recordMrDrafted` / `recordFixSuggestion` / `startFixRun`。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 迁移合法表(T1–T15) | `RequirementTransitions:25-49` | ✅ domain |
| approve 计划门 + owner 门 | `Requirement:138-143` + `RequirementAccessPolicy.canApprove` | ✅ domain |
| startImplement 前置挂 workspace | `Requirement:189-191` | ✅ domain |
| suspend/resume 回归目标 | `Requirement:232-234, 261-285` | ✅ domain |
| 活跃需求配额 | `RequirementQuotaPolicy.assertWithinActiveQuota`(计数由 app 读侧喂) | ✅ 规则在 domain,app 供数 |
| 单需求 run 配额 | `RequirementQuotaPolicy.assertWithinRunQuota`(计数来自 `RequirementRunTracker`) | ⚠️ tracker 是 app 层内存态,重启即丢 |
| 接待人解析(作者→回落→拒收) | `IntakeOwnerPolicy.resolveOwner` | ✅ domain,目录谓词注入 fail-open |
| 「仅人工可 approve」 | 靠入口收敛(仅 `RequirementController` 可达),非代码强制 | ⚠️ 约定式,`RequirementAccessPolicy` javadoc 自陈 |

### 应用编排

- `RequirementAppService`:**薄** lifecycle——每次变更 `load → 聚合方法 → repository.update`;`createWithRef` 先读侧配额检查再存(ID 冲突重试);`markDelivered` 额外静默触发 knowledge harvest;**无 `@Transactional`**(事件在同一 `update` JDBC 调用内 flush,但无包裹事务)。
- **Run 编排器**(全在 `DeliveryInfraConfig` `@Bean`,受 `agent.requirement.enabled` 门控):`PlanRunService`(不碰 approve,人工门)/`ImplementRunService`(→ `provisionFor` 挂 workspace → `startImplement` → worktree 内带 `AGENT_DEV_PORT`+toolchain env 跑)/`FixRunService`(拉最近 ≤3 条 `CHANGES_REQUESTED`/`FIX_SUGGESTED` 事件做反馈上下文)/`VerifyRunService`(见 §5)。`ExternalIntakeService.intake`(幂等 apiKeyName+Idempotency-Key)。
- **异步 + SSE**:`RequirementRunLauncher` 是唯一执行底座——同步配额 assert + `runTracker.increment`,再 `runExecutor.execute` 到缓存线程池;chunk 经 `RunEventBus` SSE 广播(stream key `req-run-<id>`);`GET /api/requirements/{id}/run-stream` 暴露,run 端点均返 202。

### 端口与适配

- 写:`RequirementRepository` → `SqliteRequirementRepo`(plan/participants JSON 序列化,`flushEvents` 把外发箱写 `requirement_event`)。读:`RequirementQueryService` → `SqliteRequirementQueryService`(视图 `RequirementBoardItem`/`RequirementDetail`/`RequirementEventView`/`RequirementEventSearchItem`)。
- 外部端口(`adapter/`):`RequirementDocFetcher`(→ `ShimoDocFetcher`)、`ScmGateway`/`ScmCredentialStore`(→ GitLab)、`WorkspaceProvisioner`、`AgentGateway`、`UserDirectory`(→ 降级 `PermissiveUserDirectory`)、`NotificationGateway`(→ `NoopNotificationGateway`)、`VerificationArtifactCollector`(→ `FlowstateArtifactCollector`)。

### 死代码 / 异味

- `RequirementAccessPolicy.canOperate` / `Requirement.addParticipant`(participants 从无流程填充)——**无调用方**,死代码。
- `RequirementSource.TICKET_DERIVED`——历史枚举,无生产者。
- run 服务的短参构造器(`recallPolicyFactory=null`)是休眠兼容口,`@Bean` 恒用全参。
- lifecycle 变更非 `@Transactional`(仅 `WorkspaceAppService.provisionFor` 是)。

---

## 3. refinery — 知识精炼 / 向量召回(核心域)

**职责**:把静默 chat 会话用 LLM 压成「可复用经验结论」并向量化入库,新对话发生时按语义召回拼成 `[历史参考]` 注入 prompt——自建轻量 RAG(embed + 内存余弦扫描,不引 sqlite-vec)。**诊断线摘除后现仅消费 chat 单上游。**

### 聚合根 RagChunk(`domain/refinery/RagChunk.java`)

- **标识**:`id` UUID(`INSERT OR IGNORE`)。构造期不变量(私有构造):必填 `requireNonNull`;`score ∈ [0,1]` 非 NaN;`embedding.length > 0`;演进字段空值兼容默认(`sourceType→CHAT`、`tier→EXPLORATORY`、`env→"unknown"`)。
- **注意**:`expires_at = now + ttlDays[category]*86400` 这条规则**不在聚合内**,而在 `RefineryAppServiceImpl.buildChunk`——聚合只把 `expiresAt` 当可空字段被动接收(分层瑕疵)。
- **软删/归档**:`archive(Instant)` 幂等保护。召回靠 `archived_at IS NULL` 过滤。
- **状态迁移**:`upgradeTier(TrustTier)` guard `newTier.ordinal() > tier.ordinal()` 拒降级。**🔴 只有单测在用,生产走 SQL UPDATE 绕过方向校验**。

### 核心概念

- **TtlCategory**(CODE/DEPLOY/BUSINESS/GENERAL,管过期天数)与 **TrustTier**(VERIFIED/PENDING/EXPLORATORY,管召回池过滤)正交。`DefaultTrustTierPolicy` 是唯一「判可信度」的位置:CHAT→EXPLORATORY(现唯一活跃生产);DIAGNOSE 各档规则仅为读存量 chunk 保留。
- **CosineSimilarity**:纯静态工具,余弦 ∈ [-1,1],✅ 落 domain。
- **ConversationView / ConversationTurn / RefinedContent**:`ConversationView` 是上游投递进子域的**统一入参 VO,子域只看 view、不 import 任何上游聚合类**(防腐层);`RefinedContent` 是 LLM 输出五字段(title/triggerSignals/context/process/conclusion)。
- **SessionRefineryState**:幂等游标。哨兵常量 `LAST_ERROR_BELOW_THRESHOLD="score below threshold"` 区分「有意丢弃不重试」与「真失败重试」。**DiscardedRefineRecord**:below-threshold 留痕,供管理台展示 + 阈值校准。

### 两条主管道

**管道 1 — refine + ingest**:`ChatViewBuilder.build` 恒产 `SourceType.CHAT` view(空消息跳过)→(chat)`shouldSkip` 秒级幂等 → `ConversationRefinery.refine`(`sliceAndSanitize` token-budget 倒序切片 + 脱敏正则 → 填 prompt → `invokeCli` 瞬态退避重试 → `LlmJsonExtractor` → 7 字段 JSON,任何环节失败统一 `RefineException`)→ 阈值丢弃(`score<0.5` 旁路留痕)→ `embeddingClient.embed` → `buildChunk` 算 expiresAt + `tierPolicy.decide` → `chunkRepo.save`(embedding 经 `EmbeddingCodec` 编小端 IEEE754 BLOB)。

**管道 2 — recall + rerank**(`RefineryRecaller.recallWithFilter`,`@ConditionalOnProperty agent.refinery.enabled`):`embed(query)` + `chunkRepo.findActive(now)` 全表扫 → 池过滤(chat 默认仅 CHAT,`crossSourceEnabled` 才纳入 DIAGNOSE+VERIFIED 存量)→ **余弦硬闸**(`vectorSim<minVectorScore` 融合前出局,融合分含与 query 无关的 `γ·decay·score` 常数底会污染绝对/相对阈值,余弦是未污染语义信号,支持 tier 分层阈值)→ **融合分** `final=α·cosine+β·jaccard(query,triggerSignals)+γ·decay·score`(默认 0.7/0.2/0.2,jaccard 走 CJK 相邻二元组分词,半衰期 30 天)→ 降序 + 绝对闸 `minScore` + 相对闸 `topScore·minScoreRatio` → `traceForChat` 拼 `[历史参考 i/n]` 前缀注入。

### 触发器

- **ChatRefineryTrigger**(`infra/refinery/scheduling`):`scheduleWithFixedDelay`,拉候选(LEFT JOIN:从未评过 OR 有新消息 OR 失败可重试)→ 串行 `refineAndIngest` → tick 末 `archiveExpiredBefore` 软删过期。
- **`DiagnoseViewBuilder` / `DiagnoseRefineryTrigger` 已删除**——诊断线摘除。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 余弦相似度 | `CosineSimilarity.cosine` | ✅ domain |
| 融合分/余弦硬闸/绝对相对闸/CJK 分词/时间衰减/池过滤 | `RefineryRecaller`(app) | 🔴 **核心领域算法整体在 app** |
| expires_at = now + ttlDays·86400 | `buildChunk`(app) | 🔴 **构造期不变量在 app** |
| tier 决策 / shouldIngest | `DefaultTrustTierPolicy` | ✅ domain policy |
| score∈[0,1] / embedding 非空 / archive 幂等 / upgradeTier 方向 | `RagChunk` | ✅ domain 聚合(但 upgradeTier 生产被绕过) |

### 分层健康度

**好**:`ConversationView` 防腐层到位,聚合不变量正确收口,`TrustTierPolicy`/`CosineSimilarity` 落 domain。
**异味**:① 重排算法整体在 app(建议下沉 `RecallRanker` 领域服务);② `expires_at` 构造期不变量逃逸 app;③ `upgradeTier` 方向校验被生产 SQL 绕过(「只能向上升级」不变量生产里没生效)。

---

## 4. workflow — 多步 agent 工作流(支撑域)

**职责**:定义可复用、管理台编写的串行 agent workflow(有序 prompt-template 步骤,绑一个 `AgentType`/workingDir),一次一 run 执行,持久化 run 记录 + 逐步执行记录及输出。相比 requirement 是薄得多的 CRUD + 异步 runner,3 态、无真正状态机。

### 聚合 / 实体(`domain/workflow/`)

- **Workflow**:定义聚合,`String id`,构造 `validate`:name/agentType/workingDir/非空 steps 必填,`ensureUniqueStepNames` 拒重名。全 `final` 不可变,"update" 是整体重建。含 `List<WorkflowStep>`(防御拷贝)。
- **WorkflowExecution**:一次 run,`status` 默认 `RUNNING`,`markSucceeded()`/`markFailed(msg)`。
- **WorkflowStepExecution**:一步 run,`status/output/errorMessage/finishedAt`,`markSucceeded(result)`/`markFailed(msg)`。
- **WorkflowStep** VO:name + promptTemplate(均非空)+ `timeoutSeconds`(≤0 用 CLI 默认)。

### 状态机 WorkflowStatus

`RUNNING / SUCCEEDED / FAILED`,Execution 与 StepExecution 共用。**无迁移表、无合法性守卫**(与 requirement 对比鲜明):只有两个终态 setter,由 `WorkflowRunner.run`/`runStep` 驱动;某步失败即 rethrow,中止余下步骤并整体 FAILED。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 非空 steps + 步骤名唯一 | `Workflow.validate`/`ensureUniqueStepNames` | ✅ domain |
| 必填字段校验 / 步骤 prompt 非空 | `Workflow`/`WorkflowStep` 构造 | ✅ domain |
| Execution/Step 终态迁移 | 实体 `markSucceeded/markFailed` | ✅ domain |
| 「禁用 workflow 不可 run」 | `WorkflowAppService.run:135-137`(抛 `IllegalStateException`) | ⚠️ 业务规则在 app,非聚合 |
| 串行执行 + 输出串联 | `WorkflowRunner.runSteps/runStep` | ✅ app 编排 |

### 应用编排

- `WorkflowAppService`:CRUD + `run`(load → 拒禁用 → 存 `RUNNING` execution → `executor.execute(() -> workflowRunner.run(...))` 异步 fire-and-forget,立即返回)。无 `@Transactional`。
- `WorkflowRunner`:串行——每步渲染模板(注入 inputs + 前序步输出)→ `promptAssemblyService.assemble`(`RunForm.WORKFLOW_STEP`,失败降级原始 prompt)→ 存步记录 → `cliInvoker.invokeSync(agentType, workingDir, prompt, timeout)` → 输出入 `stepOutputs` 供下游。
- **无 SSE/无流式**:workflow 走 `invokeSync`,观测靠轮询 execution/step 记录(`AdminWorkflowController`)。注意 workflow 用 `AgentCliInvoker` 直连,**不走 `AgentGateway`**。

### 端口与适配

`WorkflowRepository` → `SqliteWorkflowRepo`;`WorkflowExecutionRepository`(execution + step CRUD/分页)→ `SqliteWorkflowExecutionRepo`;唯一外部整合 `AgentCliInvoker`(infra/cli)。Controller `AdminWorkflowController` + `interfaces/dto/Workflow*`。

### 死代码 / 异味

贫血域:`WorkflowStatus` 无守卫;"禁用不可 run" 在 app 而非聚合;`run` fire-and-forget 无 run 级取消/超时(仅步级);create/update/run 无 `@Transactional`,execution + step 记录跨多次独立 JDBC 写无原子性;`assemblePrompt` 吞所有装配异常静默回退(韧性 vs 掩盖误配)。

---

## 5. verification — 验证(支撑域)

**职责**:把一次验证 run 的终结事实收敛为平台中性终态并留痕轮次,供轮次视图与资源熔断消费。核心是「防腐翻译」(外部状态词汇 → 中性枚举)与「资源兜底熔断」(防 run 空转烧钱),**不判「该验几轮」**——那由 run 内 agent 自判。

### 核心构件(`domain/verification/`)

| 构件 | 关键点 |
|---|---|
| `VerificationRound` | 只追加记录。构造不变量:`requirementId` 非空、`round>=1`、`verdict` 非空、`failedCount` clamp `>=0`。`deployRef` 恒 null(工厂 `record()` 传 null),`evidenceRef` L1 恒 `"requirement_artifact:"+id`。 |
| `VerificationOutcome` | 枚举 `VERIFIED / BLOCKED / DEPLOY_FAILED`。核心不变量 `fallbackForExit`:**exit≠0→DEPLOY_FAILED;exit==0 但无证据→BLOCKED(交人工,绝不自动放行)**。 |
| `RoundBreakerPolicy` | 纯域策略,默认阈值 3(连续失败)/2(同因失败),构造校验阈值 `>=1`。 |
| `CollectedVerification`/`CollectedArtifact`(`adapter/`) | 采集 DTO,`outcome` 可 null(降级信号),与 `degradeReason` 互斥。 |

### 状态机

无显式状态机;`VerificationOutcome` 是终态枚举。翻译映射在 infra `FlowstateArtifactCollector.mapState`:一组外部状态词 → VERIFIED / DEPLOY_FAILED / BLOCKED,未知 → null。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 连续失败达上限熔断 | `RoundBreakerPolicy`(逆序扫到 VERIFIED 清零) | ✅ domain |
| 同因(同 verdict)连续失败熔断 | `RoundBreakerPolicy` | ⚠️ 同因以 verdict 近似(L2 failed-case 签名待落地) |
| 无证据不自动放行(exit0→BLOCKED) | `VerificationOutcome.fallbackForExit` | ✅ domain |
| 采集旁路:失败只降级不卡状态机 | `VerifyRunService.completeVerifyRun`(全 try-catch) | ✅ app |
| 外部词汇防腐翻译 | `FlowstateArtifactCollector.mapState` | ✅ infra(域只见中性枚举) |

### 应用编排

`VerifyRunService`(在 `app/requirement/` 包):`startVerifyRun` → 配额 → **熔断闸 `assertCanStartNextRound`** → 组装 prompt → 异步 `launcher.launch` 回调 `completeVerifyRun` → 采集 → 终态映射(缺证据兜底)→ `applyVerificationOutcome`(actor 恒 `system:verify`)→ 轮次落库。**注意**:8 参构造器(无 roundRepository)会使熔断闸与轮次记录静默失效,生产装配用 9 参,故 8 参是存量兼容 dead path。

### 端口与适配

`VerificationRoundRepository`(只追加)→ `SqliteVerificationRoundRepo`(INSERT-only,`verification_round` 表);`ArtifactStore`(接口放 **app** 守 ArchUnit)→ `SqliteArtifactStore`(`requirement_artifact` 表);`VerificationArtifactCollector`(adapter)→ `FlowstateArtifactCollector`(内联上限 64KB)。

### 死代码 / 异味

🔴 `VerificationRound.deployRef` 恒 null、`failedCount` L1 恒 0(列存在无写入源);⚠️ 8 参 `VerifyRunService` 构造器无生产调用,是「熔断/轮次不生效」隐患兼容口。

---

## 6. workspace — 工作区(支撑域)

**职责**:以 git worktree 为隔离单元管理需求工作区的生命周期与释放不变量(脏态拒绝释放)。端口租约作为全局基础设施资源显式排除在聚合外,跨聚合只持 `requirementId` 引用。

### 核心构件(`domain/workspace/`)

| 构件 | 关键点 |
|---|---|
| `RequirementWorkspace` | 聚合根,id=`WorkspaceId`。构造不变量:`branch` 必须等于 `req/<requirementId>`(否则抛),repoUrl/mirrorPath/worktreePath 非空、ttlHours>0。`branchFor` 是分支命名**唯一事实源**。 |
| `WorkspaceId` | VO,`'W'+需求号后缀`(R2607040001→W2607040001),与需求一一对应。 |
| `WorkspaceStatus` | `PROVISIONING/READY/IN_USE/RELEASED`。DIRTY **不是**状态。 |
| `DirtyReport` | VO,"某一时刻事实"。`isDirty = 未提交文件非空 ‖ unpushedCommits>0`。 |
| `WorkspaceRetentionPolicy` | 域策略:`REVIEW/VERIFYING/SUSPENDED` 需求的工作区不参与 TTL 清理。 |

### 状态机

`PROVISIONING → READY ⇄ IN_USE → RELEASED`(终态),方法全在聚合内含守卫 `assertTransition`:`markReady()`(仅 PROVISIONING∨IN_USE)、`markInUse()`(仅 READY)、`markReleased()`(非 RELEASED 皆可)、`isReusable()`(≠RELEASED,幂等 provision 判据)、`isExpired(now)`(严格晚于 `lastActiveAt+ttlHours`)。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 释放前脏态检查(dirty×!force→拒绝) | `RequirementWorkspace.assertReleasable` | ✅ domain,`ReleaseCoordinator` 首行调用 |
| 分支命名空间 `req/<id>` 唯一事实源 | `RequirementWorkspace.branchFor` + 构造校验 | ✅ domain,与 delivery 白名单同源 |
| TTL 保留策略 | `WorkspaceRetentionPolicy` | ✅ domain |
| 端口分配竞态(INSERT OR IGNORE 冲突重试) | `SqliteWorkspaceRepo.allocate` | ✅ infra |
| 禁 `git clone --mirror` 红线 | `GitWorktreeProvisioner`(`clone --bare`+显式 refspec+`assertNoMirrorSemantics` 双断言) | ✅ infra |
| 供给幂等(已有未释放即复用) | `WorkspaceAppService.provisionFor`(靠聚合 `isReusable()`) | ✅ app |

### 应用编排

- `WorkspaceAppService`(`@Transactional`):`provisionFor` — 校验 → provision → 建聚合 `markReady`+save → 分配端口 → `requirement.attachWorkspace` 回写。
- `ReleaseCoordinator`(`@Transactional`):`release` — `assertReleasable` → `provisioner.release` → `portLeaseStore.releaseAll` → `markReleased` → save。M1 恒 `removeBranch=false`(保留 req/* 分支)。
- `WorkspaceCleanupService`:**定时 cron `0 40 4 * * *`**。候选 → 保留策略过滤 → 脏态检测 → clean 才释放;dirty 跳过 + 通知属主(旁路降级);顺带 `purgeWebhookDedup`(30 天,**跨域清 delivery 的 `processed_webhook`**)。
- `WorkspaceDiskMonitor`:**定时 cron `0 17 * * * *`**,剩余空间低于阈值打 warn。

### 端口与适配

`WorkspaceRepository`(写侧 upsert)→ `SqliteWorkspaceRepo`;`PortLeaseStore`(接口放 **app**)→ **同一 `SqliteWorkspaceRepo` bean 双接口实现**(`port_lease` 表);`WorkspaceProvisioner`(adapter)→ `GitWorktreeProvisioner`(bare mirror + per-req worktree,per-slug ReentrantLock 互斥,凭证打码)。

### 死代码 / 异味

⚠️ `ReleaseCoordinator.release` 的 `DirtyReport` 由调用方传入,`release` 无自检(cleanup 路径正确,直接调 release 需自律);⚠️ `countUnpushedCommits` 以 mirror 默认分支为基准而非 upstream,跨基分支可能误报。

---

## 7. delivery — 交付(支撑域)

**职责**:把需求工作区分支推到 GitLab 并创建草稿 MR,落 MR 引用与审计事件;同时作为 GitLab webhook 的入站编排(MR 合并→markDelivered、pipeline 失败/评论→fix 建议、issue 打标→建需求)。凭证解析链与 push 白名单是其安全核心。

### 核心构件

| 构件 | 关键点 |
|---|---|
| `DeliveryPolicy`(`domain/delivery/`) | 纯域规则(无状态 `final class`):push ref 白名单、commit trailer 组装、MR 强制草稿。 |
| `MergeRequestRef`(`domain/delivery/`) | VO:`mrIid/url/draft/pipelineStatus`(GitLab MR 最小投影)。 |
| `ScmCredential`(`adapter/delivery/`) | 凭证模型(内存态),`toString` 打码,禁落库禁日志。 |
| `ScmWebhookEvent`(`adapter/delivery/`) | **sealed interface**,5 record:`PipelineFailed/MrNoteAdded/MrMerged/IssueLabeled/Unsupported`。 |

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| push ref 仅 `req/*` 白名单(段不以`-`开头、禁 `..`) | `DeliveryPolicy.assertPushRefAllowed` | ✅ domain,与 infra 禁 --mirror 双保险 |
| commit trailer(必带 Session,默认账号加 Operated-By) | `DeliveryPolicy.buildCommitTrailers` | ✅ domain(默认账号无操作人即抛) |
| MR 强制 Draft 前缀 | `DeliveryPolicy.draftTitle` | ✅ domain |
| **凭证链:个人→系统默认→拒绝** | `DeliveryAppService.resolveCredential`(`findPersonal().or(::findDefaultAccount).orElseThrow`) | ✅ **顺序规则明确放 app**,端口只取单源 |
| 密钥触达(解密/env)收 infra | `GitLabCredentialStore`(env `AGENT_GITLAB_DEFAULT_TOKEN` 优先→app_setting 密文,解密失败降级) | ✅ infra |
| **webhook 幂等(UUID 去重)** | `ScmWebhookAppService.handle` + `SqliteDeliveryStore.tryMarkProcessed`(INSERT OR IGNORE) | ✅ app+infra |
| **webhook fail-closed secret 校验** | `ScmWebhookController.receive`(secret 未配置→503,常量时间 `MessageDigest.isEqual`,鉴权后恒 2xx) | ✅ interfaces |
| 单事件失败只记日志(防 5xx 重试风暴) | `ScmWebhookAppService.handle` | ✅ app |

### 应用编排

- `DeliveryAppService`(手工 `new DeliveryPolicy`):`deliverDraft` — 加载需求+工作区 → `resolveCredential` → `assertPushRefAllowed` → `buildCommitTrailers` → `pushBranch` → `createDraftMr` → `mergeRequestStore.upsert` → `requirement.recordMrDrafted` → update。**同步**,被 `RequirementRunController.deliverDraft` 调用。
- `ScmWebhookAppService`:`handle` — UUID 幂等 → `parseWebhook` 防腐 → `dispatch`。`onMrMerged`→`markDelivered`(`system:webhook`);`onPipelineFailed`/`onMrNoteAdded`→`recordFixSuggestion`(**只建议不自动触发**);`onIssueLabeled`→`IntakeOwnerPolicy` 解析 owner→`createWithRef`(issue 幂等复用 `requirement_intake_dedup`)。

### 端口与适配

`ScmGateway`(adapter)→ `GitLabScmGateway`(push 走 git CLI + `GIT_ASKPASS` env,凭证不进 URL 不落盘;显式 refspec;MR 走 REST v4 `PRIVATE-TOKEN`;webhook 委托 `GitLabWebhookParser`);`ScmCredentialStore` → `GitLabCredentialStore`;`MergeRequestStore`+`WebhookDedupStore`(接口放 app)+`RequirementIdempotencyStore` → **单 bean `SqliteDeliveryStore` 三接口**。

### 死代码 / 异味

🔴 `ScmGateway.fetchMergeRequest` 无生产调用方——纯 dead code;⚠️ `GitLabScmGateway` 自建 `GitAskpassScript` 与 git 域的 `@Component GitAskpassScript` 是两份独立实例(脚本双写);⚠️ `DeliveryPolicy` 手工 `new` 而非注入,与其它域策略走 `DomainServiceConfig` 不一致。

---

## 8. git — 用户 Git 配置(支撑域)

**职责**:持有单用户的 git 提交身份与 push 凭证引用,在 agent CLI 子进程 spawn 前把身份/凭证以 env 形式即时注入(不改磁盘 git config、可随时回落机器默认)。凭证密码 AES-256-GCM 静态加密,明文绝不入库/回显/日志。

### 核心构件

| 构件 | 关键点 |
|---|---|
| `UserGitConfig`(`domain/git/`) | 聚合根,身份=`userId`。**不持明文密码**,只持 `credentialPasswordCipher`(opaque 密文)。`updateCredential` 不变量:username 与 cipher 均非空、明文绝不进聚合。 |
| `GitIdentity`(`domain/git/`) | VO,构造校验 name/email 非空 + 邮箱正则。映射 `GIT_AUTHOR_*/GIT_COMMITTER_*`。 |
| `GitConfigPolicy`(`domain/git/`) | 域服务,`isSystemContext(userId)`:userId 空白=无上下文(不可改、不注入)。 |
| `GitEnvSpec`(`app/git/`) | app VO:identityEnv + 解密后明文凭证(**仅即时写子进程 env**),`EMPTY` 单例。 |

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 无登录上下文拒写/不注入 | `GitConfigPolicy.isSystemContext`(app 消费于 save/resolve) | ✅ domain 收口 |
| 明文密码绝不入聚合 | `UserGitConfig.updateCredential`(只收 cipher) | ✅ domain |
| **凭证 AES-256-GCM 静态加密** | `GitCredentialCipher.encrypt`(密钥仅 env `GIT_CRED_ENC_KEY` 无默认回退,密文 `v1:base64(iv‖ct‖tag)`,每条随机 12B IV) | ✅ infra |
| cipher 缺失降级为禁用(仅身份生效) | `GitCredentialCipher.isEnabled` + 前置判(设凭证但未启用→409) | ✅ infra |
| 解密失败不中断身份注入 | `GitEnvResolver.tryDecrypt` / `GitProcessEnvCustomizer`(只告警不带密文) | ✅ app/infra |

### 应用编排 / 协作

`GitConfigAppService`:`getForCurrentUser`(系统上下文→只读空视图)/`save`(系统用户拒写→409,身份校验→加密落库)。`GitEnvResolver.resolve(userId)` 产 `GitEnvSpec`。**被 delivery 复用**(`GitLabCredentialStore.findPersonal` 读同一 `UserGitConfigRepository`+`GitCredentialCipher`,凭证链第一环);**被 agentrun/cli 消费**(`GitProcessEnvCustomizer.apply` 在 `AgentCliGateway` 两处 spawn 点调用);依赖 `CurrentUserProvider`。

### 端口与适配 / 异味

`UserGitConfigRepository` → `SqliteUserGitConfigRepo`(`user_git_config`,INSERT OR REPLACE);`GitCredentialCipher`/`GitAskpassScript`/`GitProcessEnvCustomizer` 均 `@Component`。⚠️ `deleteByUserId`/`clearCredential` 实现存在但无生产入口(回落机器默认未接线);⚠️ app 层直接 import infra `GitCredentialCipher`(被「密钥来源封装」注释合理化,但非端口抽象)。

---

## 9. knowledge — 知识收件箱(支撑域)

**职责**:关单收割 + 人工审批收件箱——需求交付后把标题/描述/计划收割成「知识候选」,人工审批是入库唯一出口,批准后经 issue-log 通道落盘到目标仓 `docs/issue-log/`。

### 聚合根 KnowledgeSuggestion(`domain/knowledge/KnowledgeSuggestion.java`)

- **标识**:`id` = `"KS-"+UUID`,`final`,requireText 非空。字段:`status`(SuggestionStatus)、草稿字段(title/triggerSignals/phenomenon/rootCause/solution/notes)、审批态(reviewedBy/reviewedAt/rejectReason)、落盘回执(issueId/issuePath)。
- **不变量**(behavior methods,persistence-ignorant):`approve`/`reject`/`reviseDraft` 均 `assertPending`(仅 PENDING);`reject` 理由必填;`recordArchived` 仅 APPROVED;`create` 恒 PENDING。
- **KnowledgeScope**:`REQUIREMENT/REPO/ORG`,M4 只走 `REPO`(harvest 硬编码)。

### 状态机 SuggestionStatus

```
PENDING --approve--> APPROVED --recordArchived--> (issueId/issuePath 回填)
PENDING --reject---> REJECTED (rejectReason 留存)
```
APPROVED/REJECTED 终态;非 PENDING 调 approve/reject/revise 抛 `IllegalStateException`。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 候选恒 PENDING 入箱 | `KnowledgeSuggestion.create` | ✅ domain |
| 审批/拒绝/编辑仅 PENDING + 拒绝理由必填 | `KnowledgeSuggestion.assertPending` | ✅ domain |
| recordArchived 仅 APPROVED | `KnowledgeSuggestion` | ✅ domain |
| 收割幂等(每需求≤1) | `KnowledgeHarvestService` + `existsForRequirement` | ✅ app→domain port |
| 收割旁路降级(吞异常) | `KnowledgeHarvestService` | ✅ app |
| 落盘前触发词必填 | `IssueLogDraft.requireArchivable()`(issuelog) | ✅ domain |
| 落盘失败不持久化审批 | `KnowledgeInboxAppService`(approve→save→recordArchived→update 顺序) | ⚠️ **非事务**:save 成功后 update 失败会盘上有文件而 DB 仍 PENDING |

### 应用编排

- `KnowledgeHarvestService.harvestOnDelivered`:需求 DELIVERED 触发(`RequirementAppService.markDelivered` → `harvestKnowledgeQuietly` 单一收敛点)→ 幂等闸 → 加载 `Requirement` → `create` 候选(rootCause=计划正文截断)→ save。
- `KnowledgeInboxAppService.approve`:加载 → `requiredWorkspace` 校验 → `toDraft` → `draft.requireArchivable()` → `suggestion.approve` → `issueLogRepository.save(worktree, draft)` 写 `docs/issue-log` → `recordArchived` → update。**不自动 git commit(随 MR 走)**。`reject`/`revise` 同类。

### 端口与适配

`KnowledgeSuggestionRepository`(写)+ `KnowledgeInboxQueryService`(读)→ **单 bean `SqliteKnowledgeSuggestionRepo`**(`knowledge_suggestion` 表)。Controller `KnowledgeInboxController`。**是 issuelog 写入的唯一活跃驱动**。

---

## 10. suggestion — 用户建议(支撑域)

**职责**:用户对系统提交的建议/反馈工单 + 管理员处理。**独立于会话评分 `domain.chat.Feedback`**(后者答「AI 分析对不对」,本域答「用户对系统的建议 + admin 如何处理」)。

### 聚合根 UserSuggestion(`domain/suggestion/UserSuggestion.java`)

`final` 不可变,更新走 copy-on-write。`id`=UUID;`content` 非空,`status/createdAt/updatedAt` 非空,字段 `trimToNull`;`create` 恒 `PENDING`;title 限长 80/内容截 40。`updateByAdmin`:返回新实例;填了 reply 且当前 PENDING 且未显式指定状态 → 自动转 `REPLIED`;reply 变更时刷新 `repliedAt`。

### 状态机 UserSuggestionStatus

`PENDING / PROCESSING / REPLIED / CLOSED`,带 label + `parse`。**无强约束转移矩阵**——admin 可任意置态,仅 PENDING+reply 的隐式 →REPLIED 规则。

### 核心规则归属 / 编排 / 适配

规则(content 必填、create 恒 PENDING、reply→隐式 REPLIED、repliedAt 刷新)✅ 全在 domain;归属过滤/分页 clamp/status 解析在 app(`UserSuggestionService`)。`UserSuggestionService`:`submit`(取 `CurrentUserProvider` 身份 → save)、`listMine`/`getMine`(归属校验)/`listForAdmin`(status+keyword 分页)/`updateByAdmin`(copy → save)。仅依赖 `auth.CurrentUserProvider`,无其它域耦合。`UserSuggestionRepository` → `SqliteUserSuggestionRepo`。Controllers `UserSuggestionController`(用户)+ `UserSuggestionAdminController`(admin)。

---

## 11. issuelog — 问题沉淀(支撑域)

**职责**:把结构化经验条目落盘工作目录 `docs/issue-log/issue/I-xxx-*.md`,供后续 agent grep 召回。**诊断线摘除后,现由 knowledge inbox approve 单一驱动**(`issueLogRepository.save` 全仓仅 `KnowledgeInboxAppService.approve` 一处调用)。

### 核心构件

| 类型 | 角色 | 不变量 |
|---|---|---|
| `IssueLogEntry` | 聚合根(已落盘条目) | `id` 匹配 `^I-\d+$`;draft/filePath/createdAt 非空 |
| `IssueLogDraft` | VO(编辑期载体) | title 必填;categories/services 至少 1 非空 token;`requireArchivable()` 落盘前触发词必填;含 8参/7参兼容构造 |
| `IndexMetadata` | VO(INDEX 已有类型/服务清单) | 首次出现顺序去重、不可变 |

### 文件仓储 + 锁

`FileSystemIssueLogRepository.save`:**锁内**(`WorkingDirIssueLogLockRegistry` 按 workingDir key 发 `ReentrantLock`,解决同目录并发 save 抢同一 id 撞号)`ensureInitialized → nextId 重算 → toSlug → writeIssueFile → appendIndexRow`。`nextId` 全量扫 INDEX.md 取最大序号 +1,`formatId` 为 `I-%03d`。锁仅进程内有效,多实例需升级文件锁。

### 死代码 / 异味

- **`DraftFromTask` / `IssueLogBackfillScheduler` / `IssueLogDedupMatcher` / `IssueLogRefinery` / `IssueLogMerger` 已删除**(诊断精炼链)。仅残留:`IssueLogProperties.Backfill` 死配置类(`agent.issue-log.backfill.*` 无消费者)、若干 javadoc 提及。
- 保留组件均健康:id 不变量在聚合、七/八字段不变量在 VO、并发分配在 infra 锁内。

---

## 12. auth — 鉴权(支撑域)

**职责**:用本地登录会话识别「当前请求是谁」,据此驱动用户数据隔离与登录跳转;管理员走独立口令鉴权。

### 核心模型

- VO `LoginUser`:userId/userName/userEmail。聚合根 `ManualSession`:负责工号、用户名、TTL 不变量和安全随机令牌生成。
- 端口 `ManualSessionRepository` / `UserContext`:分别负责会话生命周期和当前请求用户读取。
- 领域服务 `ManualSessionAuthenticator`(查会话、判过期、清失效行)、`ManualSessionFactory`(统一 TTL + 时钟)、`CurrentUserProvider`(基于 `UserContext` 给隔离决策;`userId=null` 的后台线程/系统任务不做用户过滤)。

### 关键规则 / 编排

主路径会话隔离 → `CurrentUserProvider.shouldFilter()`(domain);会话认证 → `ManualSessionAuthenticator.authenticate()`(过期判断与清理集中在 domain 服务);所有非公开入口统一要求登录。`AuthAppService` 仅编排会话创建/认证/删除;`AuthController` 管 Cookie 读写与状态 DTO;`SessionAuthFilter` 管公开路径判断、用户上下文绑定与「页面 302 / API 401+loginUrl」分流(经 `AuthSecurityConfig` 显式注册,不污染 Controller 切片测试)。`PublicPaths` 放行 `/api/requirements/external`(走自己的 `ApiKeyAuthFilter`);Filter 把 userId 写 MDC。

### 分层健康度

✅ 端口/适配最干净:`CurrentUserProvider` 由 `DomainServiceConfig` 装配(Domain 不依赖 Spring);`AuthAppService` 只依赖 Domain 类型;`status` 与 Filter 复用同一认证应用服务,规则不分叉。

---

## 13. schedule — 定时任务(支撑域)

**职责**:按 cron 周期触发 prompt,落库管理任务 CRUD,运行期动态注册/取消 cron 触发器,每次触发复用 chat 链路产出一条独立会话。

### 核心模型 / 关键规则

- 实体 `ScheduledTask`:id/name/cronExpr/prompt/workingDir/enabled + 审计时间戳 + lastRunAt/lastSessionId,两个构造器,Lombok getter/setter,**无任何不变量校验**。端口 `ScheduledTaskRepository`:写侧 lifecycle,签名全 domain 类型。
- cron 合法性(构造期不变量)→ 🔴 **在 app 层校验,且散落三处**:`ScheduledTaskServiceImpl.create`/`update` 各 `new CronTrigger`,`DynamicTaskScheduler.scheduleTask` 第三处。启用/禁用迁移 → app `toggleEnabled` 翻转。

### 编排 / 健康度

`ScheduledTaskServiceImpl`:CRUD + 落库后调 `DynamicTaskScheduler` 注册/取消;`doExecute` 是 cron 与手动触发共用核心(`ChatSession.forTask` 建会话、存消息、`AgentGateway.runStream` 跑 CLI、`updateLastRun`)。`DynamicTaskScheduler`:`@EventListener(ContextRefreshedEvent)` init 回注 self 破循环依赖 + 加载 `findAllEnabled`。
🔴 cron 构造期不变量泄漏 app 且三处复制(应收口 `ScheduledTask.create`);⚠️ 贫血实体 + app 代劳 toggle;⚠️ `doExecute` 硬编码 `AgentType.CLAUDE`(定时任务永远走 Claude,模型没存 agentType);⚠️ `DynamicTaskScheduler` 放 app 但本质 infra,与 Service 循环依赖靠 setter 破环。

---

## 14. slashcommand — 斜杠命令(支撑域)

**职责**:扫描 `.claude/commands`、`.claude/skills`、`.codex/skills` 等目录解析带 YAML frontmatter 的命令定义,发送时把 `/cmd args` 展开为模板体并替换 `$ARGUMENTS`。**支撑域里最干净的。**

### 核心模型 / 规则

- VO `SlashCommand`:name/description/argumentHint/body/skill,全 final 不可变。领域服务 `SlashCommandExpander` 承载识别 + 展开规则。端口 `SlashCommandScanner`:`scan(workingDir) → List`,项目级优先主目录级。
- 展开规则 → `SlashCommandExpander.expandIfCommand`:非 `/` 开头原样返回;切命令名与参数;扫描匹配同名,未命中原样返回;命中则 `body.replace("$ARGUMENTS", arguments)`。✅ domain。
- 扫描/解析 → infra `FileSlashCommandScanner`:项目级先扫、主目录级去重 fallback;commands 递归子目录用 `:` 拼前缀;skills 每子目录 `SKILL.md` 解析为单命令。✅ IO 适配。

### 健康度

✅ 整体最干净:VO 不可变、规则在 domain 服务、IO 在 infra、端口隔离到位。无独立 app 服务;`SlashCommandExpander` 由 `SlashCommandBeanRegistrar` `@Bean` 组装。⚠️ `expandIfCommand` 每次落盘 `scanner.scan` 无缓存(当前规模无碍)。

---

## 15. worktree — Git 工作树(支撑域)

**职责**:在含多个独立 git 仓库的工作区里按分支批量建/更/删 git worktree,实现分支级隔离;无目标分支的仓库 fallback 到默认分支并以链接复用。

### 核心模型 / 规则

- VO/领域服务 `BranchNameValidator`:**本域唯一 domain 类**。`ALLOWED_KEYWORDS`=release/hotfix/feature/cr/bugfix;`validateAndNormalize`:trim 后必须以允许关键字开头**且长度 > 关键字本身**,否则抛异常。✅ domain。
- worktree 创建策略(本地/远程分支存在性、fallback、复用已检出路径)、链接 vs 真实 worktree 区分(NTFS junction 检测 `isDirectoryLink`)→ 全在 `WorktreeService`。

### 健康度

- 🔴 **App 层承载大量 infra 职责**:`WorktreeService` 把 git 子进程执行、文件树遍历、NTFS junction 检测全塞进 application 层(超类红线),应下沉 infra `GitWorktreeGateway`,app 只编排。
- 🔴 **分支名规则双轨且不一致**:domain `BranchNameValidator`(关键字白名单)与 service 私有 `safeBranchName`(仅替非法字符)是两套规则。
- 🔴 **校验入口不对称**:`/switch` 校验分支名,但 `/update`、`/remove` 不校验。
- ⚠️ 返回裸 `Map<String,Object>` 而非 DTO。

---

## 16. metrics — 管理台读侧(读模型,纯 CQRS)

**职责**:纯 SELECT 投影读模型,不经聚合根、不含业务判断。两大子块:**运营总览**(`MetricsQueryService`)与 **召回可观测性**(`RecallMetricsQueryService`)。

### 核心(无聚合根 — 全部只读 DTO)

- **`MetricsQueryService`**:`overview() → MetricsOverview`(**仅 chat 维度**:total、byAgentType、feedback 分布、accuracyRate);`trend(days)`(近 N 天每日 chat 数,UTC 补 0)。impl `SqliteMetricsQueryService` 对 `chat_session` GROUP BY。Controller `/api/metrics/overview|trend`。
- **`RecallMetricsQueryService`**:chat 召回可观测性——`summary(filter)`(attempt/executed/hit/noHit/error/skipped 计数 + 派生率 + 分桶 byStatus/byEmbeddingModel/byEnv/bySourceType/byTier + 散点),`listAttempts`/`detail(id)`/`detailByMessageId`/`topChunks`。读 `chat_recall_attempt` JOIN `chat_recall_hit`,写侧由 refinery `RecallObservationRecorder` 产出。
- **`ConversationQueryService`**:会话明细/分页,供 `AdminConversationController`(`/api/metrics/conversations`)。

### 健康度

✅ 读写分治,读端口置 app、impl 在 infra 直连 `JdbcTemplate`、分母 0→null 比率。
⚠️ **删除遗留**:`MetricsQueryService` javadoc 仍写「会话/诊断/飞书三块」,但 `MetricsOverview` 已只剩 chat。
🔴 **孤儿 DTO**:`FunnelStep` / `StageLatency` / `TopError`(`app/metrics/`)**无任何生产者或引用**——工单漏斗被移除后残留的死代码。

---

## 附录 A — 横切问题(跨域共性异味)

| 问题 | 命中的域 | 说明 |
|---|---|---|
| **构造期不变量逃逸到 app** | refinery(`expires_at`)/ schedule(cron)/ chat(workingDir) | 应收进聚合工厂 `Aggregate.create(...)`。 |
| **贫血聚合 + app 代劳状态迁移** | chat(消息/反馈)/ schedule(toggle)/ workflow(禁用不可 run) | 状态迁移被 app 用 getter/setter 重组,或规则在 app service。 |
| **领域规则散在 app 层** | refinery(重排算法)/ worktree(git 操作) | 核心判定/技术逻辑该下沉 domain policy / infra gateway。 |
| **聚合不完整(状态在边界外被改)** | refinery(tier 经 SQL 绕过方向校验) | 裸 SQL 直改聚合不拥有/不经方法的状态。 |
| **编排无事务边界** | chat / requirement / workflow / knowledge | lifecycle 的 load→mutate→save 无 `@Transactional`,多次独立 JDBC 写无原子性(knowledge approve 尤其:落盘成功但 update 失败会态不一致)。 |
| **单 bean 多接口** | workspace(×2)/ delivery(×3)/ knowledge(×2) | Sqlite 类同时实现写仓储 + 读投影/资源存储,务实但接口偏胖。 |
| **旁路降级(健康)** | verification / workspace / delivery / git / knowledge | try-catch 降级不阻断主流程,一致性良好 ✅。 |

## 附录 B — 死代码 / 休眠组件清单

| 组件 | 位置 | 现状 |
|---|---|---|
| `JsonFileSessionRepo` | infra | 不实现接口,生产无人注入;文档宣称的「JSON 备份层」未接线 |
| `ChatSession.addMessage` | domain/chat | 死代码,消息走 repo 直插 |
| `RagChunk.upgradeTier` | domain/refinery | 仅单测调用,生产走 SQL 绕过方向校验 |
| `RequirementAccessPolicy.canOperate` / `Requirement.addParticipant` | domain/requirement | 无调用方 |
| `VerificationRound.deployRef` / L1 `failedCount` | domain/verification | 列存在无写入源(恒 null / 0) |
| `VerifyRunService` 8 参构造器 | app/requirement | 无生产调用,熔断/轮次静默失效隐患口 |
| `ScmGateway.fetchMergeRequest` | adapter/infra delivery | 无生产调用方,纯 dead code |
| `IssueLogProperties.Backfill` / 诊断精炼链 javadoc | infra/issuelog | 死配置 + 残留注释(诊断线摘除) |
| `FunnelStep` / `StageLatency` / `TopError` | app/metrics | 工单漏斗移除后的孤儿 DTO,无生产者 |
| `UserGitConfigRepository.deleteByUserId` / `clearCredential` | domain/git | 实现存在但无 app 入口(回落机器默认未接线) |
| `RunForm.DIAGNOSE` / `SourceType.DIAGNOSE` / `RequirementSource.TICKET_DERIVED` | 多处 | 存量兼容枚举,无活跃生产者 |

## 附录 C — 重构优先级建议

**P0(bug / 数据保真)**:
1. knowledge approve 非事务:落盘成功 + DB update 失败 → 盘上有文件而候选仍 PENDING,应包 `@Transactional` 或补偿。
2. refinery `RagChunk.upgradeTier` 方向校验被生产 SQL 绕过(`updateTier` 不经聚合)。

**P1(领域算法/规则下沉,收复 domain)**:
3. refinery 重排算法从 `RefineryRecaller` 下沉 `RecallRanker` 领域服务。
4. schedule cron 不变量收口 `ScheduledTask.create(...)`,消除三处复制。
5. chat rewind/resume/反馈收成 `ChatSession` 业务方法 + `@Transactional`。

**P2(结构整理)**:
6. worktree git 操作下沉 infra `GitWorktreeGateway`,统一分支校验入口。
7. requirement / workflow 补事务边界。
8. 清理死代码(附录 B),同步 `MetricsQueryService` javadoc,收敛 `req/<id>` 分支前缀事实源(webhook 复用 `branchFor`)。

---

*本文档基于源码实读生成,`文件名:行号` 可能随后续提交漂移,定位时以符号名为准。*
