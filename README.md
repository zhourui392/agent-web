# Agent Web

> 基于 Spring Boot 的 Web 服务：通过浏览器驱动本机 Claude / Codex CLI，提供流式对话、本地研发工作台、定时任务、文件与 Git 工作区管理。

README 只保留项目入口信息。功能、配置、使用、开发和运维细节统一收录在 [`docs/`](docs/README.md)；Agent 修改代码时的约束见 [`AGENTS.md`](AGENTS.md)。

## 核心能力

- **CLI Agent 对话**：支持 Claude CLI、Codex CLI、可恢复 SSE、多会话持久化、文件上下文、反馈和只读分享。
- **工作空间管理**：在授权目录内浏览和上传文件，管理 Git worktree、分支与每用户 Git 身份。
- **自动化与知识召回**：支持 Cron 定时任务，以及默认关闭的 Knowledge Refinery/RAG。
- **Local Development Workbench**：提供多仓 Repository Scope、动态 Stage、不可变 Run Snapshot、受控文档上下文和后台恢复。
- **NATIVE 诊断 Agent**：可在普通聊天中手动选择进程内只读诊断运行时，独立使用专用 Provider 配置。
- **管理与安全**：数据库用户认证、ADMIN 管理台、会话隔离、路径白名单和默认关闭的高影响操作执行器。

完整能力清单见[功能说明](docs/features.md)。Workbench 的当前模型、授权边界和验证要求见[技术设计总览](docs/workbench/README.md)。

## 技术栈

| 层面 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.3.13、Java 21、Maven |
| 数据库 | SQLite |
| 前端 | Vue 3、Element Plus、Vite |
| 通信 | REST API、Server-Sent Events |
| 架构 | DDD + 六边形四层架构 |
| 测试 | JUnit 5、Mockito、Vitest、Playwright |

## 快速开始

环境要求：Java 21+、Maven 3.6+，以及 Claude CLI / Codex CLI 中至少一个。

Linux：

```bash
./scripts/service.sh start
./scripts/service.sh status
```

Windows PowerShell：

```powershell
.\scripts\service.ps1 start
.\scripts\service.ps1 status
```

本机前台开发：

```bash
SERVER_ADDRESS=127.0.0.1 \
AGENT_PUBLIC_ACCESS_ENABLED=false \
AGENT_AUTH_COOKIE_SECURE=false \
AGENT_AUTH_COOKIE_NAME=local_session \
mvn spring-boot:run
```

服务脚本参数、JDK 探测、首次登录和公网启动要求见[安装与启动](docs/getting-started.md)。公网部署前必须替换公开的管理员种子密码，并完成[公网 HTTPS 部署清单](docs/public-deployment.md)。

## 文档导航

| 目标 | 文档 |
| --- | --- |
| 了解完整功能 | [功能说明](docs/features.md) |
| 安装、启动与首次登录 | [安装与启动](docs/getting-started.md) |
| 配置路径、密钥与环境变量 | [配置指南](docs/configuration.md) |
| 使用聊天与 Workbench | [使用指南](docs/user-guide.md) |
| 查看 API 边界 | [API 指南](docs/api.md) |
| 了解目录、测试和开发门禁 | [开发指南](docs/development.md) |
| 检查安全边界 | [安全指南](docs/security.md) |
| 部署公网 HTTPS | [公网部署](docs/public-deployment.md) |
| 查找架构与专题设计 | [完整文档索引](docs/README.md) |

运行配置的最终依据是 [`src/main/resources/application.yml`](src/main/resources/application.yml)，机器路径种子位于 [`src/main/resources/agent-paths.yml`](src/main/resources/agent-paths.yml)。
