# Agent Web 领域模型

> 本文按当前源码描述仍在运行的限界上下文与分层边界。最后更新：2026-07-22。

## 限界上下文

| 上下文 | 类型 | 核心职责 | 主要模型 |
| --- | --- | --- | --- |
| chat | 核心域 | 会话、消息、反馈、恢复、回退与分享 | `ChatSession`、`ChatMessage` |
| auth | 支撑域 | 用户名密码认证、数据库会话、角色与用户隔离 | `UserAccount`、`ManualSession` |
| workflow | 支撑域 | 管理台可配置的多步 Agent 工作流 | `Workflow`、`WorkflowExecution` |
| refinery | 核心域 | 会话评分、向量化、召回、归档与观测 | `RagChunk`、`RecallCandidate` |
| git | 支撑域 | 每用户 Git 身份及加密凭据 | `UserGitConfig`、`GitIdentity` |
| suggestion | 支撑域 | 用户建议提交与管理端 triage | `UserSuggestion` |
| schedule | 支撑域 | Cron Agent 任务生命周期 | `ScheduledTask` |
| worktree | 支撑域 | 普通聊天工作区的分支切换和路径约束 | `WorkspacePathPolicy` |
| slashcommand | 支撑域 | 命令发现、解析与展开 | `SlashCommand` |
| issuelog | 支撑域 | issue-log 文件生成、去重、合并与锁 | `IssueLogEntry` |
| metrics | 读模型 | 管理台指标和召回观测 | DTO / Query Service |

`AgentType` 位于 `domain/shared`，供聊天、工作流、定时任务和 CLI 适配共同引用。`AgentGateway` 位于 `adapter`，是应用层驱动本机 CLI 的出站端口。

## 分层边界

```text
interfaces  -> 请求校验、DTO 转换、HTTP/SSE 边界
app         -> 用例编排、事务边界、端口调用
domain      -> 聚合、值对象、状态迁移、不变量、仓储端口
adapter     -> 外部网关端口
infra       -> SQLite、文件系统、CLI 子进程、HTTP 客户端、认证过滤器
```

- Domain 不依赖 Spring、JDBC、ORM、Controller 或应用服务。
- Application 只编排，不遍历聚合内部集合重组业务语义。
- 写侧仓储接口位于 Domain，签名只暴露领域类型；SQLite 实现位于 Infrastructure。
- 纯查询走 Query Service，返回 DTO / 视图，不返回半截聚合。
- Controller 不直接访问 Repository 或 Mapper。

这些边界由 `ArchitectureTest` 持续校验；存量 app → infra 依赖由 `FreezingArchRule` 冻结，禁止新增。

## Chat

`ChatSession` 是聊天聚合根，负责消息追加、反馈、恢复标识和截断等会话语义。`ChatAppServiceImpl` 负责加载会话、组装运行上下文、调用 `AgentGateway`、持久化结果并把输出交给 `StreamChunkHandler`。

写侧使用 `SessionRepository`，活跃对象由 `InMemorySessionRepo` 缓存，SQLite 负责跨重启持久化。会话列表、分享和管理台浏览由 `ChatSessionQueryService` 提供 CQRS 读模型。

普通用户的会话可见性由 `CurrentUserProvider` 和 `ChatProperties.userIsolationEnabled` 共同约束；公开分享只返回只读投影，不能恢复会话或启动子进程。

## Auth

`UserAccount` 保存用户名、BCrypt 哈希、角色和启用状态；`UserAuthenticator` 校验凭据，`UserPasswordService` 负责密码变更规则。`ManualSession` 表示数据库登录会话，随机令牌仅以哈希形式持久化。

`AuthAppService` 只编排账户查询、验密和会话创建/注销。`SessionAuthFilter` 绑定用户上下文；`AdminAuthFilter` 对配置的管理接口前缀追加 `ADMIN` 角色校验。登录失败限流由基础设施组件维护，不进入账户聚合。

公网模式下，`PublicAccessBootstrap` 在 Web Server 接收请求前检查种子密码是否已替换。浏览器安全头由 `SecurityHeadersFilter` 统一设置。

## AgentRun

`app/agentrun` 是应用层执行管线，不是领域聚合。入口通过 `AgentRunContext` 传入原始输入、运行形态、来源、工作目录、环境与召回策略。

`PromptAssemblyService` 固定按以下顺序调用 contributor：

1. 环境约束；
2. workspace 上下文；
3. workspace 知识预召回；
4. 历史 RAG；
5. 用户输入；
6. 输出约束。

单个 contributor 失败时降级为空，不阻断 run。当前用户输入只由 `UserInputContributor` 注入，避免重复。组装结果包含 prompt parts 和 SHA-256 hash，供日志观测。

## Workflow

`Workflow` 是可编辑的多步定义，包含名称、Agent 类型、工作目录和有序步骤。`WorkflowExecution` 与 `WorkflowStepExecution` 记录一次运行及各步骤的输入、输出和终态。

`WorkflowRunner` 顺序执行步骤，并通过 AgentRun 管线组装 prompt；某一步失败后停止后续步骤并把整体运行标记为 `FAILED`。管理台接口只负责编排与 DTO 转换。

## Refinery

Refinery 默认关闭。启用后，`ChatRefineryTrigger` 找到静默会话，`ConversationRefinery` 评分并结构化摘要，Embedding 客户端生成向量，`RagChunkRepository` 持久化 chunk。

`RefineryRecaller` 先用向量相似度硬闸过滤，再融合 trigger signal 与时间衰减重排。归档是软状态迁移，低分丢弃可单独留痕。查询、重建和统计通过管理台接口暴露，并受 `ADMIN` 角色保护。

## Git 与 Worktree

`UserGitConfig` 聚合保存 Git identity 和可选凭据密文。凭据加解密由 `GitCredentialCipher` 完成，明文只在启动 CLI 子进程前短暂进入环境变量，不落日志、不通过接口回显。`GitProcessEnvCustomizer` 把用户配置应用到当前 CLI run。

普通 worktree 能力服务于聊天工作目录切换。`WorkspacePathPolicy` 和 `RealPathWorkspacePolicy` 共同保证路径位于白名单根目录，防止 `..`、符号链接和越界访问。

## Schedule、Suggestion 与 Issue Log

- `ScheduledTask` 承担启停、Cron 配置和运行元数据；应用服务负责调度器注册与会话创建。
- `UserSuggestion` 承担状态与管理端处理语义；提交端与管理端接口分离。
- issue-log 通过文件仓储写入 workspace，并由工作目录级锁避免并发更新索引。精炼、去重和合并可使用同步 CLI 调用，并保留确定性 fallback。

## 存储

SQLite 保存用户、登录会话、聊天会话、消息、反馈、定时任务、工作流、用户建议、RAG chunk、召回观测、Git 配置和运行时设置。上传文件、图片与 issue-log 使用文件系统；活跃聊天会话使用有界内存缓存。

新安装数据库以 `schema.sql` 为准，兼容性列和表由 `SqliteInitializer` 做幂等迁移。
