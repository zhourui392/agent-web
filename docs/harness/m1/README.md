# Harness M1 完成记录

> 状态：Completed，允许进入 M2  
> 完成日期：2026-07-23  
> Feature Flag：`agent.harness.enabled=false`

## 1. 结论

M1 已完成独立 Harness 限界上下文的控制平面：`HarnessRun` 聚合负责四阶段顺序、唯一可写 Attempt、Gate、Artifact 版本、Approval Hash 绑定、Retry 留痕、取消和上游变化后的下游失效。Application 仅负责编排事务、Repository、Artifact Store 和路径授权端口。

M1 不调用真实 Agent、MCP、构建或部署。Prompt Pack、Skill 和 Capability Snapshot 属于 M2；Codex Runtime Adapter 属于 M3。当前在线 Codex 凭据 HTTP 401 仍是 M4 真实需求验收前置条件，不阻塞 M1 完成。

## 2. 交付物

| 交付物 | 位置 | 结果 |
| --- | --- | --- |
| Run/Stage/Attempt 聚合与状态机 | `src/main/java/com/example/agentweb/domain/harness/` | 已完成 |
| Stage Contract 1.0.0 领域值对象 | `StageContract` | 与 M0 固定四阶段合同一致 |
| Artifact/Gate/Approval/Audit 模型 | `domain/harness` | 已完成 |
| 写侧 Repository 端口 | `HarnessRunRepository` | 位于 Domain |
| SQLite Repository | `infra/harness/SqliteHarnessRunRepository` | 完整聚合恢复 + 乐观锁 |
| Artifact 正文存储 | `infra/harness/FileSystemArtifactStore` | 原子写、SHA-256、路径逃逸防护 |
| CQRS 详情投影 | `HarnessRunQueryService` / `SqliteHarnessRunQueryService` | DTO 投影，不返回半截聚合 |
| 管理 API | `interfaces/HarnessController` | 创建、查看、开始、Artifact、Gate、审批、拒绝、重试、取消 |
| Feature Flag | `agent.harness.enabled` | 默认 `false` |
| ADMIN 闸门 | `/api/harness` | 已纳入 `AdminAuthFilter` |
| 幂等 Schema | `src/main/resources/schema.sql` | 仅新增 Harness 表，不修改 Workflow 语义 |
| 纵向样例 | `HarnessFlowTest` | HTTP → Domain → SQLite → Artifact Store 通过 |

## 3. 已冻结的 M1 领域规则

- 四阶段固定为 `ANALYSIS → DESIGN → IMPLEMENTATION → DEPLOYMENT`。
- 前一阶段未 `PASSED` 时，后一阶段不能启动。
- 一个 Run 同时最多存在一个可写 Attempt。
- Retry 创建新 Attempt，旧 Attempt、Gate、Artifact 和事件不覆盖。
- Artifact 正文和元数据不可原地覆盖；同一逻辑 Artifact 使用稳定 ID 和递增版本。
- Gate 与 Approval 绑定当前 Attempt 的 Artifact 基线 SHA-256。
- Artifact 基线改变后，旧 Approval 失效；修订上游阶段时，已有下游结果标记 `INVALIDATED`。
- `COMPLETED`、`CANCELLED`、`ROLLED_BACK` 视为普通动作不可修改的终态。
- 取消尚未开始的 Run 不伪造 Attempt，重启后仍可恢复取消状态。
- 非法状态转换统一由 Domain 抛出 `IllegalHarnessTransitionException`。

M1 尚未激活 `WAITING_INPUT`、回滚和外部 Runtime 对账命令；这些状态为后续纵向能力保留，不计入本阶段已实现命令集合。

## 4. 存储与恢复

SQLite 控制面新增以下表：

```text
harness_run
harness_stage_execution
harness_stage_attempt
harness_artifact
harness_gate_result
harness_approval
harness_event
```

Artifact 正文默认写入 Git 忽略的 `data/harness/artifacts`，可通过 `AGENT_HARNESS_ARTIFACT_ROOT` 覆盖。物理路径只使用 Run ID 和 Artifact ID 的 SHA-256，不直接拼接外部 ID。Descriptor 的大小或 SHA-256 与正文不一致时拒绝写入；读取时再次校验，能够发现落盘后篡改。

在 POSIX 文件系统上，Artifact Store 会把受控目录收紧为 `700`、正文文件收紧为 `600`；非 POSIX 平台保留平台 ACL 行为。

Repository 使用聚合版本做乐观锁。读取时恢复 Stage Contract、Stage、全部 Attempt、Artifact 元数据、Gate、Approval 和事件。Schema 使用 `CREATE TABLE/INDEX IF NOT EXISTS`，重复初始化通过测试。

## 5. 管理 API

所有路径默认关闭，并额外要求数据库 `ADMIN` 角色：

```text
POST /api/harness/runs
GET  /api/harness/runs/{runId}
POST /api/harness/runs/{runId}/stages/{stage}/start
POST /api/harness/runs/{runId}/stages/{stage}/artifacts
POST /api/harness/runs/{runId}/stages/{stage}/gates
POST /api/harness/runs/{runId}/stages/{stage}/request-approval
POST /api/harness/runs/{runId}/stages/{stage}/approve
POST /api/harness/runs/{runId}/stages/{stage}/reject
POST /api/harness/runs/{runId}/stages/{stage}/retry
POST /api/harness/runs/{runId}/cancel
```

创建、开始和重试要求 `Idempotency-Key`。创建请求按“创建者 + 幂等键”持久化去重；同一个键对应不同创建基线时返回冲突。开始和重试把幂等键固化到 Attempt，重复命令不创建新 Attempt。

## 6. M1 退出门禁证据

| 门禁 | 证据 |
| --- | --- |
| 合法/非法状态转换 | `HarnessRunTest`、`HarnessRunInvalidTransitionTest`、`StageContractTest` |
| Application 无业务状态判断 | `HarnessAppServiceImplTest` + `ArchitectureTest` |
| SQLite 重启恢复和幂等迁移 | `SqliteHarnessRunRepositoryTest` |
| Artifact Hash、原子写和路径安全 | `FileSystemArtifactStoreTest` |
| CQRS 投影 | `SqliteHarnessRunQueryServiceTest` |
| 参数、状态码、幂等键、错误映射 | `HarnessControllerTest` |
| Feature Flag 关闭 | `HarnessFeatureFlagTest` + 默认完整后端回归 |
| ADMIN 401/403/放行 | `AdminAuthFilterTest` |
| 与 Workflow 隔离 | `ArchitectureTest` A5/A6 |
| API 纵向样例 | `HarnessFlowTest` |

完整命令和执行边界见 [`test-report.md`](test-report.md)。

## 7. 回滚方式

运行期回滚只需保持或恢复：

```yaml
agent:
  harness:
    enabled: false
```

关闭后 Harness Controller、Application Service、Repository、QueryService、Artifact Store 和 ID Generator 不注册，不会创建 Runtime 执行。新增表可以保留，Feature Flag 关闭不要求破坏性删表。Artifact 正文也不自动删除，避免丢失审计证据。

## 8. 下一步

进入 M2：实现四阶段 Prompt Pack、可信 Skill Catalog、授权解析和不可变 Capability Snapshot。M2 必须复用本次 Stage Contract 和 Artifact/Approval Hash 语义，不把能力选择分支写回 Application。
