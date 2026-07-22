# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot web service that provides a browser UI for driving local CLI AI agents (Claude / Codex). Users interact via chat interface; the backend spawns CLI subprocesses and streams responses back via SSE.

## Build & Run Commands

```bash
mvn clean package                # Build JAR
mvn spring-boot:run              # Run locally (默认 http://localhost:18092/，公网经 Caddy HTTPS)
mvn test                         # Run all tests
mvn test -Dtest=ChatFlowTest     # Run single test class
mvn test -Dtest=ChatFlowTest#testMethodName  # Run single test method
mvn pmd:check                    # Code quality check (Alibaba P3C)
```

> **端口 / 路径**：`application.yml` 里 `server.address=127.0.0.1`、`server.port=18092`、`server.servlet.context-path` 为空；公网由同机 Caddy 把 `https://agent.mokatu.shop/` 反代到该 loopback 端口。

> **Claude 行为约束**：代码改动后**不要自动 `mvn package` / 重启服务**。除非用户明确说"编译"/"打包"/"重启"/"部署"，否则停在改完代码 + 跑测试这一步，让用户自己控制何时重启服务。（原 `scripts/service.sh` 已随重构移除，部署脚本由运维侧维护。）

## Tech Stack

- **Backend**: Spring Boot 3.3.13 / Java 21 / Maven (jakarta 命名空间; 运行时需 JDK 21+)
- **Database**: SQLite (users, sessions, scheduled tasks, workflows, user suggestions, RAG vector store, recall traces, per-user git config; 建表见 `resources/schema.sql` + `SqliteInitializer`)
- **Frontend**: Vue 3 + Element Plus via CDN (no build step, static HTML/JS/CSS in `src/main/resources/static/`)；主应用 + `/admin` 管理台 MPA
- **Communication**: REST + Server-Sent Events (SSE)
- **Code Quality**: Alibaba P3C (PMD plugin)
- **External integrations**: Claude CLI, Codex CLI, OpenAI-compatible embedding API

## Architecture

DDD + Hexagonal Architecture with four layers:

```
interfaces/   → REST Controllers + DTOs (API boundary)
                Chat, Fs, Auth, Share, ScheduledTask, Worktree,
                GitConfig, UserSuggestion*, Metrics, RecallMetrics,
                Refinery*, Admin*(Entry/Conversation/Workflow/Settings)
app/          → Application services (orchestration, no domain logic)
                agentrun/ (prompt assembly pipeline), chat, scheduled-task, worktree,
                workflow/, git/, suggestion/, metrics/, refinery/
domain/       → Aggregate roots, value objects, repository interfaces
                chat, workflow, git, suggestion, refinery, issuelog, auth, schedule,
                slashcommand, worktree, shared
infra/        → CLI process execution, SQLite repos, auth filter, config properties
                cli/ (CliDialect strategy), workflow/, git/, suggestion/, metrics/, refinery/, issuelog/,
                setting/, UploadPicStore, ...
adapter/      → Port interfaces (AgentGateway)
```

### Key Data Flow: Chat Message

1. `ChatController` receives message → delegates to `ChatAppServiceImpl`
2. `ChatAppServiceImpl` manages `ChatSession` lifecycle, calls `AgentGateway`
3. `AgentCliGateway` routes to the matching `CliDialect` (Claude / Codex) which assembles the command and spawns the CLI subprocess, reads line-by-line JSON
4. `StreamChunkHandler` processes chunks (Codex events are first normalized via `CodexEventNormalizer` to the unified frontend contract) → SSE events pushed to browser via `SseEmitter`

### Key Data Flow: AgentRun Prompt Assembly (新增)

`app/agentrun/` 是应用层执行管线（**不是领域聚合**），把"组装一次 run 的 prompt"从各入口收敛到统一 pipeline。`WorkflowRunner`、`ScheduledTaskServiceImpl` 等入口经它装配 prompt。

1. 入口构造 `AgentRunContext`：`originalInput` + 两条正交轴 `runForm`（`RunForm`：CHAT / WORKFLOW_STEP / SCHEDULED / CUSTOM，`DIAGNOSE` 为已摘除诊断线的历史枚举，仅留兼容）× `sourceDomain`（`SourceType` CHAT / GENERAL，`DIAGNOSE` 同为存量兼容）+ `workingDir` / `env` / `outputInstruction` + `RunRecallPolicy`
2. `RunRecallPolicyFactory.forRun(runForm, sourceDomain)` 按 `AgentRunProperties`（`agent.run.*`）建策略：`workspace-context-enabled` / `workspace-knowledge-enabled` / `recall-top-k`（env 覆盖 `AGENT_RUN_*`，紧急止血可关）。`AgentRunContext` 未显式传 policy 时默认 `RunRecallPolicy.disabled()`（fail-safe，避免新入口忘配即全开）
3. `PromptAssemblyService` 按固定序跑 6 个 `PromptContributor`：`Env`(10) → `WorkspaceContext`(20) → `KnowledgePreRecall`(30) → `HistoricalRag`(40) → `UserInput`(50) → `OutputInstruction`(60)，拼成 `PromptPart` 列表、算 SHA-256 `promptHash`、返回 `PromptAssemblyResult`
4. **每个 Contributor 失败即降级为空、绝不阻断 run**；业务正文（输出格式、env 文案）由业务域 / 配置注入，平台层只负责编排顺序与降级
5. **当前用户问题由 `UserInputContributor` 唯一持有**，靠 `PromptAssembly.ownsUserInput` 互斥避免与历史 RAG enhanced query 重复注入
6. `WorkspaceContextResolver` 在 `agent.fs.roots` 白名单内由 `workingDir` 向上发现 workspace root + 约定知识索引（`docs/issue-log/INDEX.md` / `known-issues` / `playbooks`）+ 可选 `.agent-web.yml`（SnakeYAML 解析 `knowledge_indexes` / `guardrails`）。**`agent-web` 不发现 / 不读取 / 不注入 `AGENTS.md` / `CLAUDE.md`**，是否加载由具体 CLI 决定
7. guardrail 合并：env（`agent.envs[].prompt`）是基线，workspace manifest **只能收紧不能放松**（append-only），`guardrail_source` 记 `env` / `manifest` / `both`
8. `promptHash` / `RecallContribution` / workspace hits / guardrail source 目前仅落日志；`WorkflowRunner` 装配失败会静默回退原始 prompt（韧性优先，但会掩盖误配）

### Key Data Flow: Workflow Orchestration

管理台定义可复用多步 workflow：`AdminWorkflowController` CRUD `Workflow`（有序 `WorkflowStep`，每步一个 prompt 模板），`POST /{id}/run` 建 `WorkflowExecution` → `app/workflow/` 逐步经 `app/agentrun/` 装配 + `AgentGateway` 异步执行，落 `WorkflowStepExecution`；`RUNNING/SUCCEEDED/FAILED` 状态与逐步结果经 `/api/admin-workflow-executions` 查询。

### Key Data Flow: Knowledge Refinery (会话向量召回)

> 诊断线已摘除，refinery 现仅消费 chat 单上游（`ChatViewBuilder` 恒产 `SourceType.CHAT`）。`SourceType.DIAGNOSE` / cross-source 开关仅为召回存量 diagnose chunk 保留，无活跃生产者。

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

- **L1**: `InMemorySessionRepo` — fast lookup for active sessions (`SessionCache` 端口)
- **L2**: `SqliteSessionRepo` — 唯一 `SessionRepository` 实现，跨重启持久化
- 读侧 CQRS 走 `ChatSessionQueryService`（分页/消息视图/分享视图），与写侧 lifecycle 分治
- 注：`JsonFileSessionRepo` 是**孤儿 bean**（不实现任何接口、生产零注入），非活跃备份层

### Slash Commands

`FileSlashCommandScanner` scans command dirs (default `.claude/commands/`) and skill dirs (default `.claude/skills/`, `.codex/skills/`) for `.md` / `SKILL.md` with YAML frontmatter. Paths are overridable via `agent.slash-command.command-dirs` / `agent.slash-command.skill-dirs`. `SlashCommandExpander` replaces `/cmd args` with template body at send time.

### Image Context

`FsController#uploadImage` accepts pasted / uploaded images. For chat callers (passing `sessionId`), `UploadPicStore` writes them under `<workingDir>/upload_pic/<sessionId>/`; for intake / no-session callers it falls back to the flat `<workingDir>/upload_pic/`. The UI shows up to 4 thumbnail chips above the input (×-removable, front-end only — server files stay) and appends absolute paths into the chat message text on send so the agent can read the file. `ChatAppService#deleteSession` purges the matching `upload_pic/<sessionId>/` directory when the session is removed.

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI executable path |
| `CODEX_CMD` | `codex` | Codex CLI executable path |
| `SERVER_ADDRESS` / `SERVER_FORWARD_HEADERS_STRATEGY` | `127.0.0.1` / `framework` | 同机 Caddy 上游监听地址 / 可信代理转发头策略；18092 不直接暴露公网 |
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
| `REFINERY_ENABLED` | `false` | 知识精炼子域总开关，`false` 时相关 bean 全不注册 |
| `AGENT_AUTH_SESSION_TTL_SECONDS` | `604800` | 数据库用户登录会话有效期（秒） |
| `AGENT_AUTH_COOKIE_NAME` / `AGENT_AUTH_COOKIE_SECURE` | `__Host-agent_session` / `true` | 公网登录 Cookie 名 / 强制 Secure |
| `AGENT_AUTH_LOGIN_MAX_FAILURES` / `AGENT_AUTH_LOGIN_FAILURE_WINDOW_SECONDS` | `5` / `300` | 登录失败限流阈值 / 窗口 |
| `AGENT_PUBLIC_ACCESS_ENABLED` / `AGENT_BOOTSTRAP_ADMIN_PASSWORD` | `true` / _(无)_ | 公网启动门禁 / 首次替换公开种子密码的新密码 |
| `AGENT_WORKTREE_ALLOWED_ROOT` | `/home/service/workspace` | Worktree 操作允许的工作区根 |
| `GIT_CRED_ENC_KEY` | _(none)_ | 用户 SCM 凭证 AES-256-GCM 加密密钥，仅 env、无默认回退；缺失则凭证功能降级为仅注入 git 身份 |

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
| **全栈集成** | `@SpringBootTest` + `TestCliStub` | CLI 用 echo stub；其余真实 | 跨切关注点：`@Transactional` 代理、SSE 流式时序、`@Scheduled` 装配、Bean 装配本身 | 5-15s |

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

参考现有 `Sqlite*RepoTest`：`@TempDir` + 手动 `new SQLiteDataSource()` + `JdbcTemplate`，**不起 Spring 容器**。所有 `Sqlite*Repo` 都应走此模式。

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
| SSE 流式时序（chat 流） | `ChatControllerTest` |
| `@Transactional` AOP 代理 + 回滚行为 | `ChatFlowTest` 等 |
| `@Scheduled` 装配 + SQLite 锁退避 | `ScheduledTaskTest` |
| 会话跨重启持久化 | `ResumeSessionTest` |

新功能**不要**再补 `@SpringBootTest`，除非命中以上跨切关注点。

### CLI 子进程 Stub

集成测试用 `TestCliStub.register(registry)` 把 `agent.cli.codex.exec/args` 指向跨平台 `echo`（Windows `cmd /c echo`、Linux `/bin/echo`），固定前缀 `Echo `。**不要**让集成测试调真实 Claude/Codex CLI。

### 前端测试（CDN-only 工程）

前端 `static/js/app.js` 是 CDN 引入的单文件 Vue 3，**不引入 Vite/Vitest 工程化生产代码**。测试工程独立在 `tests/`，两条路径已落地：

1. **纯函数单测 (Vitest)**：可外提的格式化/解析函数抽到 `static/js/lib/formatters.js` 走 UMD-lite（挂 `window.AgentFormatters` + `module.exports`），在独立 `tests/` npm 工程用 Vitest 跑（`tests/unit/*.spec.ts`）
2. **E2E 主链路 (Playwright)**：`tests/e2e/` 独立 Playwright 工程，自动启停 Spring Boot e2e profile (端口 18099)。覆盖 chat / workflows / 管理台(auth/conversations/dashboard/recall/refinery/suggestions) / fs / share / worktree / scheduled-task / git-settings 等（`tests/e2e/*.spec.ts`）
3. **e2e profile** (`src/main/resources/application-e2e.yml`): 端口 18099、`context-path` 空、`default-type=CODEX`；Claude CLI = `cmd /c echo` (走前端兜底文本渲染), Codex CLI = `tests/e2e/fixtures/codex-json-stub.cmd` (输出固定 NDJSON 走 `CodexEventNormalizer`)；管理接口复用数据库 `ADMIN` 用户会话（测试密码由 `AGENT_E2E_ADMIN_PASSWORD` 注入），关 `agent.issue-log.refine/backfill/dedup`，独立 db `data/agent-web-e2e.db`
4. **选择器约定**：用 Playwright `getByRole` / `getByText` 等语义化 API,**不要用** Element Plus 内部 class (`.el-dialog__body` 等会随版本碎)。新功能加 `data-test="xxx"` 属性是可选优化,目前主链路用语义化选择器已经稳
5. **踩坑·Playwright 必须在 `tests/` 目录里跑**：`playwright.config.ts` 在 `tests/` 下,只有 cwd=`tests/` 时才被自动加载 (它设了 `testDir: ./e2e`、webServer 自启 18099)。若从仓库根或别处跑 (尤其**后台/非交互执行,cwd 可能默认落在仓库根**),config 不生效 → Playwright 退化成扫整个 `tests/` 树,把 Vitest 的 `tests/unit/formatters.spec.ts` 也当 E2E 加载,报一串**假错**:`require is not defined in ES module scope` + `Playwright Test did not expect test() to be called here` (像 @playwright/test 版本错配) + `No tests found`。**这不是围栏破了,是调用 cwd 错了**——务必先 `cd tests` (或 `npx playwright test -c tests/playwright.config.ts`)

### 命令

```bash
mvn test                                            # 全部后端测试
mvn test -Dtest=ChatFlowTest                        # 单类
mvn test -Dtest=ChatFlowTest#start_and_send_should_work  # 单方法
mvn test -Dtest='*RepoTest'                         # 仅跑 Infra 轻量集成
cd tests && npm test                                # 前端纯函数单测 (Vitest)
cd tests && npm run e2e                             # 前端 E2E (自动启停 Spring Boot e2e profile)
cd tests && npx playwright test chat.spec.ts        # 单条 E2E
cd tests && npm run e2e:debug                       # 带浏览器调试
```
