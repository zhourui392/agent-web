# 文档索引

根目录 [`README.md`](../README.md) 只提供项目概览和最短启动路径。本目录只保留当前使用、配置、开发、运维和仍有效的架构约束；已完成的迁移计划、实施记录和一次性验证报告由 Git 历史保存。

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

## Local Development Workbench

| 文档 | 内容 |
| --- | --- |
| [技术设计总览](workbench/README.md) | 当前模型、架构决策、授权边界和测试要求 |
| [动态阶段与全局上下文](workbench/td-11-dynamic-stages-global-context.md) | 管理端 Capability 来源、Stage 草稿/发布/停用、创建快照与全局文档上下文 |

`workbench/` 只保留仍约束当前实现或未完成能力的专题设计。任何历史测试数量都不能替代当前工作树的重新验证。
