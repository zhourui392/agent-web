# Agent Web

> 基于 Spring Boot 的 Web 服务：浏览器界面驱动本地 Claude / Codex CLI 做对话与问答，并在此之上叠加一条「需求 → 计划 → 实现 → 验证 → 交付」的 Agent 研发流水线

## 核心特性

### 对话与会话

- **多 CLI 后端** — 支持 Claude CLI、Codex CLI（`codex exec --json`），通过 `CliDialect` 策略路由
- **Web 化交互 + 实时流式** — 聊天界面直连 CLI 代理，SSE 流式推送，Codex 事件自动归一化到统一前端契约
- **会话管理** — 多会话隔离，每个会话绑定独立工作目录与 Agent 类型，支持 resume 续接、回退重开
- **会话持久化** — SQLite 落库 + 内存 L1 缓存，服务重启不丢历史
- **会话反馈** — 对结果打分 + 评论，作为知识精炼与召回的质量信号
- **图片 / 文件上下文** — 聊天框粘贴 / 上传图片与文件（落到工作空间 `upload_pic/`），自动拼进消息文本供 Agent 读取
- **文件系统浏览** — 目录浏览、文件上传 / 下载 / 删除（受 `agent.fs.roots` 白名单约束）
- **对话分享 / 续聊** — 生成公开链接免登录查看历史，并可在分享页直接续聊（接续同一 CLI 线程）
- **定时任务** — Cron 表达式驱动的定时 Agent 任务，每次触发产出一条独立会话
- **Git Worktree** — 按分支切换工作空间，支持嵌套工作空间布局、一键更新所有 worktree、分支名校验
- **Slash Commands** — 自动扫描工作目录下 `.claude/commands` `.claude/skills` `.codex/skills` 的自定义命令并展开
- **环境切换** — 多环境（测试 / 生产）上下文配置，生产环境自动注入只读约束
- **AgentRun 统一 Prompt 组装** — 各执行入口经 `app/agentrun/` 应用层管线装配 prompt：环境约束 → workspace 上下文 → 知识预召回 → 历史 RAG → 用户问题 → 输出格式，六段可开关、可降级、可观测（SHA-256 prompt hash）

### 需求交付流水线（默认关闭，`agent.requirement.enabled`）

- **需求看板 + 生命周期状态机** — `INTAKE → PLANNED → APPROVED → IMPLEMENTING → VERIFYING → REVIEW → DELIVERED`（含 `SUSPENDED` 人工接管 / `ARCHIVED` 归档），迁移受状态机约束
- **多通道接入** — 看板直建（`BOARD`）、外部系统 REST（`REST_API`，`X-API-Key` + 幂等键）、GitLab issue 打标签 webhook（`GITLAB_ISSUE`）
- **Agent 分阶段 run** — plan / implement / fix / verify 四类异步 run，配额守护，输出经 SSE 实时流（`run-stream`），推进状态机并记录审计事件
- **需求工作区隔离** — 每个进入实现阶段的需求分配独立工作区（bare mirror + git worktree + 端口租约），闲置 TTL 清理、磁盘水位监控，释放前做脏检查防未提交丢失
- **验证闭环** — verify run 产出 `VerificationRound`（轮次 / 结论 / 失败用例 / 证据），`RoundBreakerPolicy` 熔断防止验证死循环烧钱
- **SCM 交付** — 凭据链解析（个人 Git 配置 → 系统默认 → 拒绝）→ push 需求分支 → 开草稿 MR；GitLab webhook 回流：MR 合并 → 标记交付、流水线失败 / MR 评论 → 生成修复 run 建议（人工确认后执行）、issue 打标 → 建新需求
- **工作流编排** — 定义可复用的多步 workflow（每步一个 prompt 模板），一键触发执行并按步记录结果
- **知识收件箱** — 需求交付后自动收割标题 / 描述 / 计划为待审知识建议，审核通过后写入需求 worktree 的 `docs/issue-log`（随 MR 走查，不自动 git commit）

### 知识精炼与召回（Knowledge Refinery，默认关闭）

- **自动沉淀** — 静默会话自动评分 → embedding 入向量库
- **RAG 召回** — 前端「RAG 召回」开关开时每条消息自动召回历史结论拼进上下文，余弦硬闸 + 融合重排（向量 / triggerSignals Jaccard / 时间衰减三维打分）
- **召回指标分析** — 逐次召回明细、命中 chunk 统计，供阈值校准

### 平台运维

- **管理台**（`/admin`，独立口令鉴权）— 使用概览（漏斗 / 时延 / Top 错误 / 趋势）、对话浏览、需求事件审计、工作流管理、用户建议 triage、RAG 语料维护、运行时设置（Agent 模型）
- **用户建议** — 登录用户从聊天界面提交产品反馈，管理员分流处理
- **每用户 Git 身份** — 各用户配置自己的 git identity 与 SCM 凭据（密码加密存储、不回显），用于交付时归属提交与解析凭据链

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.13 / Java 21 / Maven（jakarta 命名空间） |
| 数据库 | SQLite（会话、定时任务、需求、工作区、验证轮次、MR 记录、工作流、知识建议、用户建议、向量库、召回明细、Git 配置） |
| 前端 | Vue 3 + Element Plus（CDN，无构建步骤）；主应用 + 管理台 MPA |
| 通信 | RESTful API + Server-Sent Events |
| 架构 | DDD + 六边形架构，按限界上下文分包 |
| 代码质量 | Alibaba P3C (PMD) |
| 支持的代理 | Claude CLI、Codex CLI |
| 向量化 | OpenAI 兼容 Embedding（默认 OpenRouter `qwen/qwen3-embedding-8b`，dim 4096） |
| 外部集成 | GitLab（分支 push / 草稿 MR / issue-label 与 pipeline webhook） |

## 快速开始

### 环境要求

- Java 21+
- Maven 3.6+
- Claude CLI / Codex CLI（任选其一，可同时配置）

### 启动

```bash
# 构建
mvn clean package

# 启动（二选一）
mvn spring-boot:run
java -jar target/agent-web-0.1.0-SNAPSHOT.jar

# 访问（端口与上下文路径可在 application.yml 中调整）
http://localhost:18092/qa/
```

应用默认挂载在 `/qa` 子路径（`server.servlet.context-path`），便于共享域名部署；切独立域名把 `context-path` 改回 `/` 即可（后端经 `ContextPrefix` 派生前缀，前端 `base.js` 从自身 `<script src>` 推导，均零硬编码）。

登录页 `/login.html` 使用工号 + 用户名创建本地会话，会话默认保留 7 天；除公开页面和带独立鉴权的外部接口外，所有入口均要求登录。会话可见性由 `agent.chat.user-isolation-enabled` 控制；跨用户管理视野走 `/admin/*` 独立口令鉴权（`agent.admin`），与主路径正交。

### 基本配置

编辑 `src/main/resources/application.yml`：

```yaml
server:
  port: 18092
  servlet:
    context-path: /qa               # 共享域名子路径；独立域名改回 /

agent:
  default-type: CODEX               # 对话默认 CLI「首启种子」（CODEX / CLAUDE），落库后以管理后台为准
  fs:
    roots:
      - "/home/service/workspace"   # 允许浏览的根目录白名单
  cli:                              # 各 CLI 方言的 exec / args / 超时 / 模式（完整见 application.yml）
    claude: { ... }                 # 默认 --output-format stream-json 流式
    codex:  { ... }                 # args 留空 → 真实 codex exec --json；填模板回退 legacy 文本路径
  envs:                             # 多环境上下文，env.prompt 前置注入用户消息；prod 注入只读约束
    - { key: test, label: 测试环境 }
    - { key: prod, label: 生产环境 }
  requirement:                      # 需求交付流水线，默认关；enabled=false 时相关 Controller/Advice 不装配
    enabled: false
  admin:                            # 管理台独立口令鉴权
    enabled: false
    password: ${ADMIN_PASSWORD:...}
  refinery:                         # 知识精炼 / 向量召回，默认关
    enabled: false
```

> CLI 方言参数、需求线（`agent.requirement.*`：workspace / delivery / verify / quota）、知识精炼（`agent.refinery.*`）、issue-log（`agent.issue-log.*`）、外部建需求密钥（`agent.api-keys`）的完整配置与内联注释见 `application.yml`。

知识精炼与需求交付默认关闭。开启知识精炼需经 `REFINERY_EMBED_API_KEY` 注入 embedding 鉴权，且 `agent.refinery.embedding.dimension` 须与模型维度一致（不一致启动 fail-fast）。凭证走环境变量，勿写进 `application.yml`。

### 环境变量

| 变量 | 默认值 | 用途 |
|------|--------|------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI 可执行路径 |
| `CODEX_CMD` | `codex` | Codex CLI 可执行路径 |
| `CODEX_HOME` | `~/.codex` | Codex 配置 / 鉴权目录 |
| `OPENAI_API_KEY` | _(无)_ | Codex CLI 鉴权，已通过 `~/.codex/auth.json` 登录可不设 |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite 数据库路径 |
| `AGENT_CHAT_USER_ISOLATION_ENABLED` | `false`(yml) | 对话可见性；`true`=普通用户仅见自己 + 无主老数据 |
| `ADMIN_AUTH_ENABLED` / `ADMIN_PASSWORD` | `false` / 明文默认值 | 管理台开关与口令，生产务必用环境变量覆盖 |
| `AGENT_REQUIREMENT_ENABLED` | `false` | 需求交付流水线总开关 |
| `AGENT_REQ_WORKSPACE_ROOT` | `data/req-workspaces` | 需求工作区根（mirrors + worktrees） |
| `AGENT_REQ_WORKSPACE_REPO_URL` | _(无)_ | 需求线默认目标仓 |
| `AGENT_REQ_RUN_AGENT` | `CLAUDE` | 需求线 run 使用的 CLI agent |
| `AGENT_GITLAB_BASE_URL` | _(无)_ | 交付目标 GitLab base URL |
| `AGENT_GITLAB_DEFAULT_USERNAME` / `AGENT_GITLAB_DEFAULT_TOKEN` | _(无)_ | 交付默认账号 / token（token 加密存储，严禁落 yml） |
| `AGENT_SCM_WEBHOOK_SECRET` | _(无)_ | SCM webhook 密钥，空 = 拒绝所有 webhook（fail-closed） |
| `REFINERY_ENABLED` | `false` | 知识精炼子域总开关，`false` 时相关 bean 全不注册 |
| `REFINERY_EMBED_API_KEY` | _(无)_ | embedding 鉴权（现 OpenRouter），`REFINERY_ENABLED=true` 时必填，严禁硬编码到 yml |
| `REFINERY_EMBED_ENDPOINT` | `https://openrouter.ai/api/v1` | embedding base URL（OpenAI 兼容协议） |
| `REFINERY_EMBED_MODEL` / `REFINERY_EMBED_DIM` | `qwen/qwen3-embedding-8b` / `4096` | embedding 模型与维度，不一致启动 fail-fast；换模型须重嵌入存量 chunk |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | `true` | AgentRun workspace context 注入总开关，紧急止血可关（`agent.run.*`） |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | `true` | AgentRun workspace 知识预召回开关 |
| `AGENT_RUN_RECALL_TOP_K` | `8` | AgentRun 召回 top-K |

## 使用指南

### 对话

1. 登录后在左侧边栏选择工作目录
2. 点击「开始会话」创建 Agent 会话（可在顶栏切换 Agent 类型、环境、分支）
3. 在输入框输入指令，按 `Enter` 发送（`Ctrl+Enter` 换行）；支持粘贴 / 上传图片
4. 实时查看 Agent 流式响应
5. 历史列表展示对话，可查看 / 继续会话；**删除仅限自己创建的对话**
6. 「分享」按钮生成公开链接，他人免登录查看并可续聊（链接转发即等于授权，请谨慎分享）

### 需求交付（需 `agent.requirement.enabled=true`）

1. 在需求看板 `requirement-board.html` 新建需求，或由外部系统 / GitLab issue 接入
2. 触发 plan run 让 Agent 产出计划 → 审核通过（approve）
3. start-implement 分配隔离工作区，触发 implement / fix run
4. start-verify 触发 verify run，验证轮次不过则回修
5. deliver-draft 推分支并开草稿 MR，webhook 回流合并态自动收尾
6. 管理台 `/admin` 查看需求事件审计、指标与知识收件箱

## API 接口

完整端点以 `interfaces/` 下各 `*Controller` 为准。对外契约最稳的是**外部建需求**接口（API Key 鉴权），其余（`/api/auth`、`/api/chat`、`/api/fs`、`/api/tasks`、`/api/worktree`、`/api/requirements/*`、`/api/user/git-config`、`/api/knowledge-suggestions`、`/api/user-suggestions`、`/api/metrics/*`、`/api/refinery/*`、`/api/admin*`）均为 Web 登录或管理口令鉴权的内部接口，直接看对应 Controller。

### 外部建需求 `/api/requirements/external`（API Key 鉴权）

> 调用前在 `agent.api-keys` 配置密钥，请求头携带 `X-API-Key: <key>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/requirements/external` | 外部系统创建需求（`source=REST_API`，支持 `Idempotency-Key` 头幂等，返回 `{id, duplicated}`） |

### SCM Webhook `/api/scm/webhook`（密钥鉴权）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/scm/webhook` | 接收 GitLab 事件（MR 合并 / pipeline 失败 / MR 评论 / issue 标签），UUID 幂等，始终返回 2xx |

### 需求流水线（内部，Web 登录鉴权，节选）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / POST | `/api/requirements` | 需求看板列表 / 新建 |
| POST | `/api/requirements/{id}/plan-run` \| `implement-run` \| `fix-run` \| `verify-run` | 触发各阶段 Agent run |
| GET | `/api/requirements/{id}/run-stream` | SSE 订阅 run 实时输出 |
| POST | `/api/requirements/{id}/deliver-draft` | push 分支 + 开草稿 MR |
| GET | `/api/requirements/{id}/verification-rounds` \| `merge-requests` \| `events` | 验证轮次 / MR / 审计事件 |

> 流式聊天 `/api/chat/session/{id}/message/stream` 支持 `recall` 参数（默认 `true`）控制是否触发 RAG 历史召回。

## 项目结构

```
src/main/java/com/example/agentweb/
├── interfaces/   REST Controller + DTO（Chat / Fs / Auth / Share / Worktree / ScheduledTask /
│                 Requirement* / AdminWorkflow / GitConfig / KnowledgeInbox / UserSuggestion* /
│                 ScmWebhook / Metrics / RecallMetrics / Refinery* / Admin*）
├── app/          应用编排（无业务逻辑）：agentrun（prompt 组装）、chat、scheduled-task、
│                 requirement、workflow、verification、workspace、delivery、git、knowledge、
│                 suggestion、metrics、refinery
├── domain/       聚合根 / 值对象 / 仓储端口：chat、requirement、workflow、verification、
│                 workspace、delivery、git、knowledge、suggestion、refinery、issuelog、auth、
│                 schedule、slashcommand、worktree、shared
├── adapter/      外部网关端口：AgentGateway、NotificationGateway、SCM / 需求文档 / 工作区 / 验证产物端口
├── infra/        CLI 进程执行（cli/ 方言策略）、SQLite 仓储、auth/（本地会话登录 + /qa 前缀派生）、
│                 requirement/、workflow/、delivery/、git/、knowledge/、suggestion/、metrics/、
│                 workspace/、refinery/、issuelog/、setting/、*Properties
└── config/       Web MVC 配置

src/main/resources/
├── application.yml              主配置（各 agent.* 节点带内联注释）
├── schema.sql                  SQLite 建表脚本
├── *-prompt.md                 requirement plan/implement/fix/verify prompt + refinery 评分 prompt
└── static/                     Vue3 + Element Plus（CDN，无构建）：
                                index / login / share / requirement-board / git-settings + admin/（MPA）

tests/                          前端测试工程：Vitest 纯函数单测 + Playwright E2E（自带 e2e profile）
docs/                           设计文档（domain-model.md / event-storming.md，部分早于本次重构，以源码为准）
```

## 测试

三层测试金字塔：后端 JUnit 5 + Mockito（约 180 个测试类），前端 Vitest 纯函数单测，Playwright E2E 主链路。

```bash
mvn test                            # 全部后端测试
mvn test -Dtest=ChatFlowTest        # 单类
mvn test -Dtest='*RepoTest'         # 仅跑 Infra 轻量集成
mvn pmd:check                       # 代码质量检查（Alibaba P3C）
cd tests && npm test                # 前端纯函数单测（Vitest）
cd tests && npm run e2e             # 前端 E2E（Playwright，自动启停 Spring Boot e2e profile）
```

> Playwright 必须在 `tests/` 目录内运行（`playwright.config.ts` 只在 cwd=`tests/` 时被自动加载），否则会误把 Vitest 单测当 E2E 加载报假错。

关键测试：
- `ChatFlowTest` / `FeedbackFlowTest` — 聊天流程与会话反馈
- `ResumeSessionTest` — 会话恢复
- `ScheduledTaskTest` — 定时任务（`@Scheduled` 装配 + SQLite 锁退避）
- `FsControllerTest` / `UploadPicStoreTest` — 文件系统与图片上传
- `SlashCommandScannerTest` / `SlashCommandExpanderTest` — 自定义命令
- `WorktreeControllerTest` / `BranchNameValidatorTest` — Worktree 与分支名校验
- `cli/Claude|CodexCliDialectTest` / `cli/*EventNormalizer*Test` — CLI 方言与事件归一化
- `infra/AgentCliGatewayTest` / `infra/AgentTypeResolverTest` — 网关与类型兜底
- `domain/requirement/*` / `app/requirement/*` / `infra/requirement/*` — 需求状态机、run 编排、持久化
- `app/workflow/*` / `app/verification/*` / `app/workspace/*` / `app/delivery/*` — 工作流、验证、工作区、SCM 交付
- `domain/refinery/*` / `app/refinery/*` / `infra/refinery/*` — 知识精炼评分、召回重排、embedding、向量库
- `ArchitectureTest` — 分层约束校验

## 安全注意事项

### 当前实现

- 路径验证防止目录穿越（`agent.fs.roots` 白名单；worktree 操作受 `agent.worktree.allowed-roots` 约束）
- 本地会话登录：工号 + 用户名，默认 7 天；非公开入口统一要求登录
- 会话可见性由 `agent.chat.user-isolation-enabled` 控制；无论可见性如何，**删除仅限会话创建者**（删他人会话返回 403）；跨用户管理视野走 `/admin/*` 独立口令鉴权
- 分享链接基于随机 Token，无需认证即可查看并**直接续聊**（任何拿到链接者均可续聊）
- 外部建需求接口用 `X-API-Key` 头鉴权，支持按 key 限速
- SCM webhook 密钥校验，`AGENT_SCM_WEBHOOK_SECRET` 为空时 fail-closed 拒绝所有请求
- 用户 SCM 凭据密码加密存储（`GitCredentialCipher`），响应不回显
- 生产环境通过 `agent.envs[].prompt` 强制注入只读约束
- 知识精炼入库前对结论文本做脱敏正则（`agent.refinery.privacy.redact-patterns`），防 API key / JWT / 用户路径段泄漏到向量库

### 生产环境建议

- 核对 `agent.admin.password`（用 `ADMIN_PASSWORD` 覆盖默认明文）与 `agent.api-keys`，限制 `agent.fs.roots` 为必要目录
- 交付相关凭据（GitLab token、webhook secret）一律走环境变量注入，不硬编码
- 启用 HTTPS，添加访问日志与速率限制
- 评估是否需要 `--dangerously-skip-permissions` / `--dangerously-bypass-approvals-and-sandbox`
