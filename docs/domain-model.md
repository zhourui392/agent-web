# Agent Web 领域模型

> 基于实读 `domain/` `app/` `infra/` 源码的领域建模手册,不是从目录结构反推的清单。
> 架构:DDD + 六边形,四层(interfaces / app / domain / infra)+ adapter 端口层。
> 视角:每个限界上下文按「职责 → 聚合根与不变量 → 状态机 → 核心规则归属 → 跨域协作 → 分层健康度与重构建议」展开,`文件名:行号` 为定位锚点。
> 健康度图例:✅ 收口正确　⚠️ 轻度异味(可容忍/务实折中)　🔴 重度异味(建议重构)。

---

## 0. 阅读地图

### 9 个限界上下文

| 域 | 类型 | 一句话职责 | 聚合根 / 核心 | 建模健康度 |
|---|---|---|---|---|
| **chat** | 核心 | 驱动「用户↔本地 CLI Agent」会话生命周期 | `ChatSession` | ⚠️ 贫血且越界,一致性边界在 DB |
| **diagnose** | 核心 | 异步远程诊断,one-shot 状态机任务 | `DiagnoseTask` | ✅ 状态机最健康的域 |
| **refinery** | 核心 | 自建轻量 RAG:评分→向量→召回注入 | `RagChunk` | ⚠️ 重排算法整体泄漏在 app |
| **issuelog** | 支撑 | 诊断结论→七字段→查重→审核→落盘 | `IssueLogEntry` | ⚠️ 六分类泄漏 app,有 bug 级保真缺口 |
| **ticket** | 支撑 | IM 消息建单、诊断、复核、反馈与关闭 | `Ticket` | ✅ 状态机与通知编排分离 |
| **auth** | 支撑 | 可插拔身份校验 + 数据隔离决策 | `CurrentUserProvider` | ✅ 端口/适配最干净 |
| **schedule** | 支撑 | cron 周期触发 prompt,复用 chat 链路 | `ScheduledTask` | 🔴 cron 不变量散落三处 |
| **slashcommand** | 支撑 | 扫描并展开 `/cmd args` 为模板体 | `SlashCommandExpander` | ✅ 支撑域里最干净 |
| **worktree** | 支撑 | 按分支批量建/更/删 git worktree | `BranchNameValidator` | 🔴 整域逻辑堆 app(641 行) |

### 跨域协作骨架

```
                       ┌─────────────────────────────┐
   AgentType(shared)   │   AgentGateway(adapter 端口)  │  ← chat / diagnose / schedule 共用驱动 CLI
                       └──────────────┬──────────────┘
                                      │
  飞书 IM ──ticket────────► diagnose ──┼──► chat(continue-as-chat 追问)
       ▲             │       │        │
       │ 卡片通知+反馈 │       │ 终态    │
       └─────────────┘       ▼        ▼
                          issuelog   refinery ◄── 经 ConversationView 防腐层消费
                        (六分类沉淀)  (chat会话+diagnose任务两上游)
                                          │
                                          └──► 召回反向注入回 chat 消息

  auth.CurrentUserProvider ── 是 chat/会话查询的数据隔离闸
```

- **`AgentGateway`** 端口被 chat / diagnose / schedule 三域共用,屏蔽 Claude / Codex CLI 差异。`AgentType` 放 `domain/shared` 跨域共享。
- **refinery 经 `ConversationView` 防腐层**吃 chat 会话 + diagnose 任务两个上游,domain 零 import 上游聚合——全仓边界守得最好的地方。
- **ticket 串起 IM 工单闭环**:消息建单 → 投喂 diagnose → 终态卡片通知 → 用户/管理台反馈 → issuelog 与 refinery 消费平台无关反馈。

---

## 1. chat — 会话对话(核心域)

**职责**:管理「用户 ↔ 本地 CLI Agent(Claude/Codex)」的会话生命周期——建会话、追加消息、流式驱动 CLI 并落库回应、支持回退重开 / resume 续接 / 会话级反馈,绑定到一个工作目录 + 一种 agent 类型。

### 聚合根 ChatSession(`domain/chat/ChatSession.java`)

- **标识**:`id` UUID(构造时 `UUID.randomUUID()`)。
- **内部集合**:`List<ChatMessage> messages`,对外只读 `getMessages()` 返回 `unmodifiableList`(集合封装是这里唯一像样的不变量保护)。
- **不变量**:**几乎没有**。两个构造器只做字段赋值,唯一防御是 messages null → 空 list。workingDir 存在性、agentType 非空都未在聚合校验。
- **核心业务方法**:`addMessage(role, content)` 是**死代码**——应用层全程不调它,消息一律走 `sessionRepository.addMessage(sessionId, msg)` 直插库。`forTask(...)` 静态工厂(给定时任务建会话,打 `Task-` 前缀标题)。rewind 回退 / resume 恢复 / 反馈挂载 / 历史前缀注入——**聚合内一个都没有**,全部散落 app 层。

### 值对象 / 枚举

- **ChatMessage**:`id/role/content/timestamp` 四字段 VO。`role` 是裸 `String`("user"/"assistant"),无枚举约束——这是 app 层满屏 `"user".equals(role)` 的根因。
- **Feedback**:`final` class,`rating + comment + updatedAt`,带值语义 `equals/hashCode`。注释明确允许 rating/comment 均空,所以无真正不变量。
- **FeedbackRating**:`CORRECT / PARTIALLY_CORRECT / INCORRECT` 三档枚举。
- **AgentType**(`domain/shared`):`CODEX / CLAUDE`,跨上下文共用;`NATIVE` 仅供可选的进程内诊断引擎使用。

### 仓储与三级存储

- **写侧 `SessionRepository`**(14 个方法):签名纯 domain 类型,无 ORM 泄漏。但**职责超出 lifecycle**——混入大量读模型投影(`findAllSummary`/`findSummaryPaged` 返回 `List<Map>`、`findIdsWithLastMessageBefore` 供 refinery 调度、`findRecallPayloads` 返回 `Map`)。
- **L1 `InMemorySessionRepo`**:实现 `SessionCache` 端口(非 SessionRepository),`ConcurrentHashMap` 裸缓存活跃会话。
- **L2 `SqliteSessionRepo`**:**唯一**的 `SessionRepository` 实现,承载全部持久化 + 消息表 + 召回明细 + 反馈 + 用户隔离。
- **`JsonFileSessionRepo`**:**孤儿 bean**——不实现任何接口,生产 main 代码无人注入。CLAUDE.md / README 宣称的「JSON 备份层」在生产链路**未接线**,是 dormant 组件。
- **协作真相**:L1/L2 不是经典 read-through/write-through,而是应用层手工编排两个独立端口——`getSession` 先查 cache、miss 再查 DB 回填;写入时 cache 与 DB 各写各的,cache 里的聚合**会与 DB 失配**(消息只插 DB 不更新内存对象),truncate 后靠 `sessionCache.remove` 强制失效。

### 端口 AgentGateway(`adapter/AgentGateway.java`)

对选定 `AgentType` 在 `workingDir` 执行 prompt。同步 `runOnce` + 流式 `runStream`(sessionId/resumeId/env/timeout/onChunk/onExit 回调)+ `stopStream`/`isRunning` + 两个方言适配方法 `extractResumeId`(从 stdout 抠 CLI 侧 session id)、`normalizeChunk`(一条 raw 事件展开 0..N 条统一前端事件)。实现是 infra `AgentCliGateway`(按 `CliDialect` 策略路由)。

### 应用编排 ChatAppServiceImpl.streamMessage

1. `getSession`(cache→DB),null 抛异常。
2. `effectiveEnv = s.getEnv()`——env 创建时一次性写定、之后不可变,URL 参数被忽略。
3. `resolveHistoryForPrefix`——从 DB 重新 findById,判定是否注入历史前缀。
4. **先持久化原文 user 消息**(走 `sessionRepository.addMessage` 直插,不经聚合)。
5. 建无超时 `SseEmitter(-1L)` + 15s keepalive ping + `StreamChunkHandler`。
6. 异步:RAG 召回(开关开且 `Optional<RefineryRecaller>` 在场)命中→推 `recall` SSE 帧;否则 `commandExpander.expandIfCommand` 展开斜杠命令 → 需要时拼历史前缀 → `gateway.runStream` 驱动 CLI。
7. `onChunk`:首帧抽 resumeId 落库一次 + `normalizeChunk` + SSE 推 chunk。`onExit`:累积响应存为 assistant 消息(有 recall 则连带 `saveRecall`)。

**事务边界**:**全程无 `@Transactional`**。startSession 的 cache + DB 写、流式的 user 消息 / resumeId / assistant 消息都是各自 autocommit 的独立写,无原子性保证——与 CLAUDE.md「app 做编排 + 事务」的承诺有落差。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 截断/回退:删 ≥fromId 消息并清 resume_id | `SqliteSessionRepo.truncateFrom` + `ChatAppServiceImpl.truncateFrom` | 🔴 规则在 app+infra,聚合无 rewind 方法 |
| 回退后取 prefill(fromId 指向 user 则回填原文) | `ChatAppServiceImpl:392-399` | 🔴 **app 遍历 `s.getMessages()` 找特定 id**,典型泄漏 |
| 截断后重开是否注入历史前缀 | `resolveHistoryForPrefix:264-281` | 🔴 app 用 getter(resumeId/messages 空判)重组规则 |
| user/assistant 角色分流 | 多处 `"user".equals(getRole())` | 🔴 VO 缺 `MessageRole` 枚举 |
| 工作目录必须存在 | `startSession:111-114` | ⚠️ 构造期不变量在 app(FS 存在性偏 infra 关注,争议较小) |
| resumeId 抽取与落库(首帧一次) | `StreamChunkHandler:125-137` | ✅ 协议适配收在流处理器 |
| assistant 终局文本抽取 | `StreamOutputExtractor` | ✅ 纯协议解析,无业务判断 |
| 会话按 user_id 隔离 | 决策 `CurrentUserProvider` + 应用 `SqliteSessionRepo.filterUserId` | ✅ 决策在 domain/auth,infra 只拼 WHERE |

### 跨域协作

依赖 slashcommand(`SlashCommandExpander`)、refinery(`Optional<RefineryRecaller>` 软依赖)、auth(`CurrentUserProvider`)、CLI(`AgentGateway`)、issuelog/upload(`IssueLogWriter`/`UploadPicStore`/`UploadFileStore`)。反向:refinery 通过 `SessionRepository.findIdsWithLastMessageBefore` 拉静默会话。

### 分层健康度与重构建议

**实锤泄漏**:① app 遍历聚合集合找特定 id(`:392-399`);② 对 getter 做语义判断(`resolveHistoryForPrefix`、全仓 `"user".equals`);③ 替聚合做构造期校验(`startSession` 校验 workingDir);④ 聚合方法被架空——`addMessage` 死代码,消息/feedback/resumeId 全绕过聚合直插库,**真正的一致性边界是 DB 不是聚合根**。

**好信号**:聚合根没注入 Repository(持久化无知保住了);`getMessages` 返回不可变视图;用户隔离决策在 domain/auth。

**重构方向**:
- 把 rewind / resume / 反馈挂载收成 `ChatSession` 业务方法,消息追加经聚合,让聚合成为真正的一致性边界(配 `@Transactional`)。
- 加 `MessageRole` 枚举消除 `"user".equals` 散弹。
- 拆 `SessionQueryService` 承接读模型投影。
- 决断 `JsonFileSessionRepo`:接回链路或删除,消除文档与代码不符。

---

## 2. diagnose — 远程诊断(核心域)

**职责**:把「远程/异步代码诊断」建模为带状态机的 one-shot 任务,对外提供 API-Key 鉴权的提交入口,落库后异步驱动 CLI agent,过程经 SSE 实时扇出并支持断线续传,终态结果可转聊天追问、可沉淀为 issue-log。**全仓状态机建模最健康的域。**

### 聚合根 DiagnoseTask(`domain/diagnose/DiagnoseTask.java`)

- **标识**:`taskId`(UUID,构造期生成)。不可变身份字段全 `final`;可变执行态 `status/startedAt/finishedAt/resultExpiresAt/result/errorMessage/eventSeq/resumeId`。
- **不变量**:① 所有迁移前置 `requireStatus`(`:178`),非法迁移抛 `IllegalStateException` → `GlobalExceptionHandler` 翻 HTTP 409;② `eventSeq` 从 1 起单调递增(`nextEventSeq`);③ 终态统一走 `markFinished()` 盖章 `finishedAt` 并按 `retentionMinutes` 算 `resultExpiresAt`;④ `resumeId` 是唯一带 `@Setter` 的执行态字段(执行期旁路抽取、单独回写,刻意绕开状态机)。

### 状态机(`DiagnoseStatus.java`)

6 态 `PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT`,`isTerminal()` = 后四者。**合法迁移全部收敛在聚合方法内**:

| 迁移 | 方法 | 触发方 |
|---|---|---|
| PENDING→RUNNING | `start()` | `executeTask` worker |
| RUNNING→SUCCESS | `succeed(result)` | `finalizeTask` exit=0 |
| RUNNING→FAILED | `fail(msg)` | `finalizeTask` exit≠0 / `failTask` / 启动恢复 |
| 非终态→CANCELLED | `cancel()`(按 isTerminal 守卫) | `DiagnoseAppServiceImpl.cancel` |
| RUNNING→TIMEOUT | `timeout()` | **🔴 无生产调用方**(见健康度) |

### 事件模型 DiagnoseEvent + DiagnoseEventBus

- `DiagnoseEvent`:`final` VO,6 种类型。`isPersistent()` = 非 chunk/heartbeat——只有 status/progress/result/error 落库重放,CLI 原始 chunk 流不持久化、断线即丢。
- **三条路径**:① 关键事件 `publishPersistent`——先 `nextEventSeq` 取序号 → `repo.update` 回写 → `appendEvent` 落库 → `eventBus.publish` 扇出(先持久化、再广播);② chunk 流——seq 恒传 0、不落库,纯实时转发;③ 扇出——`DiagnoseEventBus` 维护 `ConcurrentMap<taskId, Set<SseEmitter>>`,单 emitter 发送失败自动摘除,不连累其他订阅者。
- **Last-Event-ID 续传**:`subscribe` 用 `from = lastEventId ?? 0` 调 `findEventsAfter`(SQL `event_seq > ?` 升序)回放历史关键事件,逐条 `.id(eventSeq)` 重发;任务已终态则回放完直接 complete。**chunk 因 seq=0 不落库,续传只补关键事件、不补中间 token 流**——有意取舍。
- **幂等两层**:任务级 `(apiKeyName, clientToken)`,`submit` 命中即原样返回(clientToken 来自 `Idempotency-Key` header);事件级 `appendEvent` 用 `INSERT OR IGNORE` 按 `(task_id, event_seq)` 去重。

### 并发与启动恢复

- **并发上限** `agent.diagnose.max-concurrent`(默认 4)。`submit` 用 `synchronized(submitLock)` 包住「计数 → 越限抛 `DiagnoseConcurrencyLimitException`(→ HTTP 429)→ 否则 save」,保证「计数→落库」原子。
- **启动恢复** `DiagnoseStartupRecovery`(`ApplicationRunner`):进程重启后残留的 PENDING/RUNNING 孤儿行已无执行体却仍被并发计数永久占额导致后续全 429。恢复时 `findAllActive` 捞出,PENDING 先 `start()` 满足前置、再统一 `fail(RECOVERY_REASON)` 收敛为 FAILED 释放额度。时机晚于建表、早于后台 worker 启动。

### 鉴权 ApiKeyAuthFilter

`@Order(0)` Servlet Filter,仅匹配 `/api/diagnose` 前缀。取 `X-API-Key` → 空 401 → `props.findByKey` 未命中 403 → 命中则把 `apiKeyName` 写入 request attribute 供审计 + 幂等命名空间。`/api/diagnose-history`（管理页）不走此 Filter，由管理口令独立鉴权。

### continue-as-chat(跨 chat 协作)

诊断是 one-shot 无多轮上下文,转聊天基于诊断 `agentType/workingDir` 新建 `ChatSession` → 若 `resumeId` 非空则续接 CLI 上下文(空则全新会话)→ 标题 `[诊断追问]` → 双写 cache + repo → 回填两条 UI 历史(user=原 query,assistant=`extractPlainText(result)` 剥离工具调用)。

### 核心规则归属

状态机迁移、`isTerminal`、`eventSeq` 自增、`resultExpiresAt` 计算 ✅ 全收在聚合/VO;并发原子校验 ✅ 编排级事务边界;事件级幂等 `INSERT OR IGNORE` ✅ infra 协议适配;孤儿收敛 ✅ 调聚合方法未自改状态。

### 分层健康度与重构建议

**整体健康**:状态机完全收敛聚合内,SqliteRepo 无业务判断,app 调聚合方法而非 getter 重组。三处标注:

1. 🔴 **TIMEOUT 态 + 超时清扫器是死代码/休眠态**:`DiagnoseTask.timeout()` 生产零调用(仅单测);`findRunningStartedBefore`、`findTerminalExpiredBefore` 无任何 `@Scheduled` 清扫器调用。实际超时由 `AgentGateway.runStream` 凭 `timeoutSeconds` 强杀进程兜底,落点是 FAILED。**TIMEOUT 永不产生,过期任务也不会物理清理**。属「接口齐全、调度未接线」,需确认是否遗漏定时装配。
2. ⚠️ `continueAsChat` 轻度 app 语义判断泄漏(`task.getResumeId() != null && !isEmpty()`),规模小可容忍,逻辑变长应下沉 `ChatSession.fromDiagnose(...)` 工厂。
3. ⚠️ `DiagnoseTaskRepository` 读写未拆分(lifecycle + 分页/计数/按状态查 id 混一接口),按规约应拆 `DiagnoseQueryService`,但无 ORM 泄漏,属务实折中。

---

## 3. refinery — 知识精炼 / 向量召回(核心域)

**职责**:把静默 chat 会话 / 终态 diagnose 任务用 LLM 压成「可复用经验结论」并向量化入库,新对话/诊断发生时按语义召回拼成 `[历史参考]` 注入 prompt——自建轻量 RAG(embed + 内存余弦扫描,不引 sqlite-vec)。**全仓最复杂的域。**

### 聚合根 RagChunk(`domain/refinery/RagChunk.java`)

- **标识**:`id`(UUID,`INSERT OR IGNORE`)。
- **构造期不变量**(私有构造 `:60-77`):必填 `requireNonNull`;`score ∈ [0,1]` 非 NaN;`embedding.length > 0`;演进字段空值兼容默认(`sourceType→CHAT`、`tier→EXPLORATORY`、`env→"unknown"`)。
- **注意**:`expires_at = now + ttlDays[category]*86400` 这条规则**不在聚合内**,而在 `RefineryAppServiceImpl.buildChunk:231-251`——聚合只把 `expiresAt` 当可空字段被动接收,分层瑕疵(见健康度)。
- **软删/归档**:`archive(Instant)` 幂等保护(已归档再调抛异常)。召回路径靠 `archived_at IS NULL` 过滤。
- **状态迁移**:`upgradeTier(TrustTier)`,枚举序 VERIFIED(0)/PENDING(1)/EXPLORATORY(2),`guard newTier.ordinal() > tier.ordinal()` 拒绝降级。**🔴 该方法只有单测在用,生产路径绕过它走 SQL UPDATE**(见健康度)。

### 核心领域概念

- **TtlCategory(过期策略)+ TrustTier/TrustTierPolicy(可信度策略)是正交两维**。TtlCategory(CODE/DEPLOY/BUSINESS/GENERAL)只管「多久过期」,天数由 `RefineryProperties.Ttl`(14/30/60/30 天)换算。TrustTier(VERIFIED/PENDING/EXPLORATORY)决定召回池过滤。
- **TrustTierPolicy** 是子域唯一允许「判定可信度」的位置,`DefaultTrustTierPolicy` 规则表:

  | sourceType | verdict | tier | ingest |
  |---|---|---|---|
  | CHAT | * | EXPLORATORY | yes |
  | DIAGNOSE | POSITIVE | VERIFIED | yes |
  | DIAGNOSE | NONE | PENDING | yes |
  | DIAGNOSE | NEGATIVE | — | **NO(入口拦截)** |

- **CosineSimilarity**:纯静态工具,算余弦 ∈ [-1,1],维度不一致/零范数抛异常。召回重排的算法基石,**正确落在 domain**。
- **RefinedContent / ConversationView / ConversationTurn**:LLM 精炼的输入输出契约。`ConversationView` 是上游投递进子域的**统一入参 VO,边界规则:子域只看 view,不 import 任何上游聚合类**(防腐层);`RefinedContent` 是 LLM 输出五字段(title/triggerSignals/context/process/conclusion)。
- **SessionRefineryState / DiagnoseRefineryState**:幂等游标(非聚合状态迁移)。哨兵常量 `LAST_ERROR_BELOW_THRESHOLD="score below threshold"` 区分「有意丢弃不重试」与「真失败重试」。
- **DiscardedRefineRecord**:below-threshold 留痕,无 embedding、不参与召回,只供管理台展示 + 阈值校准。

### 两条主管道

**管道 1 — refine + ingest(评分→embed→入库)**,汇到 `ingestCore(view)`:
1. `ChatViewBuilder` / `DiagnoseViewBuilder` 把聚合转 `ConversationView`(空消息 → `Optional.empty()` 跳过)。
2. `tierPolicy.shouldIngest` 入口拦截(DIAGNOSE+NEGATIVE 直接丢,不喂 LLM)。
3. (仅 chat)`shouldSkip` 秒级精度幂等判断。
4. `ConversationRefinery.refine`:`sliceAndSanitize`(token-budget 倒序切片 + 脱敏正则)→ 填 prompt → `invokeCli`(瞬态 5xx/429 指数退避重试)→ `LlmJsonExtractor` → `parseResult` 出 7 字段 JSON,任何环节失败统一 `RefineException`。
5. **阈值丢弃**:`score < scoreThreshold`(默认 0.5)→ `persistDiscardedIfEnabled` 旁路留痕。
6. `embeddingClient.embed`(`ArkEmbeddingClient`)。
7. `buildChunk` 算 expiresAt + `tierPolicy.decide` 定 tier → `chunkRepo.save`,embedding 经 `EmbeddingCodec.encode` 编为**小端 IEEE754 BLOB**。

**管道 2 — recall + rerank(召回重排)**,`RefineryRecaller.recallWithFilter`:
1. `embed(query)` + `chunkRepo.findActive(now)` 全表扫活跃 chunk。
2. 池过滤:chat 默认仅 CHAT(开 `crossSourceEnabled` 才纳入 DIAGNOSE+VERIFIED);诊断仅 DIAGNOSE+VERIFIED(防探索性内容污染诊断)。
3. **余弦硬闸**:`vectorSim < minVectorScore` 在融合排序前直接出局——融合分含 `γ·decay·score` 与 query 无关的常数底会污染绝对/相对阈值,余弦是未被污染的语义信号。
4. **融合分**:`final = α·cosine + β·jaccard(query,triggerSignals) + γ·decay·score`(默认 0.7/0.2/0.2)。jaccard 走 `tokenize`(ASCII 连续段 + CJK 相邻二元组,解决中文无空格黏成巨型 token);`timeDecay` 半衰期 30 天。
5. 降序 → 绝对闸 `minScore` + 相对闸 `topScore*minScoreRatio`(降序故任一不满足即 break)。
6. `describe` 拼 `[历史参考 i/n]` 前缀;空白/斜杠命令/0命中/异常一律静默透传。

### 触发器

- **ChatRefineryTrigger**(`infra/refinery/scheduling`):`scheduleWithFixedDelay`,拉候选(`SqliteSessionRepo.findIdsWithLastMessageBefore` 的 LEFT JOIN:从未评过 OR 有新消息 OR 失败可重试)→ 串行 `refineAndIngest` → tick 末 `archiveExpiredBefore` 软删过期。
- **DiagnoseRefineryTrigger**:非定时器,由 `DiagnoseAppServiceImpl` 在诊断终态后调 `onTerminal`,幂等靠 `stateRepo.findByTaskId` 短路,异常吞成 `last_error` 绝不阻塞诊断。

### 端口与适配

- **EmbeddingClient**(domain 端口)→ `ArkEmbeddingClient`(Volcengine Ark,OpenAI 兼容协议),`@PostConstruct` embed("ping") 校验维度,不一致 fail-fast。
- **RagChunkRepository**:读侧注释明确**为何不引 sqlite-vec**(起步 < 1 万 chunk,内存余弦可接受,量级上来再拆 CQRS 读模型)。但接口偏胖(19 方法,混 lifecycle + 分页 + 统计投影 + tier 维护)。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 余弦相似度 | `CosineSimilarity.cosine` | ✅ domain |
| 融合分 / 余弦硬闸 / 绝对相对闸 / CJK 分词 / jaccard / 时间衰减 / 池过滤 | `RefineryRecaller`(app) | 🔴 **核心领域算法整体在 app** |
| score < threshold 丢弃 | `ingestCore`(读 props) | ✅ app 编排 |
| expires_at = now + ttlDays·86400 | `buildChunk`(app) | 🔴 **构造期不变量在 app** |
| tier 决策 / shouldIngest | `DefaultTrustTierPolicy` | ✅ domain policy |
| score∈[0,1] / embedding 非空 / archive 幂等 / upgradeTier 方向 | `RagChunk` | ✅ domain 聚合 |
| 反馈→tier 维护 | `RefineryFeedbackApplier`(字符串 verdict 判断) | ⚠️ app |

### 跨域协作

消费两上游:**chat**(`ChatViewBuilder` 注入 `SessionRepository`,verdict 永远 NONE;召回经 `recall` query 参数回注 chat 消息)、**diagnose**(`DiagnoseViewBuilder` 终态转 view + `DiagnosisFeedbackQueryService` 查询工单 verdict;启动前 `DiagnoseRecallAugmenter` 注入相似历史诊断)。ticket verdict 变更后驱动 `RefineryFeedbackApplier` 闭环 tier。**子域边界守得最好——上游聚合全被挡在 `ConversationView` 之外。**

### 分层健康度与重构建议

**好**:端口/适配方向全对,`ConversationView` 防腐层到位,聚合不变量正确收口,`TrustTierPolicy` 显式收成 domain policy,`CosineSimilarity` 落 domain。

**异味(按严重度)**:
1. 🔴 **重排算法整体在 app**——融合分/余弦闸/分词/jaccard/时间衰减/池过滤全在 `RefineryRecaller`(338 行),这是子域最核心的领域算法却散在 app。建议下沉 `RecallRanker` 领域服务,app 只留「取 query embedding → 调 ranker → 拼前缀」。
2. 🔴 **`expires_at` 构造期不变量逃逸 app**——建议 `RagChunk.create(view, content, score, vec, ttlConfig, now)` 工厂把 TTL 换算收进聚合。
3. 🔴 **聚合状态迁移形同虚设**——`upgradeTier` 只有单测调用;生产 `RefineryFeedbackApplier` 直接 `chunkRepo.updateTier` 走 SQL,不经方向校验。「只能向上升级」不变量生产里没生效(SQL 能把 VERIFIED 写回任意 tier)。要么走「load→upgradeTier→save」,要么删掉误导方法。
4. ⚠️ `RagChunkRepository` 过胖(统计投影该拆 `RefineryStatsQueryService`)。
5. ⚠️ verdict 中文字符串映射在 `DiagnoseViewBuilder` 和 `RefineryFeedbackApplier` 两处各写一遍,宜收成 `VerdictSignal.fromFeishuLabel(String)`。

---

## 4. issuelog — 问题沉淀(支撑域)

**职责**:把一次诊断结论(手动或定时批量)经 LLM 精炼/启发式兜底压成结构化七字段草稿,查重去噪后经人工审核落盘为工作目录里的 `docs/issue-log/issue/I-xxx-*.md` 经验条目,供后续 agent grep 召回。

### 核心聚合 / 实体

| 类型 | 角色 | 不变量 | 位置 |
|---|---|---|---|
| `IssueLogEntry` | 聚合根(已落盘条目) | `id` 匹配 `^I-\d+$`;draft/createdAt 非 null;filePath 非空白 | `IssueLogEntry.java:34-55` |
| `IssueLogDraft` | VO(编辑期载体) | title 必填 trim;categories/services 至少 1 非空 token;triggerSignals 可空;其余 null→`""`;列表不可变 | `IssueLogDraft.java:78-132` |
| `IssueLogCandidate` | 实体(回填候选) | taskId/workingDir/status/minedAt 非空;draft 在 ERROR 态可 null | `IssueLogCandidate.java:46-82` |
| `IndexMetadata` | VO(INDEX 已有类型/服务清单) | 首次出现顺序去重、不可变 | `IndexMetadata.java:16-29` |

### 候选状态机 CandidateStatus

`PENDING_REVIEW / PROMOTED / REJECTED / DROPPED / ERROR`。创建即落定 `PENDING_REVIEW` 或 `DROPPED`(`!reusable || decision==DUP → DROPPED`);`PENDING_REVIEW → PROMOTED`(`markPromoted` 落定 issueId+reviewedAt)/`REJECTED`(`markRejected`)。`approve`/`reject` 入口过 `requirePendingReview` 守卫,非 PENDING_REVIEW 抛 `IllegalStateException`(→ 409)。状态翻转在聚合方法内、守卫在 app——**分层正确**。

### 两条产出路径

**路径 1 — 手动沉淀**(诊断详情抽屉 → draft → 落盘):
`GET .../issue-log/draft` → `draftFromTask`:取 task、`autoInit` 时 `ensureInitialized`、`nextId` 预算 id → `refine.enabled` 走 `draftProducer.produce`(先 `IssueLogRefinery.refine` CLI 180s,catch `RefineException` 降级 `DraftBuilder` 启发式——按钮永不因 LLM 不稳失效)。`IssueLogRefinery` 先用 `DiagnoseTranscriptCompactor.compact` 把 stream-json 压成「排查骨架」再渲染 prompt。用户编辑确认 → `POST .../issue-log` → `FileSystemIssueLogRepository.save`:**锁内** `ensureInitialized → nextId 重算 → toSlug → writeIssueFile → appendIndexRow`。

**路径 2 — 定时回填**(`IssueLogBackfillService`):
`IssueLogBackfillScheduler`(启动 60s 后首跑,fixedDelay)→ `runOnce` 经 `AtomicBoolean.compareAndSet` 单例保护 → `doRun`:拉 SUCCESS 诊断 id、已挖集合(幂等)、批量飞书反馈,逐条 `classify` 六分类分流 → 入池走 `processOne`:`produce`(统一降级)→ `IssueLogDedupMatcher.match`(CLI 判 NEW/MERGE/DUP,失败 `failOpen`=NEW+低置信,宁可多送审不丢经验)→ 算初始状态 → `candidateRepo.save`。**只产候选,绝不自动归档**。审核 `approve` → `archive`:MERGE 候选且有 mergeTarget 且 `merge.enabled` 时调 `IssueLogMerger.merge` 让 agent 就地改写既有 md,失败一律兜底新建。

### 文件仓储 + 锁

`WorkingDirIssueLogLockRegistry` 按 `workingDir.normalize()` 字符串 key 发 `ReentrantLock`,解决**同一工作目录两次并发 save 抢同一 id 撞号**。`save` 进锁后才 `nextId`,端口契约明确要求「锁内重算 id,不信任外部预分配」——`draftFromTask` 返回的 `suggestedNextId` 仅供前端展示。`nextId` 全量扫 INDEX.md 取最大序号 +1,`formatId` 为 `I-%03d`。锁仅进程内有效,多实例部署需升级文件锁。

### 核心规则归属

| 规则 | 位置 | 评价 |
|---|---|---|
| 六分类分流(bizStatus + verdict + 等待窗口) | `IssueLogBackfillService.classify:202-225` | 🔴 **app 泄漏**:对 `FeedbackSignal` getter 做语义比对 + 时间窗口判断,应下沉 domain policy |
| DROPPED vs PENDING_REVIEW 初始状态 | `processOne:257-259` | ⚠️ 对 `MatchResult` getter 做业务判断,宜收 `IssueLogCandidate` 工厂 |
| 查重 NEW/MERGE/DUP | `IssueLogDedupMatcher` | ✅ 判断外包 CLI,matcher 是适配器 + fail-open |
| 草稿七字段构造校验 | `IssueLogDraft:78-132` | ✅ 不变量收在 VO |
| id 格式 + 锁内重算 | `IssueLogEntry` + `FileSystemIssueLogRepository.save` | ✅ id 不变量在聚合,并发分配在 infra 锁内 |
| 状态迁移守卫 | `requirePendingReview` + `markPromoted/Rejected` | ✅ 守卫在 app、翻转在聚合 |

### 跨域协作

**diagnose**(上游来源):两条路径都以 `DiagnoseTask` 为原料,CLI 复用 `task.getAgentType/getWorkingDir`,`DiagnoseTranscriptCompactor` 消费 stream-json result。**ticket**(工单反馈):六分类通过平台无关 `DiagnosisFeedbackQueryService` 读取 `FeedbackSignal`(verdict/bizStatus/humanSolution/misrouteReason),等待窗口默认 48h。

### 分层健康度与重构建议

**良性**:七/八字段不变量收在 VO;读写分治(`IssueLogRepository` + `IssueLogCandidateRepository` 均 domain 定义、infra 实现);状态迁移封装聚合方法。

**异味**:
1. 🔴 **六分类条件判断散 app**(`classify`):对 `FeedbackSignal` getter 多分支语义比对 + 时间窗口,应下沉 `BackfillEligibilityPolicy.classify(...)`。包级可见纯为测试妥协,是「给错位代码补测试」的征兆。
2. ⚠️ 候选初始状态在 app 计算,宜 `IssueLogCandidate.fromMatch(...)` 工厂。
3. 🔴 **bug 级保真缺口**:`SqliteIssueLogCandidateRepo.serializeDraft` **不写 `triggerSignals`**,`deserializeDraft` 用 7 参旧构造器还原 → 候选 round-trip 后 triggerSignals 永久丢失,审核列表看不到、只能审核员重填。
4. ⚠️ `classify` 用注入 `Clock`、`processOne` 建候选 minedAt 用 `Instant.now()`,测试可控性割裂。
5. ⚠️ `ensureInitialized` 三处重复;`DraftBuilder.KNOWN_PREFIXES` 死字段应删。

---

## 5. ticket — IM 诊断工单(支撑域)

**职责**:接收 IM 事件并建立诊断工单,封装提交、诊断、复核、通知、反馈、关闭与重开状态,通过平台无关消息模型连接飞书 IM 与 diagnose/issuelog/refinery。

### 聚合根 Ticket(`domain/ticket/Ticket.java`)

- **标识**:`TicketId`;来源由 `TicketSource` 标识,当前主入口为 IM。
- **状态机**:`TicketStatus` 覆盖入队、诊断中、提交失败/死亡、诊断成功/失败、已放行、已关闭与重开。
- **核心规则**:聚合负责状态迁移、重试次数、补充轮次、反馈和复核信息;app 层只编排 repository、diagnose 与通知。
- **幂等**:入站事件先落 inbox;诊断提交使用 `clientToken()`;卡片反馈使用 nonce + actionVersion 防重复/过期提交。

### 应用编排

- `InboundEventWorker` 消费标准化 `ImEvent`,由 dispatcher 判断新建工单、补充上下文或反馈。
- `TicketAppService` 建单并提交 diagnose;`TicketDiagnoseSubscriber` 消费诊断终态并推进聚合。
- `TicketReviewGate` 控制自动放行或驻留人工复核;`TicketNotificationService` 负责卡片发送与更新。
- `TicketFeedbackService` / `TicketAdminService` 写入平台无关反馈,并通知 refinery 调整知识 tier。

### 端口与适配

- `ImOutboundGateway` 隔离卡片发送协议;`FeishuOutboundGateway` 是飞书实现。
- `ImInboxRepository` 保证入站事件先落盘再消费;`TicketRepository` 只管理工单聚合生命周期。
- `FeishuLongConnEventSink` 把飞书事件归一化为 domain messaging 模型,不把飞书 SDK 类型泄漏到 app/domain。

### 分层健康度

状态迁移集中在 `Ticket` 聚合,app 层以编排为主;平台协议与持久化细节位于 infra。反馈读侧通过 `DiagnosisFeedbackQueryService` 返回投影,供 issuelog/refinery 消费。

---

## 6. auth — 鉴权(支撑域)

**职责**：使用本地登录会话识别“当前请求是谁”，据此驱动用户数据隔离与登录跳转；管理员仍走独立口令鉴权。

### 核心模型

- VO `LoginUser`：userId/userName/userEmail 三字段，纯数据载体。
- 聚合根 `ManualSession`：负责工号、用户名、TTL 不变量和安全随机令牌生成。
- 端口 `ManualSessionRepository` / `UserContext`：分别负责会话生命周期和当前请求用户读取。
- 领域服务 `ManualSessionAuthenticator`：查询会话、判断过期并清理失效行；`ManualSessionFactory` 封装统一 TTL 和时钟。
- 领域服务 `CurrentUserProvider`：基于 `UserContext` 给隔离决策。`userId=null` 的后台线程或系统任务不做用户过滤，避免后台查询误判数据不存在。

### 关键规则

- 主路径会话隔离 → `CurrentUserProvider.shouldFilter()`，规则位于 Domain。
- 会话认证 → `ManualSessionAuthenticator.authenticate()`，过期判断与清理集中在 Domain 服务。
- 所有非公开入口统一要求登录，不再按 Host 提供匿名绕过或默认用户。

### 编排

`AuthAppService` 仅编排会话创建、认证与删除。`AuthController` 负责 Cookie 读写和状态 DTO；`SessionAuthFilter` 负责公开路径判断、用户上下文绑定以及“页面 302 / API 401+loginUrl”分流。过滤器通过 `AuthSecurityConfig` 显式注册，不污染 Controller 切片测试。

### 跨域协作

`CurrentUserProvider` 是 chat 会话查询的过滤闸;`PublicPaths` 放行 `/api/diagnose*`(诊断走自己的 ApiKeyAuthFilter);Filter 把 userId 写 MDC 供链路追踪。

### 分层健康度与重构建议

- `CurrentUserProvider` 由 `DomainServiceConfig` 装配，Domain 不依赖 Spring。
- `AuthAppService` 只依赖 Domain 类型；配置绑定和 Servlet Filter 注册留在 Infrastructure。
- `AuthController#status` 与 `SessionAuthFilter` 复用同一个认证应用服务，避免规则分叉。

---

## 7. schedule — 定时任务(支撑域)

**职责**:按 cron 周期触发 prompt,落库管理任务 CRUD,运行期动态注册/取消 cron 触发器,每次触发复用 chat 链路产出一条独立会话。

### 核心模型

- 实体 `ScheduledTask`:id/name/cronExpr/prompt/workingDir/enabled + 审计时间戳 + lastRunAt/lastSessionId。两个构造器(新建/重建),Lombok getter/setter。**无任何不变量校验**——构造器不校验 cron、不校验必填。
- 端口 `ScheduledTaskRepository`:写侧 lifecycle(save/update/findById/findAll/findAllEnabled/deleteById/updateLastRun),签名全 domain 类型,分层正确。

### 关键规则

- cron 合法性(构造期不变量)→ 🔴 **在 app 层校验,且散落三处**:`ScheduledTaskServiceImpl.create:53` / `update:73` 各 `new CronTrigger(cronExpr)` 试构造,`DynamicTaskScheduler.scheduleTask:66` 第三处又校验一遍。
- 启用/禁用迁移 → `ScheduledTaskServiceImpl.toggleEnabled`:app 层 `task.setEnabled(!task.isEnabled())` 翻转。
- lastRun 记录 → `updateLastRun` repo 直写。

### 编排

`ScheduledTaskServiceImpl`:CRUD + 落库后调 `DynamicTaskScheduler` 注册/取消;`doExecute` 是 cron 与手动触发共用核心——`ChatSession.forTask` 建会话、存消息、`AgentGateway.runStream` 跑 CLI、`updateLastRun`。`DynamicTaskScheduler`:`@EventListener(ContextRefreshedEvent)` init 回注 self 破循环依赖 + 加载 `findAllEnabled` 注册;`scheduleTask` 先 cancel 再按 `CronTrigger` 注册到 `TaskScheduler`,future 存 map。infra `SqliteScheduledTaskRepo` 标准 JdbcTemplate,无业务判断。

### 分层健康度与重构建议

- 🔴 **cron 构造期不变量泄漏 app 且三处复制**(命中「替聚合做格式校验 → 改聚合工厂」),应收口 `ScheduledTask.create(...)` 内校验。
- ⚠️ **贫血实体 + app 代劳状态迁移**:`toggleEnabled` 的翻转+时间戳应是聚合方法 `task.toggle()`。
- ⚠️ `doExecute` 硬编码 `AgentType.CLAUDE`——定时任务永远走 Claude,无法选 Codex,且 `ScheduledTask` 模型根本没存 agentType。
- ⚠️ `DynamicTaskScheduler` 放 app 但本质 infra(纯 `TaskScheduler`/cron 技术编排),与 Service 构成循环依赖靠 setter 注入破环,位置与环都偏味。

---

## 8. slashcommand — 斜杠命令(支撑域)

**职责**:扫描 `.claude/commands`、`.claude/skills`、`.codex/skills` 等目录解析带 YAML frontmatter 的命令定义,发送时把 `/cmd args` 展开为模板体并替换 `$ARGUMENTS`。**四个支撑域里最干净的。**

### 核心模型

- VO `SlashCommand`:name/description/argumentHint/body/skill,全 final 不可变,纯数据载体。
- 领域服务 `SlashCommandExpander`:识别 + 展开规则承载者。
- 端口 `SlashCommandScanner`:`scan(workingDir) → List`,项目级优先主目录级。

### 关键规则

- 展开规则 → `SlashCommandExpander.expandIfCommand`:非 `/` 开头原样返回(卫语句);切命令名(首空格前)与参数(空格后 trim);扫描匹配同名命令,未命中原样返回;命中则 `body.replace("$ARGUMENTS", arguments)`。✅ 规则在 domain。
- 扫描/解析规则 → infra `FileSlashCommandScanner`:项目级先扫、主目录级按名去重 fallback;commands 目录递归子目录用 `:` 拼前缀(如 `spec:bizflow`);skills 目录每个子目录 `SKILL.md` 解析为单命令;frontmatter 解析 name/description/argument-hint。✅ 属协议/IO 适配。

### 编排

无独立 app 服务;`SlashCommandExpander` 由 infra `SlashCommandBeanRegistrar` `@Bean` 组装注入 scanner(领域对象在 infra config 装配,干净)。`SlashCommandProperties`(`agent.slash-command.*`)配置目录。

### 分层健康度

- ✅ 整体最干净:VO 不可变、规则在 domain 服务、IO 在 infra、端口隔离到位。
- ⚠️ `SlashCommand` 残留空 javadoc 占位(删 getter 后留下的死注释)。
- ⚠️ `expandIfCommand` 每次落盘 `scanner.scan` 无缓存(当前规模无碍)。

---

## 9. worktree — Git 工作树(支撑域)

**职责**:在含多个独立 git 仓库的工作区里按分支批量建/更/删 git worktree,实现分支级隔离;无目标分支的仓库 fallback 到默认分支并以链接复用。

### 核心模型

- VO/领域服务 `BranchNameValidator`:**本域唯一 domain 类**。`ALLOWED_KEYWORDS`=release/hotfix/feature/cr/bugfix;`validateAndNormalize`:trim 后必须以允许关键字开头**且长度 > 关键字本身**(挡住纯 `release` 这种空主体),否则抛异常。✅ 规则在 domain。

### 关键规则

- 分支命名规范 → `BranchNameValidator.validateAndNormalize`(唯一被正确建模的领域规则)。
- worktree 创建策略(本地/远程分支存在性、fallback、复用已检出路径)→ `WorktreeService.createWorktreeForRepo`:localExists/remoteExists 判断、fallback 到 defaultBranch(优先 origin/HEAD)、已检出则建目录链接复用。
- 链接 vs 真实 worktree 区分(NTFS junction 检测)→ `isDirectoryLink`:用 `toRealPath` 跟随 junction 比对父目录,显式规避 8.3 短名误判。update/remove 据此跳过链接叶子不误 pull/误删主仓库。

### 编排

`WorktreeService`(641 行):`switchBranch`(收集 git 仓→并行 fetch→逐仓建 worktree)、`updateBranch`(并行 `git pull --ff-only`,靠 HEAD 前后比对做 locale 无关分类)、`listWorktrees`、`removeWorktree`。大量 `ProcessBuilder` 直接 spawn git + 文件树遍历 + 跨平台链接(Windows `mklink /J`、Unix symlink)。`WorktreeController` 4 端点,**仅 `/switch` 调 `validateAndNormalize`**。

### 分层健康度与重构建议

- 🔴 **App 层承载大量 infra 职责**:`WorktreeService` 把 git 子进程执行、文件树遍历、NTFS junction 检测全塞进 application 层(641 行超类红线),应下沉 infra `GitWorktreeGateway`,app 只编排。
- 🔴 **分支名规则双轨且不一致**:domain `BranchNameValidator`(关键字白名单)与 service 私有 `safeBranchName`(仅替非法字符为 `-`)是两套规则,后者落目录未走领域校验。
- 🔴 **校验入口不对称**:`/switch` 校验分支名,但 `/update`、`/remove` 不校验——可对任意未规范分支名做 update/remove。
- ⚠️ 返回裸 `Map<String,Object>` 而非 DTO;死代码 `execOutput` 未引用。

---

## 附录 A — 横切问题(跨域共性异味)

| 问题 | 命中的域 | 说明 |
|---|---|---|
| **Repository 读写未分治** | chat / diagnose / refinery | lifecycle 与读模型投影混在一个接口,违反「Repository 3-5 方法返聚合根,读模型拆 `XxxQueryService`」。共性折中:都无 ORM 泄漏,务实但越界。 |
| **构造期不变量逃逸到 app** | refinery(`expires_at`)/ schedule(cron)/ chat(workingDir) | 应收进聚合工厂 `Aggregate.create(...)`。 |
| **贫血聚合 + app 代劳状态迁移** | chat(消息/反馈)/ schedule(toggle) | 状态迁移被 app 用 getter/setter 重组,聚合方法缺失或形同虚设。 |
| **领域规则散在 app 层** | refinery(重排算法)/ issuelog(六分类) | 核心判定逻辑该下沉 domain policy/领域服务。 |
| **聚合不完整(状态在边界外被改)** | refinery(tier 经 SQL 绕过方向校验) | 裸 SQL 直改聚合根不拥有/不经方法的状态。 |

## 附录 B — 重构优先级建议

**P0(bug / 数据保真,先修)**:
1. issuelog `triggerSignals` 持久化 round-trip 丢失(`SqliteIssueLogCandidateRepo` 序列化漏字段)。
2. refinery `RagChunk.upgradeTier` 方向校验被生产 SQL 绕过(`updateTier` 不经聚合)。

**P1(领域算法/规则下沉,收复 domain)**:
3. refinery 重排算法从 `RefineryRecaller` 下沉 `RecallRanker` 领域服务。
4. issuelog 六分类下沉 `BackfillEligibilityPolicy`。
5. schedule cron 不变量收口 `ScheduledTask.create(...)`,消除三处复制。
6. chat rewind/resume/反馈收成 `ChatSession` 业务方法 + `@Transactional`,让聚合成为一致性边界。

**P2(结构整理,降耦合)**:
7. 各域拆 `XxxQueryService` 承接读模型投影。
8. worktree git 操作下沉 infra `GitWorktreeGateway`,统一分支校验入口。

## 附录 C — 死代码 / 休眠组件清单

| 组件 | 位置 | 现状 |
|---|---|---|
| `JsonFileSessionRepo` | infra | 不实现接口,生产无人注入;文档宣称的「JSON 备份层」未接线 |
| `DiagnoseTask.timeout()` + TIMEOUT 态 | domain/diagnose | 生产零调用,超时实际落 FAILED,TIMEOUT 永不产生 |
| 诊断超时清扫器 | `findRunningStartedBefore` / `findTerminalExpiredBefore` | 无 `@Scheduled` 调用,过期任务不会物理清理 |
| `ChatSession.addMessage` | domain/chat | 死代码,消息走 repo 直插 |
| `RagChunk.upgradeTier` | domain/refinery | 仅单测调用,生产走 SQL |
| `DraftBuilder.KNOWN_PREFIXES` / `WorktreeService.execOutput` / `SlashCommand` 空 javadoc | 多处 | 未引用死字段/死注释 |

---

*本文档基于源码实读生成,`文件名:行号` 可能随后续提交漂移,定位时以符号名为准。*
