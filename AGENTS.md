# 角色与原则
- Java 资深开发专家，OO/DDD 大师；封装细节、保持主流程清晰，组合优于继承.
- 思考与检索用英文，回复用中文；简短直接、言之有据，不编造，不确定先核实代码.
- 作者统一格式 `@author zhourui(V33215020)`.

# DDD 四层架构
Interface / Application / Domain / Infrastructure 严格分层；**Application 严禁业务逻辑**（仅做编排+事务），**Domain 不依赖任何外层**.

## 落地判据
- **App 层泄漏判定**（出现即下沉聚合）：
  - 遍历聚合内部集合做条件查找（如 `for msg in session.getMessages()` 找特定 id）.
  - 对聚合 getter 结果做语义判断（`x != null && !x.isEmpty()`、role / 状态比对）.
  - 替聚合做构造期不变量校验（路径 / 必填 / 格式）—— 改聚合工厂 `Aggregate.create(...)`.
- **贫血允许，但不允许"代劳"**：无不变量时聚合可为纯数据载体；一旦出现状态迁移、合法性校验、语义查询，必须收回聚合根，禁止 app 层用 getter 重组规则.
- **聚合根 persistence-ignorant**：聚合根禁止注入/调用 Repository；跨聚合走 ID 引用或参数传入；需跨聚合编排由领域服务承载 Repository 调用.
- **Repository 写/读侧分治**：
  - **写侧**：接口定义在 domain（签名只允许 domain 类型，禁泄漏 ORM / JDBC / Mybatis / Spring Data 注解），infra 实现；app 只依赖 domain 接口.
  - **读侧（CQRS）**：纯 SELECT 允许 app 绕过聚合直达 infra，前提 ①返回 DTO / 视图 / `Map`，禁返回半截聚合 ②接口（`XxxQueryService`）放 domain 或 app，不允许 inject 带 ORM 注解类 ③SQL / Mapper 里禁业务判断.
- **Repository vs QueryService 边界**：domain Repository 仅聚合 lifecycle（3-5 方法）返回聚合根；读模型投影拆到 `XxxQueryService`（放 app 或 domain，**不放 infra**）；混在同一接口必须拆.
- **Repository 位置默认 domain**：①Repository 本是 domain 概念 ②未来 domain service 调用只能用 domain 位置 ③domain 抽独立 artifact 时随之带走.

# 工程实践（Java）
- 样板代码走 Lombok / MapStruct，通用工具走 Guava / Commons / Jackson / Spring Util，禁止重造轮子.
- 手写仅限：聚合根业务方法（不变量 / 状态迁移）、库表达不了的多源转换（走领域服务）、库覆盖不到的工具.

# TDD 开发流程（强制）

## 前置门禁（修改 Java 文件前按顺序）
1. 改动是否涉及 if / switch / 条件分支 / 业务判断？
2. 否（纯委托、生命周期启停、配置 / 文档）→ 直接编码.
3. 是 → 先按「App 层泄漏判定」检查归属层；错位（如本该在聚合的规则放 app）→ 先下沉再回到 4.
4. 归属正确 → 停下，先写/改测试见红 → 最小实现见绿 → 重构保绿.

**违反 = 回退重做。给错位代码补测试 = 把架构错误锁进回归网，必须先纠正分层。**

## 技术约定
- 单测用 Mockito + JUnit 5；禁 Spring 上下文 / 真实外部依赖；Given-When-Then.
- 运行测试 `/fast-test`，项目级约定 `/java-tdd`.

## 按层测试（对齐 DDD 四层）
| 层 | Mock 对象 | 测试重点 |
|---|---|---|
| **Domain**（聚合根 / 领域服务 / VO） | 无 | 不变量、状态迁移、业务规则 |
| **Application** | Domain Repository / Gateway / Domain Service | 编排顺序、事务边界；**禁业务规则** |
| **Infrastructure**（RepositoryImpl / GatewayImpl / QueryServiceImpl） | Mapper / Redis / Client | 缓存、防穿透、协议适配（**业务判断不在此层**） |
| **Interface**（Controller） | Application Service | 参数校验、DTO 转换、HTTP 状态码 |
| Mapper / 纯 SQL / JPA Entity | —— | 不走单元 TDD，走集成测试 |

## 兜底
- 含 if / switch 等条件分支的非纯 CRUD 代码，**无论包名 / 类名一律走 TDD**；豁免：纯委托、固定生命周期、纯配置 / 文档.
- 分支若落在 Application / Infrastructure，先回门禁第 3 步判断归属——业务规则必须先下沉到 Domain 再补测试.

# AGENTS.md

这份文件给 Codex 和其他 CLI Agent 提供仓库内协作约定。内容只保留当前项目真实存在、能从代码或配置验证的信息。

## 项目概况

`agent-web` 是一个 Spring Boot Web 服务，用浏览器驱动本机 CLI Agent。当前支持 Claude CLI、Codex CLI，后端启动子进程并通过 SSE 把输出流式推给前端。服务还包含远程诊断 API、管理后台、飞书 IM 工单、知识精炼和 issue-log 沉淀流程。

主要能力：

- 浏览器聊天界面驱动本机 CLI Agent
- Claude / Codex 输出事件归一化后通过 SSE 流式展示
- 会话持久化、用户隔离、分享链接、反馈、回退、图片上下文
- 受配置根目录约束的文件浏览、上传、下载、删除
- Cron 定时 Agent 任务
- Git worktree 切换、按用户保存 Git 凭据配置
- 远程诊断 API，支持 API Key、幂等键、SSE 续传、超时、取消
- 管理后台：dashboard、会话列表、诊断历史、issue-log 回填
- 飞书 IM 消息建单、诊断卡片回执、复核与人工反馈
- Knowledge Refinery：可选的会话/诊断评分、Ark embedding、向量召回、低分丢弃留痕
- 诊断结果生成 issue-log，并支持历史诊断回填候选

## 构建、启动、测试

```bash
mvn clean package                                      # 构建 JAR
mvn spring-boot:run                                    # 本地启动，application.yml 默认端口 18092
java -jar target/agent-web-0.1.0-SNAPSHOT.jar          # 启动已构建 JAR
./scripts/service.sh build                             # 经 service 脚本构建，自动探测 JDK 21
./scripts/service.sh start|stop|restart|status|logs    # 守护进程控制 (Windows 用 .\scripts\service.ps1)

mvn -q test                                            # 默认后端快速测试集
mvn -q -Dtest=ChatFlowTest test                         # 单个后端测试类
mvn -q -Dtest=ChatFlowTest#methodName test              # 单个后端测试方法
mvn verify                                             # 测试 + JaCoCo report/check
mvn pmd:check                                          # Alibaba P3C PMD，failOnViolation=false

./scripts/test-all.sh                                  # 后端 + Vitest + Playwright
cd tests && npx vitest run                             # 前端纯函数单测
cd tests && npx playwright test                        # 前端 E2E，自动以 e2e profile 启动 18099
cd tests && npx playwright test chat.spec.ts           # 单个 E2E spec
```

代码改完后不要主动执行 `mvn package`、`./scripts/service.sh restart` 或部署命令，除非用户明确要求编译、打包、部署或重启。验证优先选择能覆盖改动的最小测试命令。

### Maven 测试分组

`mvn -q test` 是默认快速测试集，不等价于全量后端测试。`pom.xml` 通过 `test.excludedGroups` 默认排除：

- `live`：依赖真实外部 CLI / 登录态。
- `git-integration`：真实 git / worktree 测试。
- `spring-flow`：`@SpringBootTest` 全链路测试。
- `process-integration`：真实子进程编排测试。

默认集启用了 JUnit 类级并行，配置在 `src/test/resources/junit-platform.properties`：测试类并行、类内方法串行、固定并行度 8。Spring Test Context cache 在 Surefire 里设为 128。`src/test/resources/logback-test.xml` 会关闭测试期应用日志。当前 PowerShell 环境最近验证 `mvn -q test` 约 60-65 秒通过；运行时长会受机器负载和首次编译影响。

在当前 Windows PowerShell 环境直接跑 Maven 前先切到 JDK 21：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q test
```

显式运行默认排除的慢测试时，要覆盖 `test.excludedGroups`，否则 tag 仍会被排除：

```powershell
# SpringBootTest 全链路
mvn -q '-Dtest.excludedGroups=live,git-integration,process-integration' '-Dgroups=spring-flow' test

# 真实子进程编排
mvn -q '-Dtest.excludedGroups=live,git-integration,spring-flow' '-Dgroups=process-integration' test

# 真实 git/worktree 测试
mvn -q '-Dtest.excludedGroups=live,spring-flow,process-integration' '-Dgroups=git-integration' test

# live 测试，要求真实外部 CLI 和登录态
mvn -q '-Dtest.excludedGroups=git-integration,spring-flow,process-integration' '-Dgroups=live' test
```

如果只想跑某个带 tag 的慢测试类，也要同步覆盖排除项，例如：

```powershell
mvn -q '-Dtest.excludedGroups=live,git-integration,spring-flow' '-Dtest=com.example.agentweb.infra.AgentCliGatewayTest' test
```

### 本地 JDK 注意事项

项目是 Spring Boot 3.3.13 + `<java.version>21</java.version>`，编译和测试需要 JDK 21。Linux 部署机默认 shell 指向 JDK 8（`JAVA_HOME=/usr/local/jdk8`），直接 `mvn test` 会在编译期报 class-file / release 版本错误。`scripts/service.sh build/start` 会从 `/usr/local/jdk-21` 自动探测并导出 `JAVA_HOME` / `PATH`；直接跑 Maven 时带上：

```bash
JAVA_HOME=/usr/local/jdk-21 mvn test
```

探测失败时用 `JAVA_BIN=/usr/local/jdk-21/bin/java ./scripts/service.sh build` 覆盖。不要靠调低 `-Djava.version` 绕过——那只会掩盖环境问题，偏离项目构建目标。

### Native Diagnose Profile

进程内 CCLC 诊断引擎是可选能力。其 Spring 配置和适配器在 `src/native-diagnose/`，仅在 Maven profile `native-diagnose` 下编译，该 profile 引入 `com.anthropic:cclc-agent-diagnosis:0.1.0-SNAPSHOT` 依赖并追加额外源码目录。默认构建刻意排除此 profile：SNAPSHOT 制品在本地 / CI 仓库可能解析不到，且 `agent.native-diagnose.enabled` 默认 `false`。只有制品可用、且确实要测试或打包原生诊断时才加 `-Pnative-diagnose`。

## 技术栈

- 后端：Spring Boot 3.3.13、Java 21、Maven
- 数据库：SQLite + Spring JDBC
- 前端：Vue 3 + Element Plus，静态文件/CDN vendor，无生产构建步骤
- 测试：JUnit 5、Mockito、MockMvc、Vitest、Playwright
- 质量检查：Alibaba P3C PMD、`mvn verify` 中的 JaCoCo 覆盖率门禁
- 外部集成：Claude CLI、Codex CLI、飞书开放平台、Volcengine Ark embedding API

## 架构边界

项目按 DDD + 六边形架构分层：

```text
interfaces/   REST Controller、请求/响应 DTO、异常处理
app/          应用服务与流程编排
domain/       聚合根、值对象、策略、仓储端口
adapter/      外部网关端口
infra/        CLI 进程适配、SQLite 仓储、认证过滤器、配置、HTTP 客户端
config/       Spring MVC 和装配配置
```

重要目录：

- `interfaces`：`ChatController`、`FsController`、`AuthController`、`AdminAuthController`、`AdminConversationController`、`MetricsController`、`GitConfigController`、`DiagnoseController`、`DiagnoseHistoryController`、`Refinery*Controller`、`IssueLogBackfillController`
- `app`：聊天/会话编排、流式处理、worktree、定时任务、诊断、IM 工单、refinery、issue-log、metrics、git config、agentrun（prompt 组装管线）
- `domain`：chat/session、auth、git、worktree、slash command、schedule、diagnose、ticket、messaging、refinery、issue-log
- `infra`：`AgentCliGateway`、CLI dialect、SQLite repo、SSO/admin auth、Feishu client、Ark embedding client、issue-log 持久化、上传存储
- `src/main/resources/static`：静态前端页面、JS、CSS、vendor 文件
- `tests`：独立 npm 测试工程，包含 Vitest 与 Playwright

## 主要流程

### Chat

1. `ChatController` 接收普通消息或 SSE 请求。
2. `ChatAppServiceImpl` 处理 `ChatSession`、用户归属、slash command、可选 recall、持久化。
3. `AgentCliGateway` 按 agent 类型路由到对应 `CliDialect`。
4. `ClaudeCliDialect` 或 `CodexCliDialect` 构建命令并启动子进程。
5. `CodexEventNormalizer` 把原生 JSON 事件转成前端统一契约。
6. `StreamChunkHandler` 存储 chunk，并通过 SSE 推给浏览器。

### CLI 模式

- Claude 默认使用 `claude --print --output-format stream-json --verbose --include-partial-messages ...`。
- `agent.cli.codex.args` 为空时，Codex 走真实 `codex exec --json`；填非空模板时回退 legacy 文本输出模式。
- 集成测试必须使用 `TestCliStub` 或 e2e fixtures，不要调用真实本机 Agent。

### AgentRun 与 Prompt 组装

`app/agentrun/` 是应用层执行管线（不是领域聚合），把各入口"组装一次 run 的 prompt"收敛到统一 pipeline。诊断、Workflow、定时任务都经它装配。

- 入口构造 `AgentRunContext`：`originalInput` + 两条正交轴 `runForm`（CHAT / DIAGNOSE / WORKFLOW_STEP / SCHEDULED / CUSTOM）× `sourceDomain`（DIAGNOSE / CHAT / GENERAL）+ `workingDir` / `env` / `RunRecallPolicy`。
- `RunRecallPolicyFactory` 按 `AgentRunProperties`（`agent.run.*`）建策略；`AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` / `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` / `AGENT_RUN_RECALL_TOP_K` 可运行期关停。历史 RAG 仅在 `sourceDomain=DIAGNOSE` 开（再受 `agent.refinery.diagnose.recall-enabled` 内层控制）。`AgentRunContext` 未显式传 policy 时默认 `RunRecallPolicy.disabled()`（fail-safe）。
- `PromptAssemblyService` 按固定序跑 6 个 `PromptContributor`：Env → WorkspaceContext → KnowledgePreRecall → HistoricalRag → UserInput → OutputInstruction，拼 `PromptPart`、算 SHA-256 prompt hash。每个 contributor 失败降级为空，绝不阻断 run。
- 当前用户问题由 `UserInputContributor` 唯一注入；命中诊断历史 RAG（legacy enhanced query 已含 `[当前问题]`）时靠 `ownsUserInput` 互斥跳过，避免重复。
- `WorkspaceContextResolver` 在 `agent.fs.roots` 白名单内发现约定知识索引（`docs/issue-log/INDEX.md` / `known-issues` / `playbooks`）与可选 `.agent-web.yml`（SnakeYAML 解析 `knowledge_indexes` / `guardrails`）。**`agent-web` 不发现 / 不读取 / 不注入本文件（`AGENTS.md`）与 `CLAUDE.md`**——是否加载由具体 CLI 自身决定。
- guardrail 合并：env（`agent.envs[].prompt`）是基线，workspace manifest 只能收紧。当前零 schema 变更：`diagnose_task.query` 存最终 assembled prompt（非仅原始 query）；召回观测仅落日志 + 现有 `recall_used` / `recall_chunk_ids`。

### 飞书 IM 工单

- 飞书长连接事件先落 IM inbox，再由 worker 标准化消费并建立 `Ticket`。
- `TicketAppService` 提交诊断，`TicketDiagnoseSubscriber` 消费终态并更新消息卡片。
- 用户或管理台反馈写入工单，供 issue-log 回填与 refinery tier 调整使用。

### Knowledge Refinery

`agent.refinery.enabled` 默认关闭。开启后：

- `ChatRefineryTrigger` 找静默会话，交给 `ConversationRefinery` 评分。
- 高分结果通过 `ArkEmbeddingClient` 生成 embedding，并由 `SqliteRagChunkRepo` 入库。
- 低分结果可写入 `chat_rag_discarded`，用于阈值校准。
- `RefineryRecaller` 内存扫描 cosine，再按 trigger signal 和时间衰减重排；只有请求开启 recall 时才注入 `[历史参考]`。
- 诊断入库和诊断召回分别由 `agent.refinery.diagnose.*` 控制。

### Issue Log

- 诊断详情可以通过 `IssueLogDraftProducer` 生成可编辑 issue-log 草稿。
- `IssueLogRefinery`、`IssueLogDedupMatcher`、`IssueLogMerger` 使用同步 CLI 调用，并带确定性 fallback。
- `FileSystemIssueLogRepository` 写入 `<workingDir>/docs/issue-log`，由 `WorkingDirIssueLogLockRegistry` 做工作目录级锁。
- 回填只生成候选；归档或写文件必须人工审核通过。

### 存储

- 活跃会话缓存：`InMemorySessionRepo`
- 会话和大部分应用状态持久化：SQLite repo
- JSON 会话备份/导出：`JsonFileSessionRepo`
- 建表脚本：`src/main/resources/schema.sql`

## 配置

主配置在 `src/main/resources/application.yml`。机器相关路径可通过 `agent-paths.yml` 覆盖。

常用环境变量：

| 变量 | 用途 |
| --- | --- |
| `CLAUDE_CLI_CMD` | Claude 可执行文件 |
| `CODEX_CMD` | Codex 可执行文件 |
| `SERVER_ADDRESS` / `SERVER_FORWARD_HEADERS_STRATEGY` | 应用监听地址（同机 Caddy 默认 `127.0.0.1`）/ 可信代理转发头策略 |
| `CODEX_HOME` | Codex 配置/鉴权目录 |
| `OPENAI_API_KEY` | Codex API 鉴权，已有登录态时可不设 |
| `AGENT_DB_PATH` | SQLite 数据库路径 |
| `AGENT_AUTH_SESSION_TTL_SECONDS` | 数据库登录会话有效期（默认 7 天） |
| `AGENT_AUTH_COOKIE_NAME` / `AGENT_AUTH_COOKIE_SECURE` | 登录 Cookie 名 / 是否强制 Secure（公网默认 `__Host-agent_session` / `true`） |
| `AGENT_AUTH_LOGIN_MAX_FAILURES` / `AGENT_AUTH_LOGIN_FAILURE_WINDOW_SECONDS` | 登录失败限流阈值 / 窗口 |
| `AGENT_PUBLIC_ACCESS_ENABLED` / `AGENT_BOOTSTRAP_ADMIN_PASSWORD` | 公网启动门禁 / 首次替换公开种子密码的新密码 |
| `AGENT_WORKTREE_ALLOWED_ROOT` | Worktree 操作允许的工作区根 |
| `GIT_CRED_ENC_KEY` | 用户 Git push 凭据的 AES-256-GCM 主密钥 |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 飞书凭据 |
| `REFINERY_ENABLED` | Knowledge Refinery 总开关 |
| `REFINERY_EMBED_API_KEY` | Ark embedding 凭据，refinery 开启时必填 |
| `REFINERY_EMBED_ENDPOINT` / `REFINERY_EMBED_MODEL` / `REFINERY_EMBED_DIM` | Ark embedding 配置 |
| `REFINERY_DIAGNOSE_INGEST_ENABLED` / `REFINERY_DIAGNOSE_RECALL_ENABLED` | 诊断侧 refinery 灰度开关 |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | AgentRun workspace context 注入开关（默认 true，紧急可关） |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | AgentRun workspace 知识预召回开关（默认 true） |
| `AGENT_RUN_RECALL_TOP_K` | AgentRun 召回 top-K（默认 8） |

`application.yml` 里可能有本地/测试部署用的占位密钥。不要新增硬编码凭据，新增敏感配置走环境变量或 Secret Store；需要文件化保存时遵循下方规则。

### 敏感信息落盘

- 服务端使用的 API Key、Token、Secret、私钥、密码和凭据导出文件等敏感信息，需要文件化保存时必须放在仓库根目录 `data/` 下；当前统一入口为 `data/secrets.properties`，由 Spring Boot 通过 `spring.config.import` 自动读取。禁止把敏感值放入源码、配置模板、文档、日志、测试 Fixture、Artifact 或仓库其他目录。
- `data/` 必须整目录保持 Git 忽略；写入敏感文件前先用 `git check-ignore -q data/<path>` 验证，禁止使用 `git add -f`、Git ignore 例外或其他方式提交其中内容。
- 敏感目录权限应为 `700`，敏感文件权限应为 `600`；代码、脚本、命令输出和回复中不得打印敏感值，只能核对变量名、是否存在或不可逆摘要。
- Codex CLI 与 Claude Code 继续使用本机默认配置和登录态；不要移动、复制、读取或改写用户级 `~/.codex`、`~/.claude` 等认证目录，也不要把 `OPENAI_API_KEY`、`ANTHROPIC_API_KEY` 放入 `data/secrets.properties`，除非用户明确要求改变 CLI 鉴权方式。
- `data/secrets.properties` 当前用于 `GIT_CRED_ENC_KEY`、`REFINERY_EMBED_API_KEY`（兼容 `CHAT_RAG_EMBED_API_KEY`）和一次性的 `AGENT_BOOTSTRAP_ADMIN_PASSWORD` 等服务端配置；同名外部环境变量优先级更高。生产环境优先使用进程环境或 Secret Store。
- `env.local` 仅保留为历史防误提交规则，不再作为新增敏感信息的存放位置。

## 开发约定

- 修改服务行为、测试、启动、诊断、飞书接入、refinery 或前端流程前，先读 `README.md`。
- 新增 helper、service、adapter、validator、parser、repository 或抽象前，先用 `rg` 搜索现有能力。
- 改动保持在用户请求范围内；除非不重构就无法安全完成，否则不做大范围整理。
- 保持分层：Controller 做边界转换和校验，App 编排流程，Domain 承载业务规则，Infra 处理外部系统。
- 新 Java 类必须包含 `@author zhourui(V33215020)` 和 `@since`。
- 禁止通配符导入。
- 保持 Java 8 兼容。
- 除非测试明确标记为 `live`，单测和集成测试不要调用真实 DB/Redis/MQ/HTTP/CLI 外部服务。
- 除任务明确要求外，不要修改 `agent-paths.yml`、`env.local`、`data/` 下的敏感文件或本地 DB，以及生成的测试产物。

## 测试选择

优先选择最低成本且能覆盖风险的层级：

- Domain 单测：不启动 Spring，不 mock domain 对象。
- App 单测：Mockito mock repository/gateway，使用真实 domain 对象。
- Infra 轻量集成：真实 SQLite 或文件系统，配合 `@TempDir`；非必要不启动 Spring 容器。
- Interface 测试：验证 DTO、状态码、过滤器行为时用 `@WebMvcTest` 或聚焦 controller 的测试。
- 完整 `@SpringBootTest`：只用于 SSE 时序/续传、事务代理、scheduler 装配、filter chain 装配、会话持久化这类跨切关注点。

前端约定：

- 生产前端保持在 `src/main/resources/static` 下的静态文件模式。
- 可复用纯 JS 逻辑先抽到 `static/js/lib`，再补 Vitest。
- Playwright 必须在 `tests/` 目录运行，或显式传 `-c tests/playwright.config.ts`；否则可能误加载 Vitest spec。
- Playwright 优先使用 `getByRole`、`getByText` 等语义化 locator，或稳定 `data-test`，不要依赖 Element Plus 内部 class。

## 验证清单

完成代码改动前：

- 跑与影响范围匹配的最小后端或前端测试。
- Java 业务逻辑变更要补能在实现前失败的测试。
- Controller/API 变更要覆盖状态码和参数校验。
- SQLite repo 变更要用真实 SQLite 验证。
- 前端行为变更跑 Vitest 或对应 Playwright spec。
- 不能运行测试时，说明原因和残余风险。
