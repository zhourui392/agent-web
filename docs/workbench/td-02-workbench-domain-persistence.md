# TD-02 Workbench Domain 与持久化

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-01](td-01-runtime-capability-decoupling.md)
> @author alex

## 1. 目标

定义 Workbench 写模型、状态唯一来源、事务边界、SQLite 表和并发控制。Application 只编排创建、运行准备、
人工状态、Handoff 与外部端口；阶段状态、写运行互斥、Owner 权限和合法转换由 Domain 守护。

## 2. Workbench 聚合

```text
Workbench
├── WorkbenchId
├── OwnerReference
├── title / originalGoal
├── agentType / environment
├── RepositoryScope（创建后不可变）
├── creationSnapshotReference
├── phases: exactly 4
├── activeWriteRunReference?
├── status: ACTIVE | ARCHIVED
├── createdAt / updatedAt
└── version
```

`WorkbenchPhase` 是聚合内实体：

```text
WorkbenchPhase
├── phase: REQUIREMENT_ANALYSIS | SOLUTION_DESIGN | IMPLEMENT_TEST | REVIEW_REFACTOR
├── status: NOT_STARTED | IN_PROGRESS | HUMAN_COMPLETED
├── conversationReference?
├── conversationGeneration
├── activeRunReference?
├── lastActivityAt?
└── completedAt?
```

### 2.1 聚合行为

- `Workbench.create(...)`：构造期校验 Owner、目标和 Repository Scope，并一次生成四阶段。
- `bindConversation(phase, conversationRef)`：一个 Phase 只能绑定一个稳定会话；重复相同引用幂等。
- `restartConversation(phase, newConversationRef, actor, now)`：仅在 Phase 无活动 Run、已进入 IN_PROGRESS
  且未人工完成时替换当前会话并递增 generation；旧会话保留只读历史。人工完成的 Phase 必须先 reopen。
- `prepareRun(phase, runId, runMode, actor, now)`：校验 Owner/状态/Phase；拒绝 Phase 内第二个活动 Run，
  保存 Phase activeRunReference；首次运行推进为 IN_PROGRESS；`MODIFY` 时额外获取 Workbench 写租约。
- `finishRun(phase, runId, now)`：清除命中的 Phase activeRunReference；若同时命中 active write reference，
  一并释放写租约；重复终态幂等。
- `completePhase(phase, actor, now)`：活动运行不存在时进入 HUMAN_COMPLETED；不检查内容或 Gate。
- `reopenPhase(phase, actor, now)`：HUMAN_COMPLETED → IN_PROGRESS。
- `archive(actor, now)`：无活动写运行时归档；归档不删除消息和文档。

Application 禁止通过 `phase.getStatus()` 自行判断转换，必须调用上述行为。

## 3. 其他聚合

### 3.1 PhaseHandoff

独立聚合，避免用户编辑 Handoff 与运行状态更新竞争同一个 Workbench version：

- ID：`workbenchId + sourcePhase`；
- 内容：Summary、Decisions、Open Questions、Pinned Files、Referenced Runs；
- 行为：create/update、应用人工选择后的候选、计算 contentHash；
- 并发：expected version；
- 不调用 Repository，外部引用的归属证明由 Application 作为参数传入工厂。

### 3.2 PhaseCapabilityConfiguration

独立聚合，保存某 Workbench/Phase 的高级 Override：

- optional skill IDs、MCP IDs、Rule Override 引用；
- 不保存 Secret 或任意命令；
- 保存 Profile Base Version 和乐观 version；
- `changeOverride(...)` 调用 Phase Capability Policy，不能放宽强制安全规则；
- 删除 Override 表示恢复默认，不修改历史 Run Snapshot。

### 3.3 WorkbenchRunSnapshot

不可变聚合，创建后只读：

- 运行身份、Workbench、Phase、RunMode；
- Repository Scope/Workspace Snapshot Reference；
- Resolved Capability Binding、Profile/Override version；
- Handoff Reception version/hash；
- Prompt Part Hash、final prompt hash；
- Runtime Enforcement 摘要；
- createdAt。

禁止把运行终态写入 Snapshot；终态属于 ChatRun。

### 3.4 HighImpactOperation

独立聚合，详见 TD-08。Workbench 只保存引用，不内嵌操作历史。

## 4. Repository 与 QueryService

写侧接口位于 Domain，签名只使用领域类型：

```text
WorkbenchRepository
  add(Workbench)
  findById(WorkbenchId)
  update(Workbench)

PhaseHandoffRepository
  find(workbenchId, phase)
  add(PhaseHandoff)
  update(PhaseHandoff)

PhaseCapabilityConfigurationRepository
  find(workbenchId, phase)
  save(configuration)
  delete(workbenchId, phase, expectedVersion)

WorkbenchRunSnapshotRepository
  add(snapshot)
  findByRunId(runId)
```

列表、详情、阶段投影、能力详情和操作历史使用 `WorkbenchQueryService`、
`WorkbenchRunSnapshotQueryService` 等 CQRS 接口，接口放 Application，Infrastructure 直接返回 DTO。
不得让 Domain Repository 混入分页列表、复杂联表或管理页 Map。

## 5. SQLite Schema

### 5.1 Workbench 与 Scope

```sql
CREATE TABLE workbench (
    id                       TEXT PRIMARY KEY,
    owner_id                 TEXT NOT NULL,
    owner_name               TEXT NOT NULL,
    title                    TEXT NOT NULL,
    original_goal            TEXT NOT NULL,
    agent_type               TEXT NOT NULL,
    environment              TEXT,
    workspace_root           TEXT NOT NULL,
    primary_repository_key   TEXT NOT NULL,
    repository_scope_hash    TEXT NOT NULL,
    creation_snapshot_id     TEXT NOT NULL,
    active_write_run_id      TEXT,
    status                   TEXT NOT NULL,
    created_at               INTEGER NOT NULL,
    updated_at               INTEGER NOT NULL,
    version                  INTEGER NOT NULL DEFAULT 0,
    CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE workbench_repository_scope (
    workbench_id       TEXT NOT NULL,
    repository_key    TEXT NOT NULL,
    relative_path     TEXT NOT NULL,
    repository_root   TEXT NOT NULL,
    root_fingerprint  TEXT NOT NULL,
    primary_repository INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, repository_key),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE CASCADE,
    CHECK (primary_repository IN (0, 1))
);
```

`repository_root` 是服务端事实，不在普通 API 中返回。数据库额外通过触发器或 Repository 写入校验保证每个
Workbench 恰好一个 primary；领域模型仍是第一道规则。

### 5.2 Phase

```sql
CREATE TABLE workbench_phase (
    workbench_id       TEXT NOT NULL,
    phase              TEXT NOT NULL,
    phase_order        INTEGER NOT NULL,
    status             TEXT NOT NULL,
    conversation_id    TEXT,
    conversation_generation INTEGER NOT NULL DEFAULT 0,
    active_run_id      TEXT,
    active_run_mode    TEXT,
    last_activity_at   INTEGER,
    completed_at       INTEGER,
    PRIMARY KEY(workbench_id, phase),
    UNIQUE(workbench_id, phase_order),
    FOREIGN KEY(workbench_id) REFERENCES workbench(id) ON DELETE CASCADE
);
```

Phase 固定四行，由 Repository 按聚合一次保存；恢复时数量/枚举不完整必须 fail-fast，不能补默认掩盖坏数据。

会话代际单独保留历史映射：

```sql
CREATE TABLE workbench_phase_conversation (
    workbench_id       TEXT NOT NULL,
    phase              TEXT NOT NULL,
    generation         INTEGER NOT NULL,
    session_id         TEXT NOT NULL,
    created_by         TEXT NOT NULL,
    created_at         INTEGER NOT NULL,
    retired_at         INTEGER,
    PRIMARY KEY(workbench_id, phase, generation),
    UNIQUE(session_id),
    FOREIGN KEY(workbench_id, phase)
        REFERENCES workbench_phase(workbench_id, phase) ON DELETE CASCADE
);
```

Restart 不删除旧消息，也不把旧会话自动注入新会话；需要保留的结论应通过 Handoff 或用户消息明确传入。

### 5.3 Handoff 与接收记录

```sql
CREATE TABLE workbench_phase_handoff (
    workbench_id          TEXT NOT NULL,
    phase                 TEXT NOT NULL,
    summary               TEXT NOT NULL,
    decisions_json        TEXT NOT NULL,
    open_questions_json   TEXT NOT NULL,
    pinned_files_json     TEXT NOT NULL,
    referenced_runs_json  TEXT NOT NULL,
    content_hash          TEXT NOT NULL,
    updated_by            TEXT NOT NULL,
    updated_at            INTEGER NOT NULL,
    version               INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, phase)
);

CREATE TABLE workbench_handoff_reception (
    workbench_id       TEXT NOT NULL,
    target_phase       TEXT NOT NULL,
    source_phase       TEXT NOT NULL,
    source_version     INTEGER NOT NULL,
    source_hash        TEXT NOT NULL,
    accepted_by        TEXT NOT NULL,
    accepted_at        INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, target_phase, source_phase)
);
```

### 5.4 Capability 与 Run Snapshot

```sql
CREATE TABLE workbench_phase_capability_config (
    workbench_id       TEXT NOT NULL,
    phase              TEXT NOT NULL,
    base_profile_id    TEXT NOT NULL,
    base_profile_version TEXT NOT NULL,
    override_json      TEXT NOT NULL,
    updated_by         TEXT NOT NULL,
    updated_at         INTEGER NOT NULL,
    version            INTEGER NOT NULL,
    PRIMARY KEY(workbench_id, phase)
);

CREATE TABLE workbench_run_snapshot (
    run_id                       TEXT PRIMARY KEY,
    workbench_id                 TEXT NOT NULL,
    phase                        TEXT NOT NULL,
    run_mode                     TEXT NOT NULL,
    repository_scope_hash        TEXT NOT NULL,
    workspace_snapshot_id        TEXT NOT NULL,
    profile_id                   TEXT NOT NULL,
    profile_version              TEXT NOT NULL,
    override_version             INTEGER,
    capability_bindings_json     TEXT NOT NULL,
    capability_snapshot_hash     TEXT NOT NULL,
    handoff_source_phase         TEXT,
    handoff_source_version       INTEGER,
    handoff_source_hash          TEXT,
    prompt_parts_json            TEXT NOT NULL,
    prompt_hash                  TEXT NOT NULL,
    runtime_enforcement_json     TEXT NOT NULL,
    created_at                   INTEGER NOT NULL,
    FOREIGN KEY(workbench_id) REFERENCES workbench(id)
);
```

JSON 只承载不可独立查询的值对象列表；业务状态、引用、Hash 和并发 version 使用独立列。

## 6. 通用 ChatRun 增量字段

```text
chat_run
  + run_origin           TEXT NOT NULL DEFAULT 'CHAT'
  + origin_reference     TEXT
  + execution_context_id TEXT
```

- 历史行保持 `CHAT`；
- Workbench 行 `origin_reference=workbenchId:phase`，`execution_context_id=runId`；
- ChatRun Domain 使用 `RunOrigin` 值对象，不在 Application 对字符串做判断；
- Execution Plan Provider Registry 根据 origin 的策略对象分派，不在 Executor 写 switch。

## 7. 事务与幂等

### 7.1 创建

- 幂等唯一键建议 `(owner_id, idempotency_key)` 单独表或 Workbench 列；
- 首次保存 Snapshot、Workbench、Scope、Phase 同一事务；
- 同键同规范化输入返回已有 Workbench；同键不同输入返回 409。

### 7.2 Run 准备

- Run ID 在进入事务前生成；
- 同一 Phase 的 idempotency key 仍由 ChatRun 唯一约束；
- 事务内依次调用领域行为、保存 Run Snapshot、创建 ChatRun；
- 任意 Run 同时写入对应 Phase 的 `active_run_id`；`MODIFY` 还更新 Workbench 的 `active_write_run_id`，
  `WHERE version=? AND active_write_run_id IS NULL`；
- 提交后启动进程，启动失败也不回滚已提交业务事实，而是将 Run 置 FAILED。

### 7.3 乐观锁

Repository 更新必须包含：

```sql
UPDATE workbench
SET ..., version = version + 1
WHERE id = ? AND version = ?;
```

影响行数为 0 转换为领域并发异常。Handoff/Override 分别使用自己的 version，避免无关编辑互相冲突。

## 8. Ownership 与可见性

- 所有写命令先加载 Workbench 并调用 `requireOperableBy(actor)`；
- 列表 Query 默认 `owner_id=currentUser`；
- Admin Query 是独立接口和投影，不复用 Owner 身份；
- Admin 允许查看、停止异常 Run、执行对账，禁止发送 Phase 消息、修改 Handoff/Override 或批准操作；
- 不存在 Workbench 时和无权访问时均返回 404，避免枚举 ID。

## 9. 错误契约

| HTTP | code | 场景 |
| ---: | --- | --- |
| 400 | `WORKBENCH_REQUEST_INVALID` | DTO/格式错误 |
| 403 | `WORKBENCH_OPERATION_FORBIDDEN` | 已确认存在但动作策略拒绝的管理操作 |
| 404 | `WORKBENCH_NOT_FOUND` | 不存在或 Owner 无权访问 |
| 409 | `WORKBENCH_VERSION_CONFLICT` | 乐观锁冲突 |
| 409 | `WORKBENCH_WRITE_RUN_ACTIVE` | 已有写运行 |
| 409 | `WORKBENCH_PHASE_RUN_ACTIVE` | 当前 Phase 会话已有活动运行 |
| 409 | `WORKBENCH_PHASE_RESTART_INVALID` | 活动、未开始或人工完成状态下不能重开会话 |
| 409 | `WORKBENCH_PHASE_TRANSITION_INVALID` | 非法人工状态转换 |
| 410 | `WORKBENCH_ARCHIVED` | 已归档资源不可写 |
| 422 | `WORKBENCH_REPOSITORY_SCOPE_INVALID` | Repository Scope 业务输入错误 |

## 10. TDD 与验收

Domain 无 Mock：

- 四阶段构造、任意切换、人工完成/重开；
- Phase Conversation 代际、活动运行时拒绝重开、旧会话引用保留；
- 完成阶段不产生 PASS/Gate；
- Owner 与 Archive 规则；
- 第二个 MODIFY Run 被拒绝、终态释放租约、重复释放幂等；
- Handoff/Override/Run Snapshot 不变量。

Application 使用 Mockito：

- 创建保存顺序、事务提交后启动；
- 幂等短路不重复 Git 采集/创建 Run；
- Snapshot 保存失败不启动进程；
- 外部预检失败不产生半截 Run。

Infrastructure 使用真实 SQLite：

- Schema 约束、四阶段 restore、乐观锁、并发写租约；
- JSON Codec round-trip；
- 历史 `chat_run` additive migration；
- Workbench 删除/归档时的引用与保留策略。
