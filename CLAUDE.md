# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot web service that provides a browser UI for driving local CLI AI agents (Claude / Codex). Users interact via chat interface; the backend spawns CLI subprocesses and streams responses back via SSE.

## Build & Run Commands

```bash
mvn clean package                # Build JAR
mvn spring-boot:run              # Run locally (port 17988)
mvn test                         # Run all tests
mvn test -Dtest=ChatFlowTest     # Run single test class
mvn test -Dtest=ChatFlowTest#testMethodName  # Run single test method
mvn pmd:check                    # Code quality check (Alibaba P3C)
./scripts/service.sh start|stop|restart|status|logs # 现网实例 (端口 18092; Windows 用 .\scripts\service.ps1)
```

> **Claude 行为约束**：代码改动后**不要自动 `mvn package` / `scripts/service.sh restart`**。除非用户明确说"编译"/"打包"/"重启"/"部署"，否则停在改完代码 + 跑测试这一步，让用户自己控制何时重启服务。

> **部署发布**（正式上线）→ 用户明确要求时，用 `./scripts/service.sh restart` 重启 **18092** 现网实例。同样受上面的"不自动执行"约束：除非用户明确说编译/部署/重启，否则停在改完代码 + 跑测试。

## Tech Stack

- **Backend**: Spring Boot 3.3.13 / Java 21 / Maven (jakarta 命名空间; 运行时需 JDK 21+)
- **Database**: SQLite (sessions, scheduled tasks, diagnose tasks, tickets, knowledge state)
- **Frontend**: Vue 3 + Element Plus via CDN (no build step, static HTML/JS/CSS in `src/main/resources/static/`)
- **Communication**: REST + Server-Sent Events (SSE)
- **Code Quality**: Alibaba P3C (PMD plugin)
- **External integrations**: Feishu Open Platform (IM / cards / contacts)

## Architecture

DDD + Hexagonal Architecture with four layers:

```
interfaces/   → REST Controllers + DTOs (API boundary)
                Chat, Fs, Auth, Share, ScheduledTask, Worktree, Diagnose, DiagnoseHistory
app/          → Application services (orchestration, no domain logic)
                agentrun/ (prompt assembly pipeline), chat, scheduled-task, worktree, diagnose/, ticket/
domain/       → Aggregate roots, value objects, repository interfaces
                chat, scheduled-task, slash-command, branch-validator, diagnose/, ticket/, messaging/
infra/        → CLI process execution, SQLite repos, auth filter, config properties
                cli/ (CliDialect strategy), diagnose/, messaging/, UploadPicStore, ...
adapter/      → Port interfaces (AgentGateway, messaging and external gateways)
```

### Key Data Flow: Chat Message

1. `ChatController` receives message → delegates to `ChatAppServiceImpl`
2. `ChatAppServiceImpl` manages `ChatSession` lifecycle, calls `AgentGateway`
3. `AgentCliGateway` routes to the matching `CliDialect` (Claude / Codex) which assembles the command and spawns the CLI subprocess, reads line-by-line JSON
4. `StreamChunkHandler` processes chunks (Codex events are first normalized via `CodexEventNormalizer` to the unified frontend contract) → SSE events pushed to browser via `SseEmitter`

### Key Data Flow: Remote Diagnose

1. `DiagnoseController` authenticates via `ApiKeyAuthFilter` (`X-API-Key`), accepts `Idempotency-Key` header
2. `DiagnoseAppServiceImpl` persists a `DiagnoseTask`, submits the run to the diagnose executor, and returns immediately with `taskId` + stream URL
3. The worker reuses `AgentGateway` to drive the CLI; events fan out through `DiagnoseEventBus` to SSE subscribers (supports `Last-Event-ID` resume)
4. `DiagnoseHistoryController` exposes list/detail and `continue-as-chat`, which converts a diagnose task into a resumable `ChatSession`

### Key Data Flow: AgentRun Prompt Assembly (新增)

`app/agentrun/` 是应用层执行管线（**不是领域聚合**），把"组装一次 run 的 prompt"从各入口收敛到统一 pipeline。`DiagnoseAppServiceImpl` / `WorkflowRunner` / `ScheduledTaskServiceImpl` 均经它装配 prompt。

1. 入口构造 `AgentRunContext`：`originalInput` + 两条正交轴 `runForm`（CHAT / DIAGNOSE / WORKFLOW_STEP / SCHEDULED / CUSTOM，决定执行引擎行为）× `sourceDomain`（`SourceType` DIAGNOSE / CHAT / GENERAL，决定 RAG 过滤与沉淀归属）+ `workingDir` / `env` / `outputInstruction` + `RunRecallPolicy`
2. `RunRecallPolicyFactory.forRun(runForm, sourceDomain)` 按 `AgentRunProperties`（`agent.run.*`）建策略：`workspace-context-enabled` / `workspace-knowledge-enabled` / `recall-top-k`（env 覆盖 `AGENT_RUN_*`，紧急止血可关）。历史 RAG 仅在 `sourceDomain=DIAGNOSE` 开（再受内层 `agent.refinery.diagnose.recall-enabled` 控制）。`AgentRunContext` 未显式传 policy 时默认 `RunRecallPolicy.disabled()`（fail-safe，避免新入口忘配即全开）
3. `PromptAssemblyService` 按固定序跑 6 个 `PromptContributor`：`Env`(10) → `WorkspaceContext`(20) → `KnowledgePreRecall`(30) → `HistoricalRag`(40) → `UserInput`(50) → `OutputInstruction`(60)，拼成 `PromptPart` 列表、算 SHA-256 `promptHash`、返回 `PromptAssemblyResult`
4. **每个 Contributor 失败即降级为空、绝不阻断 run**；业务正文（诊断输出格式、env 文案）由业务域 / 配置注入，平台层只负责编排顺序与降级
5. **当前用户问题由 `UserInputContributor` 唯一持有**。命中诊断历史 RAG 时 legacy enhanced query 内已含 `[当前问题]`，靠 `PromptAssembly.ownsUserInput` 互斥跳过 `UserInputContributor`，避免重复注入
6. `WorkspaceContextResolver` 在 `agent.fs.roots` 白名单内由 `workingDir` 向上发现 workspace root + 约定知识索引（`docs/issue-log/INDEX.md` / `known-issues` / `playbooks`）+ 可选 `.agent-web.yml`（SnakeYAML 解析 `knowledge_indexes` / `guardrails`）。**`agent-web` 不发现 / 不读取 / 不注入 `AGENTS.md` / `CLAUDE.md`**，是否加载由具体 CLI 决定
7. guardrail 合并：env（`agent.envs[].prompt`）是基线，workspace manifest **只能收紧不能放松**（append-only），`guardrail_source` 记 `env` / `manifest` / `both`
8. **当前零 schema 变更**：`diagnose_task.query` 存的是最终 assembled prompt（非仅原始 query，诊断历史页会看到 ENV / Workspace / Knowledge / Historical RAG / User Input / Output 多段内容）；`recall_used` / `recall_chunk_ids` 回填；`RecallContribution` / `promptHash` / workspace hits / guardrail source 目前仅落日志，恢复用户原话与审计字段落库需另起独立 migration

### Key Data Flow: Feishu IM Ticket

1. 飞书长连接事件标准化后先写 IM inbox，由 `InboundEventWorker` 异步消费。
2. `TicketAppService` 建立工单并提交诊断；`TicketDiagnoseSubscriber` 消费诊断终态。
3. `TicketNotificationService` 发送或更新消息卡片；复核、反馈、关闭与重开规则封装在 `Ticket` 聚合。
4. 工单反馈经 `DiagnosisFeedbackQueryService` 提供给 issue-log 回填和 Knowledge Refinery。

### Key Data Flow: Issue-Log Generation (新增)

诊断详情抽屉 → "沉淀为 issue-log" 按钮 → 把诊断结论结构化落盘到 `<workingDir>/docs/issue-log/issue/I-xxx-*.md`，对齐 issue-log skill 读取规范。

1. `DiagnoseHistoryController#draftIssueLog` (`GET /api/diagnose-history/{taskId}/issue-log/draft`) 调 `IssueLogAppServiceImpl.draftFromTask`
2. `IssueLogAppServiceImpl` 先调 `IssueLogRefinery`（复用 `task.agentType` 的 CLI，180s 同步阻塞）让 LLM 把诊断结论压成 `title/slug/categories/services/triggerSignals/phenomenon/rootCause/solution/notes` 九字段 JSON；解析失败一律降级到启发式 `DraftBuilder`（会从 query/result 正则提取错误码/接口路径/表字段兜底触发词），按钮永远不会因为 LLM 不稳而失效
3. 前端 `el-dialog` 立即弹出 loading，结构化表单填充返回值；右侧只读 panel 贴诊断 result 全文供复制
4. 用户编辑确认后 `POST /api/diagnose-history/{taskId}/issue-log`：先 `IssueLogDraft.requireArchivable()`（触发词非空才允许落盘，422），再过 `IssueLogDedupMatcher` 查重闸门（判 DUP → 409 + 既有条目 ID，查重失败不阻断保存）→ `FileSystemIssueLogRepository.save` 在 `WorkingDirIssueLogLockRegistry` 锁内重算 ID、写 `issue/I-xxx-*.md`（文件名优先用精炼产出的英文 kebab slug）、追加 `INDEX.md`。查重输入是 `IndexProjection` 压缩的 ID/标题/触发词三列投影（不再截断 16k）
5. 不自动 git commit，用户在工作目录手动 `git add` 走 review；`agent.issue-log.*` 控制路径、`refine.*` 控制 LLM 精炼、`dedup.enabled/timeout-seconds` 控制保存前查重（e2e profile 关闭）。工作空间侧录入规范以 `qpon/.claude/skills/issue-log/SKILL.md` 为唯一事实源。

### Key Data Flow: Knowledge Refinery (会话/诊断向量召回, Phase 1-4 已落地)

`agent.refinery.enabled` 默认关. 打开后, 静默会话自动评分 → embed → 入向量库; 前端"RAG 召回"开关(默认开)开时每条消息自动召回历史结论拼进消息.

1. `ChatRefineryTrigger` (`agent.refinery.poll.*`) 每 tick 拉 `chat_session.last_message_at < now - silentMinutes` 候选 (`SessionRepository.findIdsWithLastMessageBefore`), 串行交给 `RefineryAppServiceImpl.refineAndIngest`
2. **refine**: `ConversationRefinery` 复用 `AgentCliInvoker.invokeSync(session.agentType, ..., 180s)`, 喂 `refinery-refine-prompt.md` 让 LLM 输出 `{score, ttl_category, title, triggerSignals, context, process, conclusion}`; JSON / score / ttl 校验失败统一抛 `RefineException`
3. **score < threshold** (默认 0.5) 直接丢弃, 只写 `chat_session_rag_state.last_error="score below threshold"`
4. **embed**: 调 `ArkEmbeddingClient` (OpenAI 兼容协议, 现指 OpenRouter + `qwen/qwen3-embedding-8b`, dim=4096; 2026-06-11 前为 Ark `doubao-embedding-vision` dim=2048, 因多模态模型的文本相关带被压扁致召回全灭而切换, 存量 chunk 已全量重嵌入) POST `{endpoint}/embeddings`, 启动 `@PostConstruct` 校验返回长度
5. **save**: 建 `RagChunk` (含 `expires_at = now + ttlDays[category] * 86400`), `SqliteRagChunkRepo` 把 embedding 走 `EmbeddingCodec` 编为小端 BLOB 落库, 同步写 state
6. **archive maintenance**: 每 tick 末尾 `chunkRepo.archiveExpiredBefore(now)` 批量软删过期 chunk
7. **召回拦截**: 前端"RAG 召回"开关(默认开, 持久化 localStorage)经 `recall` query 参数传入 `ChatController#stream` → `ChatAppServiceImpl#streamMessage(..., recallEnabled)`; `recallEnabled && Optional<RefineryRecaller>.isPresent()` 时整条消息作 query 调 `RefineryRecaller.describe`(斜杠命令/0命中/异常静默跳过), 命中则绕过 `SlashCommandExpander` 直接拼前缀。已无 `/recall` 前缀与服务端 `auto-first-message` 开关
8. **召回 + 重排**: `RefineryRecaller.recall` 全表 SELECT 活跃 chunk; 先过**余弦硬闸** (`recall.min-vector-score`, 默认 0.6, 按 qwen3-embedding-8b 空间标定: 同义提问≈0.74 / 相关口语≈0.64 / 无关≈0.19; **换 embedding 模型必须重标定**)——融合排序前剔掉 `cosine < 阈值` 的 chunk, 这是抗"融合分含 γ·decay·score 常数底污染"的真正相关性闸, 也是相对闸无法拒绝"最佳命中也是噪声"时的兜底; 幸存者再内存计算 `final = α·cosine + β·jaccard(query, triggerSignals) + γ·time_decay·score` (默认 0.7/0.2/0.1, 半衰期 30 天), 末尾叠加融合分绝对/相对闸 (`min-score`/`min-score-ratio`); 拼 `[历史参考]` 前缀注入到送 CLI 的 message。`refinery-recall` debug 日志带 `topCosine` 供阈值校准

不引 `sqlite-vec`: JVM 没有可靠 artifact, 起步阶段 chunk 量 < 1 万, 内存余弦扫描 < 50ms 即足够; 量级起来后接口 `RagChunkRepository` 留有切换余地.

### Storage Tiers (chat sessions)

- **L1**: `InMemorySessionRepo` — fast lookup for active sessions
- **L2**: `SqliteSessionRepo` — persistent across restarts
- **Backup**: `JsonFileSessionRepo` — JSON file export/import

### Slash Commands

`FileSlashCommandScanner` scans command dirs (default `.claude/commands/`) and skill dirs (default `.claude/skills/`, `.codex/skills/`) for `.md` / `SKILL.md` with YAML frontmatter. Paths are overridable via `agent.slash-command.command-dirs` / `agent.slash-command.skill-dirs`. `SlashCommandExpander` replaces `/cmd args` with template body at send time.

### Image Context

`FsController#uploadImage` accepts pasted / uploaded images. For chat callers (passing `sessionId`), `UploadPicStore` writes them under `<workingDir>/upload_pic/<sessionId>/`; for intake / no-session callers it falls back to the flat `<workingDir>/upload_pic/`. The UI shows up to 4 thumbnail chips above the input (×-removable, front-end only — server files stay) and appends absolute paths into the chat message text on send so the agent can read the file. `ChatAppService#deleteSession` purges the matching `upload_pic/<sessionId>/` directory when the session is removed.

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI executable path |
| `CODEX_CMD` | `codex` | Codex CLI executable path |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite database path |
| `OPENAI_API_KEY` | _(none)_ | Codex CLI auth; optional if logged in via `~/.codex/auth.json` |
| `CODEX_HOME` | `~/.codex` | Codex config/auth directory (`config.toml`, `auth.json`) |
| `REFINERY_EMBED_API_KEY` | _(none)_ | embedding API key (现为 OpenRouter), `agent.refinery.enabled=true` 时必填; 绝不可硬编码到 yml |
| `REFINERY_EMBED_ENDPOINT` | `https://openrouter.ai/api/v1` | embedding base URL (OpenAI 兼容协议) |
| `REFINERY_EMBED_MODEL` | `qwen/qwen3-embedding-8b` | embedding model, 维度需与 `agent.refinery.embedding.dimension` 一致, 不一致启动 fail-fast; 换模型须同步改并重嵌入存量 chunk |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | `true` | AgentRun workspace context 注入总开关（`agent.run.*`，紧急止血可关，无需改代码） |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | `true` | AgentRun workspace 知识预召回开关 |
| `AGENT_RUN_RECALL_TOP_K` | `8` | AgentRun 召回 top-K |
| `AGENT_CHAT_USER_ISOLATION_ENABLED` | `false`(yml) | 对话用户隔离总开关（`agent.chat.*`）。`false`=全员互见(可看其他用户对话)；`true`=普通用户仅见自己的会话+老数据。代码默认 `true`(安全)，yml 显式放开为 `false` |

`application.yml` drives Codex through the real `codex exec --json` path by
default (`agent.cli.codex.args` is empty, so `CodexCliDialect` builds the
`codex exec --json` command and `CodexEventNormalizer` normalizes events). To
fall back to the legacy text-output path, set a non-empty `agent.cli.codex.args`
template.

## Testing

JUnit 5 + Mockito + MockMvc。测试文件按生产代码同包放在 `src/test/java/com/example/agentweb/`。

### 测试金字塔（分层与职责）

按"启动什么 / Mock 什么 / 验什么"严格分层，**不要混用**。下层 bug 不要靠上层兜底，上层 bug 也不要拆到下层。

| 层 | 启动什么 | Mock 边界 | 验什么 | 典型成本 |
|---|---|---|---|---|
| **Domain 单测** | 零容器 | **不 Mock** — 用真实聚合根/VO | 不变量、状态迁移、业务规则 | <10ms |
| **Application 单测** | 零容器 + Mockito | Mock Repository / Gateway / Domain Service _接口_；**真实 Domain 对象** | 编排顺序、事务边界调用、参数透传 | <50ms |
| **Infra 轻量集成** | 零容器 + 真实 SQLite/HTTP/进程 | 不 Mock 被测组件，外部依赖（远端 HTTP）按需 Mock | SQL 方言、表锁/退避、协议适配 | <500ms |
| **Interface 切片** | `@WebMvcTest(XxxController.class)` | Mock `@MockBean ApplicationService` | `@RequestBody`/`@Valid`、状态码、`@RestControllerAdvice`、Filter 装配 | ~1s |
| **全栈集成** | `@SpringBootTest` + `TestCliStub` | CLI 用 echo stub；Feishu HTTP Mock；其余真实 | 跨切关注点：`@Transactional` 代理、SSE + `Last-Event-ID` 续传、`@Scheduled` 装配、Bean 装配本身 | 5-15s |

**目标比例**（条数级，非绝对）：Domain/App 单测 ≫ Infra 轻量集成 ≫ Interface 切片 > 全栈集成。

### 选层决策树

写测试前按顺序问：

1. **被测代码有外部副作用**（SQL / HTTP / 进程 / 文件 / 时钟）吗？
   - 无 → Domain 或 App 单测（看归属层，按 `App 层泄漏判定` 决定）
   - 有 → 进 2
2. **副作用是 Repository/Gateway 接口的内部实现吗**？
   - 是（如 `SqliteSessionRepo` 的 SQL） → **Infra 轻量集成**，真实 SQLite + `@TempDir`，**不要起 Spring**
   - 否 → 进 3
3. **要验证的是 Controller 边界**（DTO 校验、状态码、Filter）吗？
   - 是 → `@WebMvcTest`，**不要起 `@SpringBootTest`**
   - 否 → 进 4
4. **要验证的是跨层装配本身**（事务代理生效、SSE 续传、Scheduler 注册）？
   - 是 → `@SpringBootTest`，但每个跨切关注点 1 条就够，**不要每个功能都补一遍**
   - 否 → 回到 1，重新看归属层

### Application 层单测的反模式

- ❌ Mock Domain Service 验"调用顺序" → 重构就垮，测试**毫无业务保证**
- ❌ 把 SQL/HTTP 拼接放进 App 层再 Mock Repository → 错位先下沉到 Domain/Infra
- ✅ Mock Repository/Gateway 接口 + **真实 Domain 对象**调 `aggregate.method()` 验业务结果

### Infra 轻量集成模板（推荐）

参考 `SqliteTicketRepoTest`：`@TempDir` + 手动 `new SQLiteDataSource()` + `JdbcTemplate`，**不起 Spring 容器**。所有 `Sqlite*Repo` 都应走此模式。

```java
@TempDir Path tempDir;
SQLiteDataSource ds = new SQLiteDataSource();
ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
SqliteXxxRepo repo = new SqliteXxxRepo(new JdbcTemplate(ds));
```

### 必须保留 `@SpringBootTest` 的跨切关注点

下列场景**只能**用全栈集成测试覆盖，每项至少留 1 条：

| 关注点 | 锚定测试 |
|---|---|
| SSE 真实时序 + `Last-Event-ID` 续传 | `DiagnoseFlowTest` |
| `@Transactional` AOP 代理 + 回滚行为 | `ChatFlowTest` 等 |
| `@Scheduled` 装配 + SQLite 锁退避 | `ScheduledTaskTest` |
| 会话跨重启持久化 | `ResumeSessionTest` |
| Filter Chain 装配（`ApiKeyAuthFilter`） | 远程诊断鉴权链路 |

新功能**不要**再补 `@SpringBootTest`，除非命中以上跨切关注点。

### CLI 子进程 Stub

集成测试用 `TestCliStub.register(registry)` 把 `agent.cli.codex.exec/args` 指向跨平台 `echo`（Windows `cmd /c echo`、Linux `/bin/echo`），固定前缀 `Echo `。**不要**让集成测试调真实 Claude/Codex CLI。

### 前端测试（CDN-only 工程）

前端 `static/js/app.js` 是 CDN 引入的单文件 Vue 3，**不引入 Vite/Vitest 工程化生产代码**。测试工程独立在 `tests/`，两条路径已落地：

1. **纯函数单测 (Vitest)**：可外提的格式化/解析函数抽到 `static/js/lib/formatters.js` 走 UMD-lite（挂 `window.AgentFormatters` + `module.exports`），在独立 `tests/` npm 工程用 Vitest 跑。当前覆盖 6 个函数 + 1 个常量,46 个 case <500ms
2. **E2E 主链路 (Playwright)**：`tests/e2e/` 独立 Playwright 工程，自动启停 Spring Boot e2e profile (端口 18099)。覆盖 chat / diagnose / issue-log 三条主流程,3 spec 15s
3. **e2e profile** (`src/main/resources/application-e2e.yml`): Claude CLI = `cmd /c echo` (走前端兜底文本渲染), Codex CLI = `tests/e2e/fixtures/codex-json-stub.cmd` (输出固定 NDJSON 走 `CodexEventNormalizer`),关 IM 长连接 / `agent.issue-log.refine` / `agent.issue-log.backfill`,独立 db `data/agent-web-e2e.db`
4. **选择器约定**：用 Playwright `getByRole` / `getByText` 等语义化 API,**不要用** Element Plus 内部 class (`.el-dialog__body` 等会随版本碎)。新功能加 `data-test="xxx"` 属性是可选优化,目前主链路用语义化选择器已经稳
5. **踩坑·Playwright 必须在 `tests/` 目录里跑**：`playwright.config.ts` 在 `tests/` 下,只有 cwd=`tests/` 时才被自动加载 (它设了 `testDir: ./e2e`、webServer 自启 18099)。若从仓库根或别处跑 (尤其**后台/非交互执行,cwd 可能默认落在仓库根**),config 不生效 → Playwright 退化成扫整个 `tests/` 树,把 Vitest 的 `tests/unit/formatters.spec.ts` 也当 E2E 加载,报一串**假错**:`require is not defined in ES module scope` + `Playwright Test did not expect test() to be called here` (像 @playwright/test 版本错配) + `No tests found`。**这不是围栏破了,是调用 cwd 错了**——务必先 `cd tests` (或 `npx playwright test -c tests/playwright.config.ts`)。`./scripts/test-all.sh` 已是 `cd tests` 后再跑,不踩此坑

### 命令

```bash
./scripts/test-all.sh                               # 一键跑三层 (后端 + Vitest + Playwright)
mvn test                                            # 全部后端测试
mvn test -Dtest=ChatFlowTest                        # 单类
mvn test -Dtest=ChatFlowTest#start_and_send_should_work  # 单方法
mvn test -Dtest='*RepoTest'                         # 仅跑 Infra 轻量集成
cd tests && npx vitest run                          # 前端纯函数单测
cd tests && npx playwright test                     # 前端 E2E (自动启停 Spring Boot)
cd tests && npx playwright test chat.spec.ts        # 单条 E2E
cd tests && npx playwright test --headed --debug    # 带浏览器调试
```
