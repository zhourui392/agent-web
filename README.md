# Agent Web

> 基于 Spring Boot 的 Web 服务：通过浏览器驱动本机 Claude / Codex CLI，提供流式对话、工作流、定时任务、文件与 Git 工作区管理。

## 核心特性

### 对话与会话

- **多 CLI 后端** — 支持 Claude CLI、Codex CLI（`codex exec --json`），通过 `CliDialect` 策略路由
- **Web 化交互 + 实时流式** — 聊天界面直连 CLI 代理，SSE 流式推送，Codex 事件自动归一化到统一前端契约
- **可恢复聊天流** — 页面统一通过 `ChatRun` 后台执行，和浏览器连接解耦；支持 `Last-Event-ID` 回放、刷新恢复、断网重连、多标签页订阅和显式停止
- **会话管理** — 多会话隔离，每个会话绑定独立工作目录与 Agent 类型，支持 resume 续接、回退重开
- **会话持久化** — SQLite 落库 + 内存 L1 缓存，服务重启不丢历史
- **会话反馈** — 对结果打分 + 评论，作为知识精炼与召回的质量信号
- **图片 / 文件上下文** — 聊天框粘贴 / 上传图片与文件（落到工作空间 `upload_pic/`），自动拼进消息文本供 Agent 读取
- **文件系统浏览** — 目录浏览、文件上传 / 下载 / 删除（受 `agent.fs.roots` 白名单约束）
- **只读对话分享** — 生成公开链接免登录查看历史；分享页不能续聊、不能启动 Agent，也不暴露工作目录
- **定时任务** — Cron 表达式驱动的定时 Agent 任务，每次触发产出一条独立会话
- **Git Worktree** — 按分支切换工作空间，支持嵌套工作空间布局、一键更新所有 worktree、分支名校验
- **Slash Commands** — 自动扫描工作目录下 `.claude/commands` `.claude/skills` `.codex/skills` 的自定义命令并展开
- **AgentRun 统一 Prompt 组装** — 各执行入口经 `app/agentrun/` 应用层管线装配 prompt：环境约束 → workspace 上下文 → 知识预召回 → 历史 RAG → 用户问题 → 输出格式，六段可开关、可降级、可观测（SHA-256 prompt hash）

### 知识精炼与召回（Knowledge Refinery，默认关闭）

- **自动沉淀** — 静默会话自动评分 → embedding 入向量库
- **RAG 召回** — 前端「RAG 召回」开关开时每条消息自动召回历史结论拼进上下文，余弦硬闸 + 融合重排（向量 / triggerSignals Jaccard / 时间衰减三维打分）
- **召回指标分析** — 逐次召回明细、命中 chunk 统计，供阈值校准

### 平台运维

- **管理台**（`/admin`，数据库 ADMIN 角色鉴权）— 使用概览、对话浏览、用户账号创建、工作流管理、用户建议 triage、RAG 语料维护、运行时设置（Agent 模型）
- **工作流编排** — 定义可复用的多步 workflow（每步一个 prompt 模板），一键触发执行并按步记录结果
- **研发交付 Harness（默认关闭）** — 独立于 Workflow 的四阶段控制平面；M1 已支持 Run/Stage/Attempt、Artifact/Gate/Approval，M2 已支持四阶段 Prompt Pack、可信 Skill Catalog、能力授权求交、不可变 Capability Snapshot 与 ADMIN 预览页；M3 已完成只读 MCP、`RuntimeExecution`、提交后启动/取消、M3.1 Snapshot、单次 CLI 能力覆盖、工作区旁路防护、Codex 版本/PID 预检、Secret 脱敏和 Evidence Store，并通过重新验收，详见 [M3 设计](docs/harness/04-m3-detailed-design.md)、[实现记录](docs/harness/m3/README.md)与[自测报告](docs/harness/m3/test-report.md)
- **用户建议** — 登录用户从聊天界面提交产品反馈，管理员分流处理
- **每用户 Git 身份** — 各用户配置自己的 git identity 与 SCM 凭据（密码加密存储、不回显），用于交付时归属提交与解析凭据链

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.13 / Java 21 / Maven（jakarta 命名空间） |
| 数据库 | SQLite（用户、会话、定时任务、工作流、用户建议、向量库、召回明细、Git 配置） |
| 前端 | Vue 3 + Element Plus（CDN，无构建步骤）；主应用 + 管理台 MPA |
| 通信 | RESTful API + Server-Sent Events |
| 架构 | DDD + 六边形架构，按限界上下文分包 |
| 代码质量 | Alibaba P3C (PMD) |
| 支持的代理 | Claude CLI、Codex CLI |
| 向量化 | OpenAI 兼容 Embedding（默认 OpenRouter `qwen/qwen3-embedding-8b`，dim 4096） |
| 外部集成 | Claude CLI、Codex CLI、OpenAI 兼容 Embedding API |

## 快速开始

### 环境要求

- Java 21+
- Maven 3.6+
- Claude CLI / Codex CLI（任选其一，可同时配置）

### 启动

Linux：

```bash
# 自动查找 JDK 21+，编译成功后在后台启动
./scripts/service.sh start

# 可用子命令：build、stop、restart、status、logs
./scripts/service.sh status
```

Windows PowerShell：

```powershell
# 自动查找 JDK 21+，编译成功后在后台启动
.\scripts\service.ps1 start

# 可用子命令：build、stop、restart、status、logs
.\scripts\service.ps1 status
```

执行 `build`、`start` 或 `restart` 时，脚本第一步会优先使用 `JAVA_BIN` 指定的 Java，再检查 `JAVA_HOME`、`PATH`、系统注册表（Windows）和常见 JDK 安装目录；找到完整的 JDK 21 或更高版本后会设置当前进程的 `JAVA_HOME` / `PATH`，执行 `mvn clean package`，并仅在编译成功后启动 `app/agent-web.jar`。若自动探测失败，可显式指定：

```bash
JAVA_BIN=/usr/local/jdk-21/bin/java ./scripts/service.sh start
```

```powershell
$env:JAVA_BIN='C:\Program Files\Java\jdk-21\bin\java.exe'
.\scripts\service.ps1 start
```

脚本要求 Maven 3.6+ 已加入 `PATH`。JVM 参数可通过 `JAVA_OPTS` 传入，Spring Boot 参数可追加到 `start` / `restart` 后，例如 `./scripts/service.sh start --spring.profiles.active=local`。运行日志位于 `logs/service.log`（Windows 的标准错误另写入 `logs/service-error.log`）。

也可以不使用服务脚本，手工构建和前台启动：

```bash
mvn clean package

# 首次公网启动必须设置一个不同于仓库种子密码的新管理员密码
read -rsp 'New admin password: ' AGENT_BOOTSTRAP_ADMIN_PASSWORD && echo
export AGENT_BOOTSTRAP_ADMIN_PASSWORD

# 启动（二选一；生产环境建议由进程管理器注入环境变量）
mvn spring-boot:run
java -jar target/agent-web-0.1.0-SNAPSHOT.jar

# 经 HTTPS 反向代理访问
https://agent.mokatu.shop/
```

应用默认挂载在域名根路径，访问地址为 `https://agent.mokatu.shop/`。后端仍通过 `ContextPrefix` 派生路径，前端 `base.js` 从自身 `<script src>` 推导，因此测试环境需要路径前缀时仍可单独覆盖。

登录页 `/login.html` 使用数据库用户名 + 密码认证，会话默认保留 7 天；除登录入口、静态资源与只读分享外，所有入口均要求登录。首次初始化 SQLite 时会创建 `ADMIN` 账户 `admin`，公开种子密码为 `Aa135246`。公网模式启动时，如果数据库仍保留种子密码哈希，服务会在监听端口对外提供请求前要求 `AGENT_BOOTSTRAP_ADMIN_PASSWORD`，把新密码以 BCrypt（cost 12）哈希写入数据库并注销该账户已有会话；环境变量不会写入数据库，后续启动也不会覆盖已修改的密码。

管理后台复用同一登录会话，并额外要求账户角色为 `ADMIN`；不存在独立管理口令或绕过开关。`agent.chat.user-isolation-enabled` 默认开启，普通用户只能访问自己的会话。公网部署通过同机 Caddy 提供 HTTPS，公网只开放 443，应用端口 18092 仅监听 loopback；完整步骤见 [公网 HTTPS 部署](docs/public-deployment.md)。

仅做本机 HTTP 开发时，可显式关闭公网门禁和 Secure Cookie，并收回 loopback 监听：

```bash
SERVER_ADDRESS=127.0.0.1 \
AGENT_PUBLIC_ACCESS_ENABLED=false \
AGENT_AUTH_COOKIE_SECURE=false \
AGENT_AUTH_COOKIE_NAME=local_session \
mvn spring-boot:run
```

### 基本配置

机器相关路径编辑 `src/main/resources/agent-paths.yml`。`agent.fs.roots` 同时授权文件接口和 Git
worktree 操作；`agent.fs.upload-roots` 仍只额外授权上传接口：

```yaml
agent:
  fs:
    roots:
      - "${AGENT_WORKSPACE_ROOT:/home/service/workspace}"
    upload-roots:
      - "/home/service/.agent-web"
```

其他服务配置编辑 `src/main/resources/application.yml`：

```yaml
server:
  address: 127.0.0.1               # 同机 Caddy 反代，18092 不直接对公网监听
  port: 18092
  forward-headers-strategy: framework
  servlet:
    context-path: ""                # 独立域名根路径

agent:
  default-type: CODEX               # 对话默认 CLI「首启种子」（CODEX / CLAUDE），落库后以管理后台为准
  cli:                              # 各 CLI 方言的 exec / args / 超时 / 模式（完整见 application.yml）
    claude: { ... }                 # 默认 --output-format stream-json 流式
    codex:  { ... }                 # args 留空 → 真实 codex exec --json；填模板回退 legacy 文本路径
  envs:                             # 多环境上下文，env.prompt 前置注入用户消息；prod 注入只读约束
    - { key: test, label: 测试环境 }
    - { key: prod, label: 生产环境 }
  auth:
    cookie-name: __Host-agent_session
    cookie-secure: true             # 公网入口必须使用 HTTPS
    session-ttl-seconds: 604800     # 用户名密码登录会话默认 7 天
    login-max-failures: 5           # 同一来源或用户名的失败阈值
    login-failure-window-seconds: 300
  public-access:
    enabled: true                   # 种子密码未替换时拒绝公网启动
  admin:                            # 管理接口复用登录会话，并要求 ADMIN 角色
    protected-prefixes:
      - /api/metrics
  refinery:                         # 知识精炼 / 向量召回，默认关
    enabled: false
```

> CLI 方言参数、知识精炼（`agent.refinery.*`）和 issue-log（`agent.issue-log.*`）的完整配置与内联注释见 `application.yml`。

知识精炼默认关闭。开启知识精炼需经 `REFINERY_EMBED_API_KEY` 注入 embedding 鉴权，且 `agent.refinery.embedding.dimension` 须与模型维度一致（不一致启动 fail-fast）。凭证走环境变量或下述 Git 忽略配置，勿写进 `application.yml`。

本机文件化的服务端敏感配置统一放在 Git 忽略的 `data/secrets.properties`，应用启动时会自动读取；外部环境变量优先级更高。Codex CLI 和 Claude Code 不读取该文件中的认证配置，仍使用各自的本机默认登录态。示例只列变量名，不要把真实值写入受 Git 跟踪的文件：

```properties
GIT_CRED_ENC_KEY=<32 字节密钥的 base64>
REFINERY_EMBED_API_KEY=<embedding key>
AGENT_BOOTSTRAP_ADMIN_PASSWORD=<仅首次公网启动使用的新管理员密码>
```

创建后应限制权限：`chmod 600 data/secrets.properties`。`AGENT_BOOTSTRAP_ADMIN_PASSWORD` 只用于首次替换种子密码，启动成功后应从文件删除。生产环境优先使用进程环境或 Secret Store。

### 环境变量

| 变量 | 默认值 | 用途 |
|------|--------|------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI 可执行路径 |
| `CODEX_CMD` | `codex` | Codex CLI 可执行路径 |
| `SERVER_ADDRESS` | `127.0.0.1` | 应用监听地址；同机 Caddy 通过 loopback 访问 18092 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | 识别代理传入的 HTTPS / 客户端地址；仅在代理受信且后端端口隔离时使用 |
| `CODEX_HOME` | `~/.codex` | Codex 配置 / 鉴权目录 |
| `OPENAI_API_KEY` | _(无)_ | Codex CLI 鉴权，已通过 `~/.codex/auth.json` 登录可不设 |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite 数据库路径 |
| `AGENT_CHAT_USER_ISOLATION_ENABLED` | `true` | 对话可见性；`true`=普通用户仅见自己 + 无主老数据 |
| `AGENT_AUTH_SESSION_TTL_SECONDS` | `604800` | 数据库登录会话有效期（秒） |
| `AGENT_AUTH_COOKIE_NAME` | `__Host-agent_session` | 登录会话 Cookie 名；`__Host-` 前缀要求 Secure、Path=/ 且无 Domain |
| `AGENT_AUTH_COOKIE_SECURE` | `true` | 强制会话 Cookie 使用 Secure；本机纯 HTTP 测试才可关闭 |
| `AGENT_AUTH_LOGIN_MAX_FAILURES` | `5` | 登录失败限流阈值（同时按直连来源和用户名） |
| `AGENT_AUTH_LOGIN_FAILURE_WINDOW_SECONDS` | `300` | 登录失败限流窗口（秒） |
| `AGENT_PUBLIC_ACCESS_ENABLED` | `true` | 启用公网启动安全门禁；本机 loopback 开发才可关闭 |
| `AGENT_BOOTSTRAP_ADMIN_PASSWORD` | _(无)_ | 仅在数据库仍是公开种子哈希时使用一次的新管理员密码；至少 12、至多 256 字符 |
| `AGENT_WORKSPACE_ROOT` | `/home/service/workspace` | 文件接口与 Worktree 共用的主工作区根；写入 `agent-paths.yml` 的首个 `agent.fs.roots` |
| `AGENT_WORKTREE_ALLOWED_ROOT` | `/home/service/workspace` | 旧部署兼容变量，仅在未设置 `AGENT_WORKSPACE_ROOT` 时作为共享主工作区根回退 |
| `AGENT_E2E_ADMIN_PASSWORD` | _(无)_ | Playwright 登录测试账户的密码；只用于测试进程，不写入仓库 |
| `GIT_CRED_ENC_KEY` | _(无)_ | 用户 Git push 凭据的 AES-256-GCM 主密钥；支持环境变量或 `data/secrets.properties` |
| `REFINERY_ENABLED` | `false` | 知识精炼子域总开关，`false` 时相关 bean 全不注册 |
| `REFINERY_EMBED_API_KEY` | _(无)_ | embedding 鉴权（现 OpenRouter），`REFINERY_ENABLED=true` 时必填，严禁硬编码到 yml |
| `REFINERY_EMBED_ENDPOINT` | `https://openrouter.ai/api/v1` | embedding base URL（OpenAI 兼容协议） |
| `REFINERY_EMBED_MODEL` / `REFINERY_EMBED_DIM` | `qwen/qwen3-embedding-8b` / `4096` | embedding 模型与维度，不一致启动 fail-fast；换模型须重嵌入存量 chunk |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | `true` | AgentRun workspace context 注入总开关，紧急止血可关（`agent.run.*`） |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | `true` | AgentRun workspace 知识预召回开关 |
| `AGENT_RUN_RECALL_TOP_K` | `8` | AgentRun 召回 top-K |
| `AGENT_HARNESS_ENABLED` | `false` | Harness 管理 API、Repository、Catalog、Artifact Store 总开关；默认关闭 |
| `AGENT_HARNESS_ARTIFACT_ROOT` | `data/harness/artifacts` | Harness Artifact 正文受控根目录 |
| `AGENT_HARNESS_PROMPT_PACK_ROOT` | `src/main/resources/harness/prompt-packs` | 四阶段 Prompt Pack 热读取根；打包部署应覆盖为管理员维护的外置目录 |
| `AGENT_HARNESS_PLATFORM_SKILL_ROOT` | `src/main/resources/harness/skills` | 平台可信 Skill 热读取根 |
| `AGENT_HARNESS_APPROVED_USER_SKILL_ROOT` | _(无)_ | 管理员批准的用户 Skill 根；目录来源固定为 `APPROVED_USER`，Manifest 不可伪造来源 |
| `AGENT_HARNESS_WORKSPACE_SKILL_ROOT` | _(无)_ | Workspace Skill 根；每个 Skill 仍需在 Snapshot 请求中做 Run 级显式批准 |
| `AGENT_HARNESS_MCP_SERVER_ROOT` | `src/main/resources/harness/mcp-servers` | 管理员可信 MCP Server Catalog 根；只允许 Snapshot 选中的 Server 进入隔离配置 |
| `AGENT_HARNESS_CODEX_COMMAND` | `CODEX_CMD`，未配置时为 `codex` | Harness 专用 Codex Runtime 命令；与普通聊天命令配置分离 |
| `AGENT_HARNESS_RUNTIME_TEMP_ROOT` | `data/harness/runtime` | Harness 单次执行隔离 `HOME/CODEX_HOME/XDG_CONFIG_HOME` 的临时根，终态后清理 |
| `AGENT_HARNESS_ALLOWED_MCP_SERVER_IDS` | _(无)_ | 当前环境允许挂载的 MCP Server ID 集合；空集合 fail-closed |
| `CODEX_STREAM_IDLE_TIMEOUT_SECONDS` / `CLAUDE_STREAM_IDLE_TIMEOUT_SECONDS` | `900` | 普通流式聊天无 stdout 活动的终止期限；收到活动会续期，`0` 表示禁用 |
| `CODEX_STREAM_MAX_RUNTIME_SECONDS` / `CLAUDE_STREAM_MAX_RUNTIME_SECONDS` | `7200` | 普通流式聊天绝对运行上限；stdout 活动不会续期，`0` 表示禁用 |

## 使用指南

### 对话

1. 登录后在左侧边栏选择工作目录
2. 点击「开始会话」创建 Agent 会话（可在顶栏切换 Agent 类型和分支）
3. 在输入框输入指令，按 `Enter` 发送（`Ctrl+Enter` 换行）；支持粘贴 / 上传图片
4. 实时查看 Agent 流式响应
5. 历史列表展示对话，可查看 / 继续会话；**删除仅限自己创建的对话**
6. 「分享」按钮生成只读公开链接；链接持有者可查看历史与消息中明确引用的图片，但不能续聊或启动 Agent

## API 接口

完整端点以 `interfaces/` 下各 `*Controller` 为准。`/api/auth/login`、`/api/auth/status`、只读分享和静态资源为公开入口；聊天、文件、定时任务、普通 worktree、用户 Git 配置与用户建议等接口要求数据库用户会话；`/api/metrics/*`、`/api/refinery/*` 和 `/api/admin*` 等管理能力还会额外校验 `ADMIN` 角色。

> 页面聊天统一先 `POST /api/chat/session/{id}/runs`（携带 `Idempotency-Key`）提交，再通过 `GET /api/chat/runs/{runId}/events` 订阅；断线后客户端指数退避重连，并使用 `Last-Event-ID` 回放未确认事件。旧 POST SSE、session status/stop 入口已移除。

## 项目结构

```
src/main/java/com/example/agentweb/
├── interfaces/   REST Controller + DTO（Chat / Fs / Auth / Share / Worktree / ScheduledTask /
│                 AdminWorkflow / GitConfig / UserSuggestion* / Metrics / RecallMetrics / Refinery* / Admin*）
├── app/          应用编排（无业务逻辑）：agentrun（prompt 组装）、chat、scheduled-task、
│                 workflow、git、suggestion、metrics、refinery、harness；面向外部能力的端口位于
│                 agentrun/port、auth/port、logging 等对应子域
├── domain/       聚合根 / 值对象 / 仓储端口：chat、workflow、git、suggestion、refinery、issuelog、auth、
│                 schedule、slashcommand、worktree、harness、shared
├── infra/        CLI 进程执行（cli/ 方言策略）、SQLite 仓储、auth/（本地会话登录 + context path 派生）、
│                 workflow/、git/、schedule/、log/、suggestion/、metrics/、refinery/、issuelog/、harness/、setting/
└── config/       Web MVC / Spring 装配、运行配置 Properties（含 refinery/、harness/）

src/main/resources/
├── application.yml              主配置（各 agent.* 节点带内联注释）
├── schema.sql                  SQLite 建表脚本
├── *-prompt.md                 refinery 评分与 issue-log prompt
└── static/                     Vue3 + Element Plus（CDN，无构建）：
                                index / login / share / git-settings + admin/（MPA）

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
- `app/workflow/*` / `domain/workflow/*` / `infra/workflow/*` — 多步工作流编排与持久化
- `domain/refinery/*` / `app/refinery/*` / `infra/refinery/*` — 知识精炼评分、召回重排、embedding、向量库
- `ArchitectureTest` — 分层约束校验

## 安全注意事项

### 当前实现

- 路径验证防止目录穿越；文件接口和 worktree 操作统一受 `agent.fs.roots` 约束
- 数据库用户名密码登录：BCrypt 哈希、256-bit 随机会话、`HttpOnly` / `SameSite=Strict` Cookie，默认 7 天；登录失败按来源和用户名限流
- 公网模式在 Web Server 开始接收请求前检查 `admin` 种子哈希，未提供新密码或继续使用公开种子密码时拒绝启动；公网 Cookie 默认 `Secure` + `__Host-` 前缀
- 会话可见性默认按用户隔离；**删除仅限会话创建者**（删他人会话返回 403）；管理接口在普通会话认证后再校验 `ADMIN` 角色
- 分享链接基于随机 Token，只读查看；公开分享不能续聊、不能启动 Agent、不能使用 owner 的 Git 身份或凭据
- 用户路径经过真实路径白名单校验，拒绝符号链接逃逸；上传限制文件名、扩展名和大小，并禁止覆盖已有文件
- Markdown 经 DOMPurify 白名单净化；统一发送 CSP、`nosniff`、拒绝 framing 等浏览器安全头
- Agent 子进程使用最小环境变量集合、超时和输出上限；Codex 默认不绕过 sandbox，Claude 不使用 `--dangerously-skip-permissions`
- 用户 SCM 凭据密码加密存储（`GitCredentialCipher`），响应不回显
- 生产环境通过 `agent.envs[].prompt` 强制注入只读约束
- 知识精炼入库前对结论文本做脱敏正则（`agent.refinery.privacy.redact-patterns`），防 API key / JWT / 用户路径段泄漏到向量库

### 生产环境建议

- 公网入口只开放 Caddy 的 HTTPS 443；Spring Boot 默认仅监听 `127.0.0.1:18092`，禁止覆盖成公网可达地址
- 首次公网启动用 Secret、进程环境或权限为 600 的 `data/secrets.properties` 临时注入 `AGENT_BOOTSTRAP_ADMIN_PASSWORD`；成功写入 BCrypt 哈希后删除该明文配置
- 把 `agent.fs.roots` 限制到确实允许浏览、修改和执行 worktree 操作的必要工作目录
- 用户 Git 凭据使用 `GIT_CRED_ENC_KEY` 加密；密钥通过受控环境或 Git 忽略配置注入，不写入受版本控制的文件
- TLS 在反向代理终止时保持 `SERVER_FORWARD_HEADERS_STRATEGY=framework`，并设置 `X-Forwarded-Proto=https`；部署样例和检查清单见 [公网 HTTPS 部署](docs/public-deployment.md)
- CSP 为兼容当前无前端构建步骤的 Vue 运行时模板编译，暂时包含 `unsafe-inline` 与 `unsafe-eval`；后续改为预编译模板和外部脚本后应移除这两个兼容项
- 不要开启 `CODEX_SANDBOX_BYPASS`，除非已经评估工作目录、CLI 登录态和子进程权限边界
