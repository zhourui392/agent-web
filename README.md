# Agent Web

> 基于 Spring Boot 的 Web 服务，通过浏览器界面驱动本地 Claude / Codex CLI，并对外提供远程诊断 API

## 核心特性

- **多 CLI 后端** — 支持 Claude CLI、Codex CLI（`codex exec --json`），通过 `CliDialect` 策略路由
- **Web 化交互** — 聊天界面与 CLI 代理通信，无需终端命令
- **实时流式输出** — 基于 SSE 的响应流式传输，Codex 事件自动归一化到统一前端契约
- **会话管理** — 多会话隔离，每个会话绑定独立工作目录，支持上下文恢复
- **会话持久化** — SQLite + JSON 双存储，服务重启不丢失
- **会话反馈** — 对会话结果打分 + 评论，作为知识精炼与经验回填的质量信号
- **图片上下文** — 聊天框支持上传图片（落到工作空间 `upload_pic/`），自动拼进消息文本供 Agent 读取
- **文件系统浏览** — 目录浏览、文件上传 / 下载 / 删除
- **对话分享 / 续聊** — 生成公开链接，他人免登录查看历史对话，并可在分享页直接续聊（接续同一 CLI 线程）；非安全上下文下回退手动复制
- **定时任务** — Cron 表达式驱动的定时 Agent 任务调度
- **Git Worktree** — 按分支切换工作空间，支持嵌套工作空间布局、一键更新所有 worktree、分支名校验
- **Slash Commands** — 自动扫描工作目录下 `.claude/commands` `.claude/skills` `.codex/skills` 等路径的自定义命令并展开
- **环境切换** — 多环境（测试 / 生产）上下文配置，生产环境自动注入只读约束
- **AgentRun 统一 Prompt 组装** — 诊断 / Workflow / 定时任务经 `app/agentrun/` 应用层管线装配 prompt：环境约束 → workspace 上下文 → 知识预召回 → 历史 RAG → 用户问题 → 输出格式 六段可开关、可降级、可观测（SHA-256 prompt hash）；workspace 知识索引由 `workingDir` 约定发现（`docs/issue-log/INDEX.md` 等），运行期开关在 `agent.run.*`
- **远程诊断 API** — 对外暴露 `/api/diagnose` (API Key 鉴权 + SSE)，支持异步提交、断线续传、幂等键、超时取消
- **诊断历史页** — 列表 / 详情 / 工具调用展示，并支持将一次诊断结果转换为聊天会话继续追问
- **知识精炼与向量召回（Knowledge Refinery）** — 静默会话 / 终态诊断自动评分 → embedding 入向量库；前端"RAG 召回"开关开时每条消息自动召回历史结论拼进上下文（余弦硬闸 + 融合重排，默认关闭子域）
- **Issue-Log 沉淀** — 诊断结论一键结构化落盘为 `docs/issue-log/issue/I-xxx-*.md`，LLM 精炼七字段 + 启发式兜底
- **Issue-Log 回填** — 定时把历史诊断任务批量转为 issue-log 候选，仅产候选，归档必经人工审核
- **飞书 IM 工单接入** — 群聊 @机器人自动建单并提交诊断，以消息卡片回执结论，支持复核、反馈、补充上下文和失败重试

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.13 / Java 21 / Maven（jakarta 命名空间） |
| 数据库 | SQLite（会话、定时任务、诊断任务、向量库、issue-log 候选、飞书已处理记录） |
| 前端 | Vue 3 + Element Plus（CDN，无构建步骤） |
| 通信 | RESTful API + Server-Sent Events |
| 架构 | DDD + 六边形架构 |
| 代码质量 | Alibaba P3C (PMD) |
| 支持的代理 | Claude CLI、Codex CLI |
| 向量化 | OpenAI 兼容 Embedding（默认 OpenRouter `qwen/qwen3-embedding-8b`，dim 4096） |
| 外部集成 | 飞书开放平台（IM / 消息卡片 / 通讯录） |

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

# 访问（端口可在 application.yml 中调整）
http://localhost:17988
```

登录页 `/login.html` 使用工号 + 用户名创建本地会话，会话默认保留 7 天；登录信息仅在浏览器本地回显，便于下次进入自动预填。除公开页面和带独立鉴权的外部接口外，所有入口均要求登录。会话数据按用户隔离；跨用户视野走 `/admin/*` 独立口令鉴权（`agent.admin`），与主路径正交。

> 支持通过 `server.servlet.context-path=/qa` 挂载到共享域名的 `/qa/` 子路径。

### 基本配置

编辑 `src/main/resources/application.yml`：

```yaml
server:
  port: 17988

agent:
  default-type: CLAUDE              # UI 不传时兜底（CLAUDE / CODEX）
  fs:
    roots:
      - "/home/user/projects"       # 允许访问的根目录白名单
  cli:                              # 各 CLI 方言的 exec / args / 超时 / 模式（完整见 application.yml）
    claude: { ... }                 # 默认 --output-format stream-json 流式
    codex:  { ... }                 # args 留空 → 真实 codex exec --json；填模板回退 legacy 文本路径
  envs:                             # 多环境上下文，env.prompt 前置注入用户消息；prod 注入只读约束
    - { key: test, label: 测试环境 }
    - { key: prod, label: 生产环境 }
  run:                              # AgentRun workspace 上下文 / 召回开关（env 覆盖 AGENT_RUN_*）
    workspace-context-enabled: true
    workspace-knowledge-enabled: true
```

> CLI 方言参数、诊断 API（`agent.diagnose.*`）、飞书 IM 工单（`agent.im.*` / `agent.ticket.*`）、知识精炼（`agent.refinery.*`）、issue-log（`agent.issue-log.*`）的完整配置见 `application.yml` 内联注释；设计背景见 [`docs/domain-model.md`](docs/domain-model.md)。

知识精炼默认关闭（`agent.refinery.enabled`）。开启需经 `REFINERY_EMBED_API_KEY` 注入 embedding 鉴权，且 `agent.refinery.embedding.dimension` 须与模型维度一致（不一致启动 fail-fast）。凭证放项目根 `env.local`（`.gitignore`，`scripts/service.sh start` 自动 `source`，`mvn spring-boot:run` 需手动 `source`），勿写进 `application.yml`。

### 环境变量

| 变量 | 默认值 | 用途 |
|------|--------|------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI 可执行路径 |
| `CODEX_CMD` | `codex` | Codex CLI 可执行路径 |
| `CODEX_HOME` | `~/.codex` | Codex 配置 / 鉴权目录 |
| `OPENAI_API_KEY` | _(无)_ | Codex CLI 鉴权，已通过 `~/.codex/auth.json` 登录可不设 |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite 数据库路径 |
| `REFINERY_ENABLED` | `false` | 知识精炼子域总开关，`false` 时相关 bean 全不注册 |
| `REFINERY_EMBED_API_KEY` | _(无)_ | embedding 鉴权（现 OpenRouter），`REFINERY_ENABLED=true` 时必填，严禁硬编码到 yml |
| `REFINERY_EMBED_ENDPOINT` | `https://openrouter.ai/api/v1` | embedding base URL（OpenAI 兼容协议） |
| `REFINERY_EMBED_MODEL` | `qwen/qwen3-embedding-8b` | embedding 模型，维度须与 `embedding.dimension` 一致 |
| `REFINERY_EMBED_DIM` | `4096` | embedding 维度，与模型不一致启动 fail-fast；换模型须重嵌入存量 chunk |
| `REFINERY_REFINE_MODEL` | `claude-haiku-4-5-20251001[1m]` | 评分专用廉价模型，置空回退会话 CLI 默认模型 |
| `REFINERY_DIAGNOSE_INGEST_ENABLED` | `false` | 终态诊断自动评分入库灰度开关 |
| `REFINERY_DIAGNOSE_RECALL_ENABLED` | `false` | 诊断启动前注入相似历史诊断灰度开关 |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | `true` | AgentRun workspace context 注入总开关，紧急止血可关（`agent.run.*`） |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | `true` | AgentRun workspace 知识预召回开关 |
| `AGENT_RUN_RECALL_TOP_K` | `8` | AgentRun 召回 top-K |
| `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 占位值 | 飞书 IM 接入凭据 |

## 使用指南

1. 登录后在左侧边栏选择工作目录
2. 点击"开始会话"创建 Agent 会话（可在顶栏切换 Agent 类型、环境、分支）
3. 在输入框输入指令，按 `Enter` 发送（`Ctrl+Enter` 换行）；支持粘贴 / 上传图片
4. 实时查看 Agent 流式响应
5. 历史列表展示所有用户的对话，可查看 / 继续他人会话；**删除仅限自己创建的对话**
6. 可通过"分享"按钮生成公开链接，他人免登录即可查看，并可在分享页直接续聊（链接转发即等于授权续聊，请谨慎分享）
7. 顶栏"使用说明"按钮可查看快速开始与快捷键

## API 接口

完整端点以 `interfaces/` 下各 `*Controller` 为准。这里只列对外契约最稳的远程诊断 API；其余（`/api/auth`、`/api/chat`、`/api/fs`、`/api/tasks`、`/api/worktree`、`/api/diagnose-history`、`/api/refinery`、`/api/issue-log-backfill`）均为 Web 登录鉴权的内部接口，直接看对应 Controller。

### 远程诊断 `/api/diagnose`（API Key 鉴权）

> 调用前在 `agent.diagnose.api-keys` 配置密钥，请求头携带 `X-API-Key: <key>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/diagnose` | 提交诊断任务（支持 `Idempotency-Key` 头幂等） |
| GET | `/api/diagnose/{taskId}` | 查询任务详情 |
| GET | `/api/diagnose/{taskId}/stream` | SSE 订阅，支持 `Last-Event-ID` 断线续传 |
| DELETE | `/api/diagnose/{taskId}` | 取消任务 |

配套 Python 客户端：`clients/diagnose-remote/scripts/diagnose.py`（异步提交 + SSE 订阅 + 重连 + 超时取消）。

> 流式聊天 `/api/chat/session/{id}/message/stream` 支持 `recall` 参数（默认 `true`）控制是否触发 RAG 历史召回。

## 项目结构

```
src/main/java/com/example/agentweb/
├── interfaces/   REST Controller + DTO（Chat / Fs / Auth / Share / ScheduledTask / Worktree / Diagnose / DiagnoseHistory / Refinery* / IssueLogBackfill）
├── app/          应用编排（无业务逻辑）：agentrun（prompt 组装）、chat、scheduled-task、worktree、diagnose、ticket、refinery、issuelog
├── domain/       聚合根 / 值对象 / 仓储端口：chat、diagnose、ticket、messaging、refinery、issuelog、slash-command、scheduled-task、branch-validator
├── adapter/      外部网关端口：AgentGateway、消息、通知与外部交付端口
├── infra/        CLI 进程执行（cli/ 方言策略）、SQLite 仓储、auth/（本地会话登录 + /qa 前缀派生）、diagnose/、messaging/、refinery/、issuelog/、*Properties
└── config/       Web MVC 配置

src/main/resources/
├── application.yml              主配置（各 agent.* 节点带内联注释）
├── *-prompt.md                  refinery 评分 / issue-log 精炼·查重·合并 prompt
└── static/                      Vue3 + Element Plus（CDN，无构建）：index / login / share / admin（MPA）/ js / css / vendor

clients/diagnose-remote/         远程诊断 Python 客户端（SKILL.md + scripts/diagnose.py）
tests/                           前端测试工程：Vitest 纯函数单测 + Playwright E2E
docs/                            设计文档（见 docs/domain-model.md）
```

## 测试

三层测试金字塔：后端 JUnit 5 + Mockito，前端 Vitest 纯函数单测，Playwright E2E 主链路。

```bash
./scripts/test-all.sh               # 一键跑三层（后端 + Vitest + Playwright）
mvn test                            # 全部后端测试
mvn test -Dtest='*RepoTest'         # 仅跑 Infra 轻量集成
mvn pmd:check                       # 代码质量检查（Alibaba P3C）
cd tests && npx vitest run          # 前端纯函数单测
cd tests && npx playwright test     # 前端 E2E（自动启停 Spring Boot e2e profile）
```

关键测试：
- `ChatFlowTest` / `FeedbackFlowTest` — 聊天流程与会话反馈
- `DiagnoseFlowTest` — 诊断 API 全链路（SSE + `Last-Event-ID` 续传）
- `FsControllerTest` / `UploadPicStoreTest` — 文件系统与图片上传
- `ResumeSessionTest` — 会话恢复测试
- `ScheduledTaskTest` — 定时任务测试
- `SlashCommandScannerTest` / `SlashCommandExpanderTest` — 自定义命令测试
- `WorktreeControllerTest` / `WorktreeServiceTest` / `BranchNameValidatorTest` — Worktree 测试
- `cli/Claude|CodexCliDialectTest` / `cli/*EventNormalizer*Test` — CLI 方言与事件归一化
- `infra/AgentCliGatewayTest` / `infra/AgentTypeResolverTest` — 网关与类型兜底
- `infra/diagnose/ApiKeyAuthFilterTest` — API Key 鉴权
- `infra/messaging/*` — 飞书 IM、消息卡片、入站事件与工单持久化
- `domain/refinery/*` / `app/refinery/*` / `infra/refinery/*` — 知识精炼评分、召回重排、embedding 客户端、向量库 SQLite
- `domain/issuelog/*` / `app/issuelog/*` / `infra/issuelog/*` — issue-log 草稿、回填、合并、文件仓储
- `app/diagnose/*` / `app/ticket/*` — 应用服务单测

## 安全注意事项

### 当前实现

- 路径验证防止目录穿越
- 本地会话登录：工号 + 用户名，默认 7 天；非公开入口统一要求登录
- 会话可见性由 `agent.chat.user-isolation-enabled` 控制（环境变量 `AGENT_CHAT_USER_ISOLATION_ENABLED`）：当前为**全员互见**（`false`）；置 `true` 时普通用户仅见自己 + 无主历史。无论可见性如何，**删除仅限会话创建者**（删他人会话返回 403），无主老数据任意用户可删；跨用户管理视野走 `/admin/*` 独立口令鉴权
- 分享链接基于随机 Token，无需认证即可查看，并可在分享页**直接续聊**（任何拿到链接者均可续聊，链接转发即等于授权）
- 远程诊断 API 使用 X-API-Key 头鉴权，支持按 key 限速（`rate-limit`）
- 生产环境通过 `agent.envs[].prompt` 强制注入只读约束
- 知识精炼入库前对结论文本做脱敏正则（`agent.refinery.privacy.redact-patterns`），防 API key / JWT / 用户路径段泄漏到向量库

### 生产环境建议

- 核对 `agent.admin.password` 与 `agent.diagnose.api-keys`，限制 `agent.fs.roots` 为必要目录
- 启用 HTTPS
- 评估是否需要 `--dangerously-skip-permissions` / `--dangerously-bypass-approvals-and-sandbox`
- 添加访问日志与速率限制
- 飞书 IM 接入务必通过环境变量注入 `FEISHU_APP_ID` / `FEISHU_APP_SECRET`，知识精炼通过 `REFINERY_EMBED_API_KEY` 注入 embedding 鉴权，均不要硬编码
