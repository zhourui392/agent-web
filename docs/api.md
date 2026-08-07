# API 指南

完整端点、请求 DTO 和响应 DTO 以 [`src/main/java/com/example/agentweb/interfaces`](../src/main/java/com/example/agentweb/interfaces) 下各 `*Controller` 为准。本文件只说明稳定的认证和协议边界。

## 认证边界

- `/api/auth/login`、`/api/auth/status`、只读分享和静态资源是公开入口。
- 聊天、文件、定时任务、普通 worktree 和用户 Git 配置等接口要求数据库用户会话。
- `/api/metrics/*`、`/api/refinery/*`、`/api/admin*` 等管理能力在普通会话认证后继续校验 `ADMIN` 角色。
- 普通会话默认按用户隔离；删除会话只允许 owner 执行。

## ChatRun 与 SSE

页面聊天先提交后台 Run，再订阅事件：

```text
POST /api/chat/session/{id}/runs
Idempotency-Key: <客户端生成的幂等键>

GET /api/chat/runs/{runId}/events
Last-Event-ID: <最后确认的事件序号>
```

提交接口要求长度不超过 128 的 `Idempotency-Key`，成功时返回 `202 Accepted`。客户端断线后使用指数退避重连，并通过 `Last-Event-ID` 回放未确认事件；游标早于最早保留事件时返回 `410 EVENT_CURSOR_EXPIRED`，客户端应重新加载消息和 Run 状态。Run 的 `PENDING / RUNNING / CANCEL_REQUESTED` 与各终态由服务端持久化，停止命令保持幂等。旧 POST SSE、session status 和 session stop 入口已经移除。

## Workbench API

Workbench 按授权边界拆分：

- `POST /api/workbench/workspaces/inspect` 只检查 Workspace Root、仓库拓扑和可选范围，不创建 Workbench。
- `/api/workbenches` 负责 Owner 范围内的 Workbench、Stage、会话、Run、Capability、文档和附件。
- `/api/admin/workbenches` 只向 `ADMIN` 暴露安全投影以及 Stop/Reconcile 运维动作。
- `/api/admin-settings/workbench/stage-definitions` 和 `/api/admin-settings/workbench/capability-sources` 负责 Stage Catalog 与 Capability Source 的管理。

完整方法、请求体、幂等键和并发版本约束以 [`interfaces/workbench`](../src/main/java/com/example/agentweb/interfaces/workbench) 下的 Controller 为准。Workbench 的 Repository Scope、Owner-first 授权和 Admin 投影边界见[技术设计总览](workbench/README.md)。
