# Local Development Workbench 技术设计总览

> 状态：TD-11 实施中
> 日期：2026-08-05
> 产品输入：[本地开发工作台 MVP 产品设计](../local-development-workbench-mvp-design.md)
> 权威当前设计：[TD-11 Workbench 动态阶段与全局上下文](td-11-dynamic-stages-global-context.md)
> @author alex

## 1. 当前结论

Workbench 采用 Dynamic Stage 单模型。管理员发布 Stage Definition，用户创建 Workbench 时选择 Stage，服务端按发布顺序冻结 Stage Snapshot；后续配置变化不改写已有 Workbench。

Workbench 没有历史数据，因此直接切换，不实现：

- 同时读取固定工作单元与 Dynamic Stage；
- Phase 数据转换或在线迁移；
- 模型切换工厂、模型判别或旧 API 适配；
- 前端导航与 localStorage 回退；
- 旧 SQLite 表创建或恢复。

固定工作单元、相邻交接、专属评审确认、能力覆盖和类型化高影响操作不属于当前在线模型。

## 2. 目标架构

```text
interfaces/
  Owner Stage API、Admin Stage API、请求校验与安全响应
       │
       ▼
app/
  用例编排、Owner-first 授权、事务与查询服务
       │
       ▼
domain/
  Workbench、Stage Catalog、Stage Snapshot、Run Snapshot、不变量
       ▲
       │
infra/
  SQLite、Artifact Registry、文件、Runtime、SSE、Telemetry
```

应用层只编排；Stage 集合查找、状态迁移、Run 租约、Run Mode 与精确绑定判断由聚合表达。写侧 Repository 只负责聚合生命周期；Owner/Admin 列表和详情使用独立 Query Service。

## 3. 核心模型

### 3.1 Stage Catalog

```text
WorkbenchStageCatalog
└── WorkbenchStageDefinition
    ├── currentDraft
    ├── currentPublishedRevision
    ├── disabled
    └── immutable revisions[]
```

Stage 生命周期为 Draft、Published、Disabled。发布同时冻结顺序、名称、说明、Rules、Allowed Run Modes，以及 Command、Skill、MCP 的精确版本和 Hash。

### 3.2 Workbench

```text
Workbench
├── owner
├── repositoryScope
├── creationWorkspaceSnapshot
├── stages[]
│   ├── stageInstanceIdentifier
│   ├── immutable stageSnapshot
│   ├── conversation generation
│   ├── status
│   └── activeRunReference?
├── activeWriteRunReference?
└── status/version
```

Workbench 必须至少包含一个 Stage。Stage 集合创建后不隐式增删、重排或升级。

### 3.3 Run

唯一身份：

```text
originReference    = workbenchId + ":" + stageInstanceIdentifier
runOrigin          = WORKBENCH
sessionKind        = WORKBENCH_STAGE
executionContextId = runId
```

每次 Run 再冻结：

- Stage Snapshot 与 Hash；
- Command Binding；
- Capability Binding；
- Context Version、Hash 与文档清单；
- Workspace Snapshot；
- Prompt Parts 与 Prompt Hash；
- Runtime Enforcement；
- Repository/Uploaded Attachment 引用。

## 4. 核心不变量

1. Workbench Stage 集合非空，Definition、Instance Identifier 和 Sequence 分别唯一。
2. Published Revision 与 Workbench Stage Snapshot 不可变。
3. 客户端只提交 Stage Definition Identifier 集合，服务端决定发布顺序。
4. 每个 Stage 最多一个活动 Run；每个 Workbench 最多一个活动写 Run。
5. Stage 名称不触发权限、评审、交接或自动流转。
6. Run Mode 只来自冻结 Snapshot 的 `allowedRunModes`。
7. Command 只来自冻结 Stage Snapshot 与不可变 Artifact Registry。
8. Runtime Compatibility 使用应用当前唯一 Compatibility Matrix Version。
9. Runtime 只能在 Run 事务提交后启动。
10. Run 终态释放 Stage 活动引用；可写 Run 同时释放 Workbench 写租约。
11. Attachment 精确绑定 Owner、Workbench、Stage、Conversation Generation 和 Hash。
12. 归档 Workbench 只读，不允许新 Run 或其他业务修改。

## 5. Owner 与 Admin 边界

Owner Run 授权顺序固定为：

```text
加载 Workbench
→ 校验 Owner
→ 解析 Run ID
→ 查询 Stage Run Snapshot
→ 查询 ChatRun
→ 校验 exact Stage Origin
```

错误 Owner、Workbench、Stage、Run、Run ID 或 Origin 对外统一为 Run 不存在，避免身份枚举。

Admin 只允许：

- 查询 Workbench 与 Run 安全投影；
- Stop；
- 单 Run Reconcile；
- 管理 Capability Source 与 Stage Catalog。

Admin 不能代 Owner 对话、提交 Run 或修改 Owner 业务状态。

## 6. 在线 API

核心 Stage 路径：

```http
GET  /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/commands
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/runs
```

同一 Stage 身份还用于生命周期、Conversation、Conversation Restart、Attachment、Run History、SSE 与 Stop。

Admin 运维路径位于：

```text
/api/admin/workbenches
/api/admin-settings/workbench/stage-definitions
/api/admin-settings/workbench/capability-sources
```

不存在旧工作单元、交接、专属评审、能力覆盖或高影响操作 API。

## 7. 持久化

Stage-only 新库保留：

```text
workbench
workbench_repository_scope
workbench_stage
workbench_stage_conversation
workbench_stage_run_snapshot
workbench_stage_run_prompt_payload
workbench_stage_uploaded_attachment
workbench_stage_conversation_restart_receipt
workbench_creation_request
workbench_admin_audit
```

Capability Source、Stage Catalog 与 Artifact Registry 使用各自当前表。旧工作单元、交接、专属评审、旧 Run Snapshot、旧 Attachment、能力覆盖和高影响操作表不创建。

本项目不修改现有本地数据库来模拟迁移；SQLite 测试使用临时空库验证真实 Schema 和 Repository。

## 8. Capability 与 Runtime

```text
Capability Source
→ 当前 Catalog
→ Stage Draft Selection
→ Published Revision + Archived Artifact
→ Workbench Stage Snapshot
→ Run Capability Binding
→ Runtime Materialization
```

来源目录或当前 MCP 配置变化不会改写已发布 Revision；运行时 Artifact 丢失、内容 Hash 不匹配或 Runtime 不兼容时失败关闭。

高影响命令由 Runtime Command Policy 直接拒绝。`MODIFY_WORKSPACE` 只授权冻结 Repository Scope 内的普通文件修改，不授权 commit、push、部署或生产写入。

## 9. Context

目标模型是 Workbench 级 Global Context：Owner 发布文档引用，后续 Stage Run 获得有界元数据，正文按需通过受控 Document Gateway 读取。

完整 Context 聚合尚未完成。当前没有已发布文档时使用确定性基线：

```text
contextVersion = 0
documents      = []
promptContent  = Context version: 0
                 No published documents.
```

该基线是当前业务事实，不是旧模型回退。

## 10. Telemetry

保留 Stage-only 指标：creation、run terminal、write conflict、SSE reconnect、event lag、capability resolution/version change、scope violation、document read 和 recovery reconciliation。

Run 指标不使用旧工作单元标签，也不使用高基数 Stage Instance Identifier。

## 11. 前端

- Detail 与导航只消费 `stages[]`。
- 本地状态键包含 User、Workbench、Stage Instance 和 Conversation Generation。
- 唯一 Run Mode 自动采用；双模式必须显式选择。
- Run Mode 进入提交指纹与幂等重试判断。
- Stage Attachment、SSE、History、Document 和实时测试事件共享精确 Stage 身份。
- Admin Workbench 只展示 Stage 投影。

## 12. 测试与发布

完整策略见 [TD-10](td-10-test-release.md)，当前证据见[发布就绪快照](release-readiness-2026-08-01.md)。

至少包括：

- Java 领域、应用、接口与真实 SQLite；
- Owner-first 越权、错 Origin、并发、幂等和终态释放；
- Frontend TypeScript、Lint、Build 和 Vitest；
- Mocked Owner/Admin Playwright；
- 真实 Spring、临时 SQLite、Runtime Stub、SSE Playwright；
- 独立 JVM 强杀与重启恢复。

## 13. 专题索引

当前权威：

- [TD-11 Dynamic Stage 与 Global Context](td-11-dynamic-stages-global-context.md)
- [TD-11 实施进度](td-11-implementation-progress.md)
- [TD-10 Stage-only 测试与发布](td-10-test-release.md)
- [发布就绪快照](release-readiness-2026-08-01.md)
- [TD-08 高影响命令边界](td-08-high-impact-operations.md)

仍适用的基础专题：

- [TD-01 公共 Runtime 与 Capability 解耦](td-01-runtime-capability-decoupling.md)
- [TD-04 多仓库工作区](td-04-multi-repository-workspace.md)
- [TD-06 文档查看器](td-06-document-viewer.md)
- [TD-09 可观测性与运维](td-09-observability-operations.md)

TD-02、TD-03、TD-05 和 TD-07 中与固定工作单元、交接、专属评审或能力覆盖有关的章节仅保留为历史设计记录，不是当前实现合同；与 TD-11 冲突时一律以 TD-11 为准。
