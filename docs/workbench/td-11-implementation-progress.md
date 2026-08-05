# TD-11 Workbench Dynamic Stage 与全局上下文实施进度

> 状态：实施中，禁止标记完成
> 更新日期：2026-08-05
> 当前分支：`master`
> 权威设计：[TD-11 Workbench Dynamic Stage 与全局上下文](td-11-dynamic-stages-global-context.md)
> @author alex

## 1. 当前目标

Workbench 没有需要保留或转换的数据。本轮直接完成 Dynamic Stage 单模型切换，并关闭后端、前端、Schema、测试与文档中的固定工作单元残留。

当前交付范围包括：

- Stage Catalog、Draft、Published Revision、Disable 与 Catalog Version；
- 创建时选择 Definition Identifier，服务端排序并冻结 Stage Snapshot；
- Stage Conversation、Attachment、Run、History、SSE、Stop；
- Stage-only SQLite Repository 与 Query；
- Capability Source、不可变 Artifact Registry、Run Binding；
- Owner-first Run 授权；
- Admin Stage Catalog、查询、停止和对账；
- Stage-only 前端与浏览器链路；
- Runtime 终态和 Server Restart Recovery；
- Global Context 的确定性空 Manifest 基线。

完整 Global Context 主链仍未实现，因此 TD-11 不能标记完成。

## 2. 不可回退的架构决策

### 2.1 唯一领域模型

Workbench 聚合只有非空 `WorkbenchStageState` 集合。唯一构造与恢复入口为：

```java
Workbench.create(..., List<WorkbenchStageState> stages, Instant now)
Workbench.restore(..., List<WorkbenchStageState> stages,
                  WorkbenchStageRunReference activeWriteRunReference, ...)
```

不得重新加入固定工作单元集合、模型判别方法、迁移构造器、旧接口请求适配、旧表转换或旧浏览器状态恢复。

### 2.2 Catalog 与 Snapshot

- `WorkbenchStageCatalog` 管理 Definition、Draft、Published Revision、Disable 和 Catalog Version；
- Published Revision 不可变；
- `sequenceNumber` 随 Revision 发布；
- Workbench 创建请求只提交 Definition Identifier 集合与 Catalog Version；
- 后端按 Published Sequence 排序并冻结 Snapshot；
- 管理端后续发布、停用或重排不影响已有 Workbench；
- Stage 之间没有前置、后置、Gate、默认上游或自动流转。

### 2.3 Capability

Capability Source、当前 Catalog、Published Revision、Workbench Stage Snapshot 和 Run Binding 是不同边界。

Stage 发布时归档精确 Command、Skill、MCP 内容、版本、业务内容 Hash 与 Payload Hash。Run 只通过 Stage Snapshot 和 Artifact Registry 解析；不得回退到当前 Workspace 扫描。Artifact 丢失、Hash 不匹配或 Runtime 版本不支持时失败关闭。

### 2.4 Run 身份与授权

```text
originReference    = workbenchId + ":" + stageInstanceIdentifier
runOrigin          = WORKBENCH
sessionKind        = WORKBENCH_STAGE
executionContextId = runId
```

Owner Run 访问固定顺序：

```text
加载 Workbench
→ 校验 Owner
→ 解析 Run ID
→ 查询 Stage Run Snapshot
→ 查询 ChatRun
→ 校验 exact Stage Origin
```

Owner、Workbench、Stage、Run、Run ID 或 Origin 错误，对外统一为 Run 不存在。Owner 失败前不得访问 Snapshot 或 ChatRun Repository。

### 2.5 Run Mode 与 Runtime Compatibility

- `allowedRunModes` 来自 Workbench 内冻结的 Stage Snapshot；
- 唯一模式由客户端自动采用；两种模式必须显式选择；
- Run Mode 进入提交指纹和幂等重试判断；
- 不按 Stage 名称猜测模式，不从当前 Catalog 重新读取；
- `ResolvedCapabilityBinding.runtimeCompatibility` 只使用 `WorkbenchRunPreparationSettings.getRuntimeCompatibility()`；
- 该值与公共 `CodexRuntimeCompatibilityMatrix` 的唯一版本一致；
- 不增加多个版本标识的接受分支。

### 2.6 终态与外部副作用

Run 终态在事务内释放 Stage 活动引用；写 Run 同时释放 Workbench 写租约；Stage 上传附件进入 `RELEASE_PENDING`。事务提交后才记录 Stage-only Telemetry。

Run Submission 同样只在持久化事务提交后启动 Runtime。Server Restart Recovery 不自动重放 Runtime 或外部命令。

### 2.7 Schema 与在线 API

新库只创建 Stage Catalog、Workbench Stage、Stage Conversation、Stage Run Snapshot、Prompt Payload、Stage Attachment、Restart Receipt、创建 Receipt 与 Admin Audit 等表。

在线 Run 创建与 Command 路径只有：

```http
GET  /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/commands
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/runs
```

其余在线能力同样使用 Stage 身份：生命周期、Conversation、Restart、Attachment、History、SSE、Stop、Admin 查询、停止和对账。

### 2.8 高影响命令

Workbench 不提供类型化高影响操作 Proposal、Approval 或 Execution API。Runtime Command Policy 在副作用前拒绝 commit、push、本地部署、生产写入及其绝对路径、Shell Wrapper、复合命令或别名表达。

`MODIFY_WORKSPACE` 只允许冻结 Repository Scope 内的普通文件修改。

## 3. 已完成实现

### 3.1 Stage Catalog 与 Capability

- Dynamic Stage Definition、Draft、Published Revision、Disable、Sequence、Catalog Version 已实现；
- Admin Capability Source 与 Stage Catalog API 已实现；
- Command、Skill、MCP 独立发现、校验、归档与内容寻址已实现；
- Workbench 创建时按服务端 Published Sequence 冻结 Stage Snapshot；
- Command 查询从 Workbench Stage Snapshot 与 Artifact Registry 解析；
- Stage Run Capability Binding 冻结精确内容和 Hash；
- Runtime Compatibility 已改为从公共设置显式传入 Resolver。

### 3.2 聚合、Persistence 与 API

- Workbench 聚合已收敛为非空 Stage 集合；
- Stage 生命周期、Conversation Generation、活动 Run 与单写租约由聚合守护；
- SQLite Schema、Repository 和 Query 已收敛到 Stage-only；
- Stage Conversation、Restart、Attachment、Run、History、SSE、Stop 已实现；
- Admin Workbench 查询、Run Stop 与 Reconcile 已实现；
- Run Snapshot、Prompt Payload、Attachment Binding 和创建/重启幂等 Receipt 已持久化。

### 3.3 Owner-first 授权

`WorkbenchRunAccessResolver` 当前顺序为：

```java
Workbench workbench = requireOwned(actor, workbenchId);
ChatRunId runId = parseRunId(runIdValue);
snapshotRepository.findByRunId(...);
runRepository.findById(...);
snapshot.requireExactRun(workbench, run, ...);
```

测试已覆盖：

1. 非 Owner 在任何 Run Repository 查询前失败；
2. 畸形 Run ID 在 Owner 校验后失败；
3. Snapshot、ChatRun 与 Stage Origin 全部精确匹配时授权；
4. 错 Stage Origin 对外表现为 Run 不存在。

### 3.4 Run 终态

当前终态处理已完成：

- 释放 Stage 活动 Run；
- 写 Run 释放 Workbench 写租约；
- Stage Attachment 转为 `RELEASE_PENDING`；
- 提交后才记录 Stage-only Telemetry；
- 恢复流程不自动重放外部副作用；
- Prometheus 合同不包含固定工作单元或高基数 Stage 标签。

### 3.5 前端

- 创建页和 Workbench 页以服务端 `stages[]` 为唯一事实；
- Stage Instance Identifier 贯穿 Conversation、Command、Attachment 与 Run 请求；
- `allowedRunModes` 驱动模式自动选择或显式选择；
- Run Mode 进入失败重试指纹；
- Run History、SSE 和事件 Envelope 为 Stage-only；
- 实时测试事件已投影 `testStatus`、`repositoryKey` 与稳定 `data-test`；
- 真实 E2E 文件事件使用结构化文档引用稳定定位器。

### 3.6 删除范围

已删除固定工作单元 Run、Conversation、Attachment、Handoff、Review、Capability Override/Profile、Admin Phase Capability 和类型化高影响操作的生产入口与持久化模型。

内置 Capability 用例标签统一为 `WORKBENCH_STAGE`。Stage Runtime 不再生成私有版本字符串。

## 4. 当前验证证据

### 4.1 完整门禁

| 边界 | 结果 |
| --- | --- |
| 完整 Maven 默认测试集 | 通过 |
| Frontend typecheck | 通过 |
| Frontend lint | 通过 |
| Frontend production build | 通过 |
| Tests typecheck | 通过 |
| 完整 Vitest | 40 files / 344 tests 通过 |
| Mocked Workbench E2E | 4/4 通过 |
| Mocked Admin E2E | 2/2 通过 |
| 真实 Workbench E2E | 4/4 通过 |
| 独立 JVM Restart Recovery | 通过 |

真实 Workbench E2E 使用独立 `18109` 后端、`5186` 前端、临时 SQLite 和 Runtime Stub，没有调用真实 CLI，也没有修改共享业务数据库。测试主动断开 SSE 时出现的 Tomcat `Broken pipe` 未导致用例失败。

### 4.2 聚焦回归

| 边界 | 结果 |
| --- | --- |
| Runtime Compatibility Fast Test | 6/6 通过 |
| Owner-first 授权 Fast Test | 4/4 通过 |
| 实时测试事件组件 Vitest | 12/12 通过 |
| 相关前端回归 Vitest | 5 files / 60 tests 通过 |
| 目标后端 Maven 测试集合 | 通过 |

目标后端集合覆盖 Artifact Catalog、Runtime Capability Materializer、Agent Process Kernel、Stage Run Stream Projection、Submission Committer、Prompt Composer、真实 SQLite Submission Transaction、Dynamic Stage 聚合和 Admin Auth Filter。

### 4.3 恢复与附件证据

`WorkbenchRuntimeRestartRecoveryProcessIntegrationTest` 已在打开 `process-integration` 分组后通过，验证：

- 未知写 Run 恢复为 `INTERRUPTED / SERVER_RESTARTED`；
- Runtime Handle 删除；
- Stage 活动 Run 与 Workbench 写租约释放；
- 旧 Runtime 不被重放；
- 恢复后只启动一次新的写 Run。

该进程测试不上传附件。附件的终态合同由同一终态参与者的 `WorkbenchChatRunTerminalParticipantTest` 覆盖：Run 首次终态时绑定附件转为 `RELEASE_PENDING`，持久化完成后才发送 Telemetry。该测试已包含在完整 Maven 绿色结果中。

## 5. 尚未完成

### 5.1 完整 Global Context

当前只有确定性空 Manifest：

```text
contextVersion = 0
documents      = []
promptContent  = Context version: 0
                 No published documents.
```

仍需实现：

- Workbench Context 聚合；
- Context Document 与不可变 Revision；
- Agent Candidate 与 Owner 确认；
- Context Repository 和 SQLite Schema；
- Owner Context API；
- Published Hash 与 stale 刷新；
- 受 Run Binding 约束的 Document Gateway 正文读取；
- Context Manifest 版本化与 Prompt 冻结；
- 前端 Context Drawer；
- 授权、并发、幂等、路径和真实 SQLite 回归测试。

### 5.2 自动化门禁状态

当前工作树的完整工程、浏览器、独立 JVM 恢复和目标残留扫描均已完成。扫描只剩以下 Stage-only 删除合同：

- Schema 测试断言固定工作单元表和 Session Kind 不存在；
- Workbench E2E 断言不会生成 `/phases/` 路径；
- Admin 动态 Stage 页面断言固定 Review 枚举不出现。

`CapabilitySourceConfiguration` 与 `WorkbenchCapabilityConfiguration` 是当前 Stage Artifact 和 Runtime Binding 的有效能力配置边界，不属于已删除的 Workbench Capability Override/Profile。

## 6. 后续实施

下一阶段只实施第 5.1 节的完整 Global Context。开始 Java 业务修改前继续使用最小失败测试固定 Owner 授权、Published Hash、路径、并发、幂等和 Run Binding，再按领域聚合、应用编排、SQLite 适配、接口、前端顺序推进。

Global Context 实施完成后必须重新运行本文第 4.1 节全部门禁；本轮绿色结果不能替代之后代码变化的验证。

## 7. 工作区与回滚说明

- 当前工作树包含用户修改和 TD-11 修改，所有变更均未提交；
- 禁止整体恢复、清理或覆盖工作树；
- 回滚前必须先查看目标文件差异，再用最小补丁精确处理；
- 不恢复固定工作单元、Handoff、Review、Capability Override/Profile、旧 Snapshot、旧 Attachment 或旧浏览器状态；
- 不让 Command 查询回到当前 Workspace 扫描；
- 不通过修改业务本地数据库规避测试；
- 不主动 commit、push、package、restart 或部署。

## 8. 状态判定

Dynamic Stage 单模型主链已收敛，Owner-first、Runtime Compatibility、终态、前端实时事件、真实可写链、完整工程门禁和重启恢复均有当前工作树绿色证据。

当前仍为“实施中”的唯一功能缺口是完整 Global Context 聚合、API、持久化、授权和前端尚未实现。该缺口关闭并重新通过完整门禁前，不得标记 TD-11 完成或发布 Ready。
