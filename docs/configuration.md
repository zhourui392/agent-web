# 配置指南

完整配置及内联注释以 [`src/main/resources/application.yml`](../src/main/resources/application.yml) 为准。本文件说明常用覆盖项、配置来源和敏感信息边界，不复制全部 Spring 配置。

## 工作空间配置

数据库尚未保存机器路径配置时，应用使用 [`src/main/resources/agent-paths.yml`](../src/main/resources/agent-paths.yml) 作为种子：

```yaml
agent:
  fs:
    roots:
      - "${AGENT_WORKSPACE_ROOT:${AGENT_WORKTREE_ALLOWED_ROOT:${user.home}/workspace}}"
    upload-roots: []
```

管理员可以在 `/admin/settings.html` 维护默认工作空间、允许的工作空间根目录和仅上传额外根目录。保存后，配置以 `workspace.configuration` 单个 JSON 文档写入 `app_setting` 表并立即生效；数据库值优先于种子文件。

- `agent.fs.roots` 同时授权文件接口、会话、Workspace Context 和 Git worktree 操作。
- `agent.fs.upload-roots` 只为上传接口增加额外授权，不扩大其他文件能力。
- 所有路径必须是已存在的绝对目录。
- 默认工作空间必须同时位于允许根目录列表中。
- 通用配置按 key 使用进程内缓存；更新后刷新对应缓存，删除或“恢复配置文件默认值”后淘汰缓存。

除非明确要改变部署种子，不应主动修改 `agent-paths.yml`。

## 服务配置概览

当前主要配置边界如下；省略项仍以 `application.yml` 为准：

```yaml
server:
  address: 127.0.0.1
  port: 18092
  forward-headers-strategy: framework
  servlet:
    context-path: ""

agent:
  default-type: CODEX
  cli:
    claude: { ... }
    codex: { ... }
  envs:
    - { key: test, label: 测试环境 }
    - { key: prod, label: 生产环境 }
  auth:
    cookie-name: __Host-agent_session
    cookie-secure: true
    session-ttl-seconds: 604800
    login-max-failures: 5
    login-failure-window-seconds: 300
  public-access:
    enabled: true
  refinery:
    enabled: false
  runtime:
    workbench-enabled: <见 application.yml>
    chat-enabled: false
  workbench:
    enabled: <见 application.yml>
    create-enabled: <见 application.yml>
    write-run-enabled: <见 application.yml>
    high-impact:
      commit-enabled: false
      push-enabled: false
      local-deploy-enabled: false
      production-write-enabled: false
```

关键语义：

- `agent.default-type` 只是数据库尚无值时的首启种子，落库后以管理后台设置为准。
- Claude 默认使用 `stream-json`；Codex `args` 为空时使用真实 `codex exec --json`，填写模板时回退 legacy 文本路径。
- `agent.runtime.workbench-enabled` 与 `agent.runtime.chat-enabled` 分别控制 Workbench 和普通 Chat 是否使用公共进程 Runtime，二者互不隐式开启。
- Workbench 页面、创建、写 Run 和公共 Workbench Runtime 使用独立开关分级发布，默认值以当前 `application.yml` 为准；高影响 Executor 全部默认关闭。Operation Proposal 的创建不构成授权。
- Knowledge Refinery 与 NATIVE 默认关闭。

## NATIVE 进程内诊断 Agent

NATIVE 默认关闭，默认模型为 `gpt-5.6-sol`。启用时至少设置：

```bash
AGENT_NATIVE_ENABLED=true
AGENT_NATIVE_API_KEY=<由进程环境或 Secret Store 注入>
AGENT_NATIVE_BOUND_ENVIRONMENT=test
```

需要换模型时覆盖 `AGENT_NATIVE_MODEL`。OpenAI-compatible 凭据和地址只使用 `AGENT_NATIVE_API_KEY`、`AGENT_NATIVE_BASE_URL`；NATIVE 不读取 `OPENAI_API_KEY` 或 `OPENAI_BASE_URL`，避免与 Codex CLI、官方 OpenAI 客户端串用 Provider。

NATIVE 默认把宿主 `logs/` 目录作为 test-bound 只读日志源，只允许配置的 `*.log`，逻辑服务为 `agent-web`、逻辑数据源为 `local-agent-web-logs`。日志根由 `AGENT_NATIVE_LOCAL_LOG_ROOT` 固定，不能由用户消息或聊天 `workingDir` 改写；根目录必须是存在且可读的绝对目录。

如果改用远程 `agent.native.backends.log-query-url`，必须设置 `AGENT_NATIVE_LOCAL_LOGS_ENABLED=false`。本地和远程 LogQuery 同时启用会 fail-fast，防止同名工具被静默覆盖。完整设计见 [NATIVE 诊断 Agent 集成](native-diagnosis-agent-integration-design.md)。

## Knowledge Refinery

Knowledge Refinery 默认关闭。启用时通过 `REFINERY_EMBED_API_KEY` 注入 embedding 鉴权，并保证 `REFINERY_EMBED_DIM` 与模型维度一致；不一致会在启动时 fail-fast。更换 embedding 模型时必须重新嵌入存量 chunk。

凭据只通过进程环境、Secret Store 或下述 Git 忽略文件注入，不写入 `application.yml`。

## 敏感配置

生产环境优先使用进程环境或 Secret Store。本机确实需要文件化的服务端秘密时，只能写入 Git 忽略的 `data/secrets.properties`；写入前先确认忽略规则：

```bash
git check-ignore -q data/secrets.properties
chmod 700 data
chmod 600 data/secrets.properties
```

文件采用 Spring Properties 语法。示例只列变量名或占位符，不能把真实值写入仓库配置、文档、Fixture 或受版本控制的脚本：

```properties
GIT_CRED_ENC_KEY=<32 字节密钥的 base64>
REFINERY_EMBED_API_KEY=<embedding key>
agent.native.api-key=<NATIVE Provider key>
agent.native.base-url=<NATIVE OpenAI-compatible base URL>
AGENT_BOOTSTRAP_ADMIN_PASSWORD=<仅首次公网启动使用的新管理员密码>
```

外部环境变量优先级更高。普通 Codex CLI 和 Claude Code 不读取该文件中的认证配置，仍使用各自的本机默认登录态；不得读取、复制或改写用户级 `~/.codex`、`~/.claude` 认证目录。`AGENT_BOOTSTRAP_ADMIN_PASSWORD` 只用于首次替换种子密码，启动成功后应立即从文件或进程环境中移除。

## 常用环境变量

下表用于快速检索常用覆盖项，不代替 `application.yml` 中的完整配置。

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI 可执行路径 |
| `CODEX_CMD` | `codex` | Codex CLI 可执行路径 |
| `CODEX_SANDBOX_BYPASS` | `true` | 普通 Codex CLI 是否绕过 sandbox；公网或多人环境必须显式设为 `false` |
| `SERVER_ADDRESS` | `127.0.0.1` | 应用监听地址；同机 Caddy 通过 loopback 访问 18092 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | 识别代理传入的 HTTPS 和客户端地址；仅在代理受信且后端端口隔离时使用 |
| `CODEX_HOME` | `~/.codex` | Codex 配置与鉴权目录 |
| `OPENAI_API_KEY` | 无 | Codex CLI 或官方 OpenAI 客户端鉴权；NATIVE 不读取 |
| `OPENAI_BASE_URL` | Provider 默认值 | Codex CLI 或官方 OpenAI 客户端地址；NATIVE 不读取 |
| `AGENT_NATIVE_ENABLED` | `false` | 是否注册进程内只读诊断运行时并在 Agent Catalog 标记可用 |
| `AGENT_NATIVE_PROVIDER` / `AGENT_NATIVE_MODEL` | `OPENAI` / `gpt-5.6-sol` | NATIVE Provider 类型和模型 |
| `AGENT_NATIVE_API_KEY` / `AGENT_NATIVE_BASE_URL` | 无 | NATIVE 专用凭据和 OpenAI-compatible 地址，不回退标准 OpenAI 变量 |
| `AGENT_NATIVE_BOUND_ENVIRONMENT` | `test` | NATIVE 唯一允许的聊天环境，必须存在于 `agent.envs` |
| `AGENT_NATIVE_LOCAL_LOGS_ENABLED` | `true` | 是否注册宿主受限本机日志 `LogQuery`；启用远程 LogQuery 时必须关闭 |
| `AGENT_NATIVE_LOCAL_LOG_ROOT` | `${user.dir}/logs` | 本机日志 allowlist 根，必须为存在且可读的绝对目录 |
| `AGENT_NATIVE_LOCAL_LOG_SERVICE` | `agent-web` | 宿主注入的默认逻辑服务，不从 Prompt 猜测 |
| `AGENT_NATIVE_LOCAL_LOG_DATA_SOURCE_ID` | `local-agent-web-logs` | 对外证据使用的逻辑数据源 ID，不暴露真实路径 |
| `AGENT_NATIVE_LOCAL_LOG_ZONE` | `UTC` | 无时区本地日志时间戳的解析时区 |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite 数据库路径 |
| `AGENT_CHAT_USER_ISOLATION_ENABLED` | `true` | 对话可见性；普通用户只看自己和无主老数据 |
| `AGENT_AUTH_SESSION_TTL_SECONDS` | `604800` | 数据库登录会话有效期，单位为秒 |
| `AGENT_AUTH_COOKIE_NAME` | `__Host-agent_session` | 登录会话 Cookie 名；`__Host-` 要求 Secure、Path=/ 且无 Domain |
| `AGENT_AUTH_COOKIE_SECURE` | `true` | 强制会话 Cookie 使用 Secure；仅本机纯 HTTP 开发可关闭 |
| `AGENT_AUTH_LOGIN_MAX_FAILURES` | `5` | 登录失败限流阈值，同时按直连来源和用户名统计 |
| `AGENT_AUTH_LOGIN_FAILURE_WINDOW_SECONDS` | `300` | 登录失败限流窗口，单位为秒 |
| `AGENT_PUBLIC_ACCESS_ENABLED` | `true` | 公网启动安全门禁；仅本机 loopback 开发可关闭 |
| `AGENT_BOOTSTRAP_ADMIN_PASSWORD` | 无 | 数据库仍是公开种子哈希时使用一次的新管理员密码，长度 12～256 字符 |
| `AGENT_WORKSPACE_ROOT` | `${user.home}/workspace` | 工作空间配置未落库时的主工作区种子 |
| `AGENT_WORKTREE_ALLOWED_ROOT` | `${user.home}/workspace` | 旧部署兼容变量；未设置 `AGENT_WORKSPACE_ROOT` 时作为回退 |
| `AGENT_E2E_ADMIN_PASSWORD` | 无 | Playwright 登录测试账户密码，只用于测试进程 |
| `GIT_CRED_ENC_KEY` | 无 | 用户 Git push 凭据的 AES-256-GCM 主密钥 |
| `REFINERY_ENABLED` | `false` | Knowledge Refinery 总开关 |
| `REFINERY_EMBED_API_KEY` | 无 | embedding 鉴权；Refinery 开启时必填 |
| `REFINERY_EMBED_ENDPOINT` | `https://openrouter.ai/api/v1` | embedding base URL，使用 OpenAI 兼容协议 |
| `REFINERY_EMBED_MODEL` / `REFINERY_EMBED_DIM` | `qwen/qwen3-embedding-8b` / `4096` | embedding 模型与维度 |
| `AGENT_RUN_WORKSPACE_CONTEXT_ENABLED` | `true` | AgentRun workspace context 注入总开关 |
| `AGENT_RUN_WORKSPACE_KNOWLEDGE_ENABLED` | `true` | AgentRun workspace 知识预召回开关 |
| `AGENT_RUN_RECALL_TOP_K` | `8` | AgentRun 召回 top-K |
| `AGENT_WORKBENCH_ENABLED` | 见 `application.yml` | Workbench 页面和 API 总开关；关闭时下级开关也必须关闭 |
| `AGENT_WORKBENCH_CREATE_ENABLED` / `AGENT_WORKBENCH_WRITE_RUN_ENABLED` | 见 `application.yml` | Workbench 创建和写 Run 分级发布开关 |
| `AGENT_COMMON_RUNTIME_WORKBENCH_ENABLED` | 见 `application.yml` | Workbench 使用公共 Codex Runtime 的独立开关，不改变普通 Chat 路径 |
| `AGENT_WORKBENCH_HIGH_IMPACT_COMMIT_ENABLED` / `AGENT_WORKBENCH_HIGH_IMPACT_PUSH_ENABLED` | `false` / `false` | Git commit/push Executor 发布开关；Proposal 本身不构成授权 |
| `AGENT_WORKBENCH_HIGH_IMPACT_LOCAL_DEPLOY_ENABLED` / `AGENT_WORKBENCH_HIGH_IMPACT_PRODUCTION_WRITE_ENABLED` | `false` / `false` | 本地部署/生产写 Executor 发布开关，默认 fail-closed |
| `AGENT_WORKBENCH_RUN_TIMEOUT_SECONDS` / `AGENT_WORKBENCH_RUN_MAX_OUTPUT_BYTES` | `1800` / `8388608` | Workbench 单次 Run 超时和输出上限 |
| `AGENT_WORKBENCH_ATTACHMENT_STORAGE_ROOT` | `data/workbench/uploads` | 浏览器上传附件的 Git 忽略受控根，不写入 Repository Scope |
| `AGENT_WORKBENCH_ATTACHMENT_MAXIMUM_BYTES` / `AGENT_WORKBENCH_ATTACHMENT_MAXIMUM_AVAILABLE` | `10485760` / `16` | 单附件大小和每阶段会话可用附件数上限 |
| `AGENT_CAPABILITY_RULE_ROOT` | `src/main/resources/capability/rules` | Workbench 可信 Rule Catalog 根 |
| `AGENT_CAPABILITY_SKILL_ROOT` | `src/main/resources/capability/skills` | Workbench 平台可信 Skill Catalog 根 |
| `AGENT_CAPABILITY_MCP_ROOT` | `src/main/resources/capability/mcp-servers` | Workbench 管理员可信 MCP Catalog 根 |
| `CODEX_STREAM_IDLE_TIMEOUT_SECONDS` / `CLAUDE_STREAM_IDLE_TIMEOUT_SECONDS` | `900` | 流式聊天无 stdout 活动的终止期限；`0` 表示禁用 |
| `CODEX_STREAM_MAX_RUNTIME_SECONDS` / `CLAUDE_STREAM_MAX_RUNTIME_SECONDS` | `7200` | 流式聊天绝对运行上限；`0` 表示禁用 |

生产部署的网络、密码和 Cookie 配置见[公网 HTTPS 部署](public-deployment.md)，整体安全边界见[安全指南](security.md)。
