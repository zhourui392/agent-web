# Agent Web

> 一个基于 Spring Boot 的 Web 服务，通过浏览器界面驱动本地 CLI AI 代理（Codex/Claude）

## 项目简介

Agent Web 是一个 Web 中间层服务，它将本地命令行 AI 代理（如 Claude CLI 和 Codex）封装为 Web API，并提供了一个现代化的聊天界面。用户无需直接使用终端命令，即可通过浏览器与 AI 代理进行交互，完成代码生成、项目分析等任务。

### 核心特性

- **Web 化交互**: 通过友好的聊天界面与 CLI 代理通信，无需记忆复杂命令
- **实时流式输出**: 基于 Server-Sent Events (SSE) 的实时响应流式传输
- **会话管理**: 支持多会话隔离，每个会话绑定独立的工作目录
- **上下文保持**: 支持 Claude 的会话恢复功能，保持对话连续性
- **文件系统浏览**: 内置目录浏览器，可视化选择工作目录
- **身份认证**: 基于 Session 的用户认证机制
- **双代理支持**: 同时支持 Codex 和 Claude 两种 AI 代理

## 技术栈

### 后端
- **框架**: Spring Boot 2.7.18
- **语言**: Java 8+
- **构建工具**: Maven
- **架构模式**: 领域驱动设计 (DDD) + 六边形架构

### 前端
- **框架**: Vue 3 (CDN)
- **UI 组件**: Element Plus
- **通信**: RESTful API + Server-Sent Events

### 支持的 AI 代理
- **Claude**: Anthropic Claude CLI (Sonnet 4.5)
- **Codex**: OpenAI Codex CLI

## 快速开始

### 环境要求

- Java 8 或更高版本
- Maven 3.6+
- Claude CLI 或 Codex CLI（至少安装一个）

### 安装步骤

1. **克隆项目**
```bash
git clone <repository-url>
cd agent-web
```

2. **配置 CLI 代理路径**（可选）

如果 Claude/Codex 不在系统 PATH 中，可设置环境变量：
```bash
export CLAUDE_CLI_CMD=/path/to/claude
export CODEX_CMD=/path/to/codex
```

3. **修改配置文件**（推荐）

编辑 `src/main/resources/application.yml`，限制允许访问的目录：
```yaml
agent:
  fs:
    roots:
      - "/home/user/projects"  # 修改为实际的工作目录
      - "/tmp"
```

4. **构建项目**
```bash
mvn clean package
```

5. **启动服务**

使用 Maven 启动：
```bash
mvn spring-boot:run
```

或使用提供的脚本：
```bash
# Linux/macOS
./run.sh

# Windows
run.bat
```

或直接运行 JAR：
```bash
java -jar target/agent-web-0.1.0-SNAPSHOT.jar
```

6. **访问应用**

打开浏览器访问：`http://localhost:18092`

默认登录凭据：
- 用户名：`admin`
- 密码：`Zz135246!@#$`

## 使用指南

### 创建会话

1. 登录系统后，在左侧边栏选择工作目录
2. 点击根路径下拉框，选择允许的根目录
3. 浏览并点击目标文件夹
4. 确认代理类型（当前为 CLAUDE）
5. 点击"开始会话"按钮

### 发送消息

1. 会话创建成功后，在底部输入框输入你的指令
2. 按 `Enter` 发送消息（`Ctrl+Enter` 换行）
3. 实时查看 AI 代理的流式响应
4. 系统会自动提取并保存 Resume ID，用于恢复上下文

### 清除上下文

如需开始全新对话，点击"清除上下文"按钮，系统将重置会话状态。

## API 文档

### 认证接口

#### 登录
```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "Zz135246!@#$"
}
```

#### 登出
```http
POST /api/auth/logout
```

#### 检查认证状态
```http
GET /api/auth/check
```

### 文件系统接口

#### 获取允许的根目录
```http
GET /api/fs/roots
```

#### 列出子目录
```http
GET /api/fs/list?path=/home/user/projects
```

### 聊天会话接口

#### 创建会话
```http
POST /api/chat/session
Content-Type: application/json

{
  "agentType": "CLAUDE",
  "workingDir": "/home/user/projects/my-project"
}
```

响应：
```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "agentType": "CLAUDE",
  "workingDir": "/home/user/projects/my-project"
}
```

#### 发送消息（同步）
```http
POST /api/chat/session/{sessionId}/message
Content-Type: application/json

{
  "message": "分析这个项目的结构"
}
```

#### 发送消息（流式）
```http
GET /api/chat/session/{sessionId}/message/stream?message=分析项目结构&resumeId=xxx
```

响应格式（Server-Sent Events）：
```
event: chunk
data: {"type":"stream_event","content":"..."}

event: exit
data: 0

event: error
data: 错误信息
```

## 项目结构

```
src/main/java/com/example/agentweb/
├── interfaces/              # 接口层（REST 控制器、DTO）
│   ├── ChatController.java       # 聊天会话和消息处理
│   ├── FsController.java         # 文件系统浏览
│   ├── AuthController.java       # 用户认证
│   ├── GlobalExceptionHandler.java
│   └── dto/                      # 数据传输对象
│
├── app/                     # 应用服务层
│   ├── ChatAppService.java       # 服务接口
│   └── ChatAppServiceImpl.java   # 服务实现
│
├── domain/                  # 领域模型层
│   ├── ChatSession.java          # 聊天会话聚合根
│   └── AgentType.java            # 代理类型枚举
│
├── adapter/                 # 适配器接口
│   └── AgentGateway.java         # 代理网关接口
│
├── infra/                   # 基础设施层
│   ├── AgentCliGateway.java      # CLI 进程执行适配器
│   ├── AgentCliProperties.java   # CLI 配置属性
│   ├── FsProperties.java         # 文件系统配置
│   ├── InMemorySessionRepo.java  # 内存会话仓储
│   ├── AuthenticationInterceptor.java
│   └── ExecutorConfig.java       # 线程池配置
│
└── config/                  # Spring 配置
    └── WebConfig.java            # Web MVC 配置

src/main/resources/
├── application.yml          # 应用配置文件
└── static/
    ├── index.html          # 主聊天界面（Vue 3）
    └── login.html          # 登录页面（Vue 3）
```

## 配置说明

### 端口配置
```yaml
server:
  port: 18092  # 修改为所需端口
```

### 文件系统根目录
```yaml
agent:
  fs:
    roots:
      - "/home"      # 生产环境应限制为特定目录
      - "/tmp"
```

### Codex 代理配置
```yaml
agent:
  cli:
    codex:
      exec: ${CODEX_CMD:codex}  # 可通过环境变量覆盖
      args:
        - exec
        - --skip-git-repo-check
        - --full-auto
      stdin: true
      timeout-seconds: 180      # 超时时间（秒）
```

### Claude 代理配置
```yaml
agent:
  cli:
    claude:
      exec: ${CLAUDE_CLI_CMD:claude}
      args:
        - --print
        - --output-format
        - stream-json
        - --verbose
        - --include-partial-messages
        - --dangerously-skip-permissions
      stdin: true
      timeout-seconds: 180
```

## 架构设计

### 设计原则

本项目采用 **领域驱动设计 (DDD)** 和 **六边形架构（端口与适配器）**，确保代码的可维护性和可测试性。

### 分层结构

1. **接口层 (interfaces)**: 处理 HTTP 请求和响应，定义 DTO
2. **应用层 (app)**: 协调领域对象和基础设施，编排业务流程
3. **领域层 (domain)**: 核心业务逻辑和领域模型
4. **适配器层 (adapter)**: 定义外部系统交互的端口
5. **基础设施层 (infra)**: 实现具体的技术细节（进程调用、存储等）

### 执行流程

**会话创建流程**:
1. 用户选择工作目录 → 2. 发送 `POST /api/chat/session` → 3. 创建 `ChatSession` 对象 → 4. 存储到内存仓储 → 5. 返回会话 ID

**消息执行流程（流式）**:
1. 用户输入消息 → 2. 发送 `GET /stream?message=xxx` → 3. 启动 CLI 进程 → 4. 将消息写入 stdin → 5. 实时读取 stdout → 6. 通过 SSE 推送到前端 → 7. 进程结束，返回退出码

## 安全注意事项

### 当前实现

- ✅ 基于 Session 的身份认证
- ✅ 路径验证防止目录穿越
- ✅ 进程超时保护（默认 180 秒）
- ⚠️ 使用硬编码的管理员凭据（仅供开发测试）

### 生产环境建议

- ❗ **更改默认密码**：修改 `AuthController` 中的凭据或集成外部认证系统
- ❗ **限制根目录**：在 `application.yml` 中严格限制 `agent.fs.roots`
- ❗ **启用 HTTPS**：配置 SSL/TLS 证书
- ❗ **移除危险标志**：评估是否需要 `--dangerously-skip-permissions`
- ❗ **添加访问日志**：记录所有代理调用和文件访问
- ❗ **实施速率限制**：防止滥用和资源耗尽

## 测试

### 运行测试
```bash
# 运行所有测试
mvn test

# 运行代码质量检查
mvn pmd:check
```

### 测试文件
- `FsControllerTest.java` - 文件系统控制器测试
- `ChatFlowTest.java` - 聊天流程集成测试

## 常见问题

### Q: 如何添加新的 AI 代理？
A:
1. 在 `AgentType` 枚举中添加新类型
2. 在 `application.yml` 中配置代理的 CLI 命令和参数
3. 在 `AgentCliProperties` 中添加对应的配置类

### Q: 会话数据存储在哪里？
A: 当前使用内存存储 (`InMemorySessionRepo`)，服务重启后会话数据会丢失。生产环境建议实现持久化存储（如 Redis）。

### Q: 如何自定义超时时间？
A: 修改 `application.yml` 中的 `timeout-seconds` 配置项。

### Q: 支持并发会话吗？
A: 支持。每个会话独立管理，互不影响。线程池配置在 `ExecutorConfig` 中。

## 开发计划

- [ ] 支持更多 AI 代理（如 GPT-4、Gemini）
- [ ] 会话持久化存储（Redis/数据库）
- [ ] 文件上传和下载功能
- [ ] 多用户管理和权限控制
- [ ] Docker 容器化部署
- [ ] WebSocket 替代 SSE
- [ ] 前端优化：代码高亮、Markdown 渲染

## 版本历史

### 0.1.0-SNAPSHOT (当前版本)
- ✅ Claude 会话上下文管理和恢复支持
- ✅ 前端升级到 Vue 3 + Element Plus
- ✅ Codex 和 Claude 自动模式支持
- ✅ 流式输出显示优化
- ✅ Git 仓库检查跳过选项

## 许可证

请根据项目实际情况添加许可证信息。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 联系方式

如有问题或建议，请通过以下方式联系：
- 提交 Issue: [项目仓库]
- 邮箱: [联系邮箱]
