# Agent Web

> 基于 Spring Boot 的 Web 服务，通过浏览器界面驱动本地 CLI AI 代理（Claude / Codex）

## 核心特性

- **Web 化交互** — 聊天界面与 CLI 代理通信，无需终端命令
- **实时流式输出** — 基于 SSE 的响应流式传输
- **会话管理** — 多会话隔离，每个会话绑定独立工作目录，支持上下文恢复
- **会话持久化** — SQLite + JSON 双存储，服务重启不丢失
- **文件系统浏览** — 目录浏览、文件上传 / 下载 / 删除
- **对话分享** — 生成公开链接，他人免登录查看历史对话
- **定时任务** — Cron 表达式驱动的定时 Agent 任务调度
- **Git Worktree** — 按分支切换工作空间，支持并行多分支开发
- **Slash Commands** — 自动扫描工作目录下的自定义命令并展开
- **环境切换** — 支持多环境（测试 / 生产）上下文配置

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 2.7.18 / Java 8+ / Maven |
| 数据库 | SQLite（会话 + 定时任务持久化） |
| 前端 | Vue 3 + Element Plus（CDN） |
| 通信 | RESTful API + Server-Sent Events |
| 架构 | DDD + 六边形架构 |
| 代码质量 | Alibaba P3C (PMD) |
| 支持的代理 | Claude CLI (Opus 4.6) / OpenAI Codex CLI |

## 快速开始

### 环境要求

- Java 8+
- Maven 3.6+
- Claude CLI 或 Codex CLI（至少安装一个）

### 启动

```bash
# 构建
mvn clean package

# 启动（二选一）
mvn spring-boot:run
java -jar target/agent-web-0.1.0-SNAPSHOT.jar

# 访问
http://localhost:17988
```

默认登录凭据：`admin` / `Aa135246`（可在 `application.yml` 中修改）。

### 基本配置

编辑 `src/main/resources/application.yml`：

```yaml
server:
  port: 17988

agent:
  # 允许访问的根目录
  fs:
    roots:
      - "/home/user/projects"
  # 认证配置
  auth:
    enabled: true
    username: admin
    password: "your-password"
    max-fail-count: 50
  # CLI 代理路径（也可通过环境变量 CLAUDE_CLI_CMD / CODEX_CMD 覆盖）
  cli:
    claude:
      exec: ${CLAUDE_CLI_CMD:claude}
      timeout-seconds: 0  # 0 表示不超时
    codex:
      exec: ${CODEX_CMD:codex}
      timeout-seconds: 0
```

## 使用指南

1. 登录后在左侧边栏选择工作目录
2. 点击"开始会话"创建 Agent 会话
3. 在输入框输入指令，按 `Enter` 发送（`Ctrl+Enter` 换行）
4. 实时查看 Agent 流式响应
5. 可通过"分享"按钮生成公开链接

## API 接口

### 认证 `/api/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/status` | 登录状态与认证开关 |

### 聊天会话 `/api/chat`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/session` | 创建会话 |
| GET | `/api/chat/sessions?page=&size=` | 会话列表（分页） |
| GET | `/api/chat/session/{id}/messages` | 会话历史消息 |
| GET | `/api/chat/session/{id}/status` | 会话运行状态 |
| POST | `/api/chat/session/{id}/message` | 发送消息（同步） |
| GET | `/api/chat/session/{id}/message/stream` | 发送消息（SSE 流式） |
| POST | `/api/chat/session/{id}/stop` | 停止运行中的会话 |
| POST | `/api/chat/session/{id}/summarize` | 总结会话为 Issue Log |
| DELETE | `/api/chat/session/{id}` | 删除会话 |
| GET | `/api/chat/session/{id}/commands` | 会话可用 Slash Commands |
| GET | `/api/chat/commands?workingDir=` | 按目录查询 Slash Commands |
| GET | `/api/chat/envs` | 环境配置列表 |

### 对话分享

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/session/{id}/share` | 生成分享链接 |
| GET | `/api/share/{token}` | 查看分享内容（无需认证） |

### 文件系统 `/api/fs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/fs/roots` | 允许的根目录列表 |
| GET | `/api/fs/list?path=` | 列出目录内容 |
| POST | `/api/fs/upload` | 上传文件 |
| GET | `/api/fs/download?path=` | 下载文件 |
| DELETE | `/api/fs/delete?path=` | 删除文件 |

### 定时任务 `/api/tasks`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks` | 任务列表 |
| POST | `/api/tasks` | 创建任务 |
| GET | `/api/tasks/{id}` | 任务详情 |
| PUT | `/api/tasks/{id}` | 更新任务 |
| DELETE | `/api/tasks/{id}` | 删除任务 |
| POST | `/api/tasks/{id}/toggle` | 启用 / 禁用 |
| POST | `/api/tasks/{id}/run` | 手动触发执行 |

### Git Worktree `/api/worktree`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/worktree/switch` | 按分支切换工作空间 |
| GET | `/api/worktree/list?workspacePath=` | 列出分支 |
| DELETE | `/api/worktree/remove?workspacePath=&branch=` | 移除 worktree |

## 项目结构

```
src/main/java/com/example/agentweb/
├── interfaces/              # 接口层（REST 控制器、DTO）
│   ├── ChatController           # 聊天会话与消息
│   ├── FsController             # 文件系统浏览与管理
│   ├── AuthController           # 登录认证
│   ├── ShareController          # 对话分享
│   ├── ScheduledTaskController  # 定时任务管理
│   ├── WorktreeController       # Git Worktree 管理
│   ├── GlobalExceptionHandler   # 全局异常处理
│   └── dto/                     # 数据传输对象（14 个）
│
├── app/                     # 应用服务层
│   ├── ChatAppService           # 聊天服务接口与实现
│   ├── ScheduledTaskService     # 定时任务服务接口与实现
│   ├── WorktreeService          # Worktree 操作服务
│   ├── DynamicTaskScheduler     # 动态 Cron 调度器
│   ├── IssueLogWriter           # 会话总结输出
│   └── StreamChunkHandler       # SSE 流式块处理
│
├── domain/                  # 领域模型层
│   ├── ChatSession              # 聊天会话聚合根
│   ├── ChatMessage              # 聊天消息
│   ├── AgentType                # 代理类型枚举
│   ├── ScheduledTask            # 定时任务实体
│   ├── SlashCommand / Scanner / Expander  # 自定义命令
│   ├── SessionRepository        # 会话仓储接口
│   ├── SessionCache             # 会话缓存接口
│   └── ScheduledTaskRepository  # 定时任务仓储接口
│
├── adapter/                 # 适配器接口
│   └── AgentGateway             # 代理网关端口
│
├── infra/                   # 基础设施层
│   ├── AgentCliGateway          # CLI 进程执行适配器
│   ├── SqliteSessionRepo        # SQLite 会话持久化
│   ├── SqliteScheduledTaskRepo  # SQLite 定时任务持久化
│   ├── JsonFileSessionRepo      # JSON 文件会话持久化
│   ├── InMemorySessionRepo      # 内存会话缓存
│   ├── SqliteInitializer        # SQLite 建表初始化
│   ├── AuthFilter               # 认证过滤器
│   ├── FileSlashCommandScanner  # 文件系统命令扫描
│   ├── SlashCommandBeanRegistrar # 命令 Bean 注册
│   └── *Properties              # 配置属性类
│
└── config/
    └── WebConfig                # Web MVC 配置

src/main/resources/
├── application.yml
└── static/
    ├── index.html           # 主界面
    ├── login.html           # 登录页
    ├── share.html           # 分享页
    ├── js/app.js            # 前端逻辑
    └── css/app.css          # 样式
```

## 测试

```bash
mvn test          # 运行所有测试
mvn pmd:check     # 代码质量检查（Alibaba P3C）
```

测试文件：
- `ChatFlowTest` — 聊天流程集成测试
- `FsControllerTest` — 文件系统控制器测试
- `ResumeSessionTest` — 会话恢复测试
- `ScheduledTaskTest` — 定时任务测试
- `SlashCommandScannerTest` / `SlashCommandExpanderTest` — 自定义命令测试
- `WorktreeControllerTest` / `WorktreeServiceTest` — Worktree 测试
- `JsonFileSessionRepoTest` — JSON 持久化测试

## 安全注意事项

### 当前实现

- 路径验证防止目录穿越
- 登录认证 + 失败次数锁定
- 分享链接基于随机 Token，无需认证

### 生产环境建议

- 修改默认密码，限制 `agent.fs.roots` 为必要目录
- 启用 HTTPS
- 评估是否需要 `--dangerously-skip-permissions`
- 添加访问日志与速率限制
