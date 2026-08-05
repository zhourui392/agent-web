# 文档索引

根目录 [`README.md`](../README.md) 只提供项目概览和最短启动路径。本目录承载使用、配置、开发、运维以及各限界上下文的详细设计。

## 使用与运维

| 文档 | 内容 |
| --- | --- |
| [功能说明](features.md) | 对话、Workbench、Knowledge Refinery 和平台运维的完整能力清单 |
| [安装与启动](getting-started.md) | 环境要求、服务脚本、前台启动、首次登录和本机 HTTP 开发 |
| [配置指南](configuration.md) | 工作空间、服务配置、NATIVE、敏感配置和常用环境变量 |
| [使用指南](user-guide.md) | 普通对话与 Local Development Workbench 操作流程 |
| [API 指南](api.md) | 认证边界、ChatRun SSE 协议和 Workbench API 边界 |
| [安全指南](security.md) | 已实现的安全控制与生产环境建议 |
| [公网 HTTPS 部署](public-deployment.md) | Caddy 拓扑、首次换密、启动与上线检查 |

## 开发与架构

| 文档 | 内容 |
| --- | --- |
| [开发指南](development.md) | 项目结构、测试金字塔、命令和发布前门禁 |
| [领域模型](domain-model.md) | 核心领域对象与关系 |
| [事件风暴](event-storming.md) | 业务命令、事件和策略 |
| [DDD 重构计划](ddd-refactoring-plan.md) | 分层与领域模型重构记录 |
| [前端重构计划](frontend-refactoring-plan.md) | 前端模块化与测试改造记录 |

## 聊天与运行时设计

| 文档 | 内容 |
| --- | --- |
| [可恢复聊天流设计](resumable-chat-stream-design.md) | ChatRun、事件存储、SSE 续传和恢复 |
| [工具调用持久化设计](chat-tool-invocation-persistence-design.md) | 工具调用生命周期与数据模型 |
| [工具调用历史验证](chat-tool-invocation-history-validation.md) | 历史投影验证记录 |
| [NATIVE 诊断 Agent 集成](native-diagnosis-agent-integration-design.md) | 进程内诊断运行时、配置与安全边界 |

## Local Development Workbench

| 文档 | 内容 |
| --- | --- |
| [MVP 产品设计](local-development-workbench-mvp-design.md) | 用户流程、范围与验收标准 |
| [技术设计总览](workbench/README.md) | 架构决策与 TD-01～TD-11 索引 |
| [动态阶段与全局上下文](workbench/td-11-dynamic-stages-global-context.md) | 管理端 Capability 来源、Stage 草稿/发布/停用、创建快照与全局文档上下文 |
| [发布就绪快照](workbench/release-readiness-2026-08-01.md) | 已有自动化证据、剩余真实试点与运维门禁 |

`workbench/` 下的 `td-*.md` 是各技术主题的详细设计。文档状态和日期以各文件头部声明为准；历史测试数量不能替代当前工作树的重新验证。
