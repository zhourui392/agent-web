# Dynamic Stage Workbench 发布就绪快照

> 状态：Not Ready，TD-11 实施中
> 快照更新：2026-08-05
> 适用模型：Dynamic Stage 单模型
> 权威设计：[TD-11](td-11-dynamic-stages-global-context.md)
> @author alex

## 1. 当前结论

Workbench 没有需要转换的数据，本次直接采用 Dynamic Stage 单模型。固定工作单元、交接、专属评审、能力覆盖和类型化高影响操作已退出在线合同；Schema、领域、API 与前端只读取 Stage 事实。

当前候选工作树已通过完整 Maven、Frontend、Vitest、Mocked E2E、真实 Spring E2E 和独立 JVM 重启恢复门禁。Dynamic Stage 主链的发布阻断已关闭。

TD-11 仍不能标记 Ready，因为 Global Context 完整聚合、Owner API、Candidate、持久化、Published Hash 授权和前端 Drawer 尚未实现；当前只有确定性空 Manifest。真实 CLI、真实用户试点和生产运维验收也不在自动化 Runtime Stub 证据内。

## 2. 架构基线

### 2.1 单一 Workbench 模型

Workbench 聚合只保存非空 Stage 集合。新库只创建 Stage 表；不存在固定工作单元恢复、模型判别、迁移工厂或旧浏览器键值恢复。

### 2.2 单一 Run 身份

```text
originReference    = workbenchId + ":" + stageInstanceIdentifier
runOrigin          = WORKBENCH
sessionKind        = WORKBENCH_STAGE
executionContextId = runId
```

### 2.3 Owner-first 授权

```text
Workbench → Owner → Run ID → Stage Snapshot → ChatRun → exact Stage Origin
```

错误 Owner、Workbench、Stage、Run、Run ID 或 Origin 对外统一为 Run 不存在。Owner 失败前不读取 Snapshot 或 ChatRun Repository。

### 2.4 冻结能力与模式

- Command 来自冻结 Stage Snapshot 与 Artifact Registry；
- Runtime Compatibility 使用公共 Runtime 的唯一 Matrix Version；
- Run Mode 来自冻结 `allowedRunModes`，Stage 名称不承载权限；
- 每个 Stage 单活动 Run，每个 Workbench 单活动写 Run；
- Artifact 缺失、Hash 不匹配或 Runtime 版本不支持时失败关闭。

### 2.5 当前 Context 基线

```text
contextVersion = 0
documents      = []
promptContent  = Context version: 0
                 No published documents.
```

该基线只表示当前没有已发布 Context Document，不从其他模型或 Workspace 扫描补齐内容。

## 3. 当前自动化证据

以下结果均来自 2026-08-05 当前候选工作树：

| 边界 | 当前结果 | 说明 |
| --- | ---: | --- |
| Maven 默认完整测试集 | 通过 | Java 21，退出码 0 |
| Frontend typecheck | 通过 | `tsc --noEmit` |
| Frontend lint | 通过 | ESLint 退出码 0 |
| Frontend production build | 通过 | Vite build 退出码 0 |
| Tests typecheck | 通过 | 测试工程 TypeScript |
| 完整 Vitest | 40 files / 344 tests | 全部通过 |
| Mocked Owner E2E | 4/4 | Stage 导航、Run Mode、Attachment/History、创建 |
| Mocked Admin E2E | 2/2 | 鉴权、安全投影、Stop/Reconcile |
| 真实 Spring E2E | 4/4 | SQLite、SSE、刷新恢复、多仓 Scope、真实文件与测试事件 |
| 独立 JVM Restart Recovery | 通过 | Process Integration 退出码 0 |

真实 Spring E2E 使用独立 `18109` 后端、`5186` 前端、临时 SQLite 与 Runtime Stub，不调用真实 Codex/Claude，也不修改共享业务数据库。测试主动断开 SSE 时 Tomcat 记录 `Broken pipe`，但全部业务断言通过。

## 4. 已关闭的发布风险

| 风险 | 当前控制与证据 |
| --- | --- |
| Runtime Preflight 503 | Capability Binding 使用 `WorkbenchRunPreparationSettings.runtimeCompatibility`，与公共 Matrix ID 一致 |
| Stage 名称推断权限 | 只读取 Workbench 内冻结的 `allowedRunModes` |
| 当前 Workspace 能力漂移 | Stage 发布归档 Artifact，Run 不重新扫描 Workspace Command |
| Owner 枚举 Run | Owner 校验在 Run ID 解析与任何 Run Repository 查询之前 |
| 错 Origin 暴露 | Snapshot 与 ChatRun exact Stage Origin 校验，统一投影为不存在 |
| 多仓越界 | Run 冻结 Repository Scope，真实 E2E 排除未选择 sibling |
| 关页取消后台 Run | 浏览器关闭不发送取消；重新打开恢复同一持久化终态 |
| Run 终态租约 | Stage 活动引用与 Workbench 写租约在终态释放 |
| Attachment 生命周期 | 首次终态把绑定附件转为 `RELEASE_PENDING`，Repository 回归通过 |
| Server Restart | `INTERRUPTED / SERVER_RESTARTED`，删除 Handle、释放引用和租约，不重放 Runtime |
| 非 Stage Schema 被创建 | Stage-only SQLite 测试断言被删除表和 Session Kind 不存在 |
| 高影响副作用 | Runtime Command Policy 在进程启动前失败关闭；无操作批准 API |

## 5. Telemetry 合同

当前保留：

```text
workbenchCreated
runTerminal(mode, status, duration)
writeConflict
sseReconnect
eventLag
capabilityResolution
capabilityVersionChanged
workspaceScopeViolation
documentRead
recoveryReconciliation
```

不使用固定工作单元标签，不加入高基数 Stage Identifier，不保留交接冲突或类型化操作指标。`runTerminal` 只在事务提交后记录。

## 6. 重启恢复证据

`WorkbenchRuntimeRestartRecoveryProcessIntegrationTest` 已显式打开 `process-integration` 分组运行并通过：

- 首个写 Run 与 Runtime Handle 在强制终止前已持久化；
- 第二个 JVM 启动后 Run 为 `INTERRUPTED / SERVER_RESTARTED`；
- Runtime Handle、Stage 活动 Run 和 Workbench 写租约均被释放；
- Runtime 调用次数保持 1，证明旧执行未重放；
- 随后提交的新写 Run 成功，Runtime 调用次数精确变为 2。

该进程用例没有上传附件。附件 `RELEASE_PENDING` 由同一首次终态参与者的应用层测试与 SQLite Repository 测试直接覆盖，并包含在完整 Maven 绿色结果中。

## 7. 唯一未完成的 TD-11 功能边界

完整 Global Context 仍需实现：

- Workbench Context 聚合；
- Context Document、不可变 Revision 和 Candidate；
- Owner 发布、刷新、撤回与幂等 API；
- Context Repository 与 SQLite Schema；
- Published Hash、stale 和 Repository Scope 授权；
- 受 Run Binding 约束的 Document Gateway 正文读取；
- Context Manifest 版本化与 Prompt 冻结；
- 前端 Context Drawer；
- 授权、并发、幂等、路径、真实 SQLite 与浏览器测试。

该边界完成后必须重新执行第 3 节全部门禁。

## 8. 残留扫描结论

生产代码和前端已无固定工作单元、Handoff、Workbench Review、Capability Override/Profile、旧 Run Snapshot、旧 Attachment、固定 Runtime 标识或旧 API 路径。

当前扫描命中只允许是删除合同的负向断言：

- Stage-only Schema 测试断言固定工作单元表和 Session Kind 不存在；
- Workbench E2E 断言不会请求 `/phases/`；
- Admin 动态 Stage 页面断言固定 Review 枚举不出现。

`CapabilitySourceConfiguration` 与 `WorkbenchCapabilityConfiguration` 是 Dynamic Stage Artifact 与 Runtime Binding 的有效配置，不是 Workbench 级能力覆盖。

## 9. 回滚与签署

- 不修改共享业务数据库来规避测试；新 Schema 只面向空库；
- 不提供应用内 Phase 数据转换或模型回滚；候选版本不可发布时回滚应用制品，不把固定工作单元合同加回当前代码；
- 所有变更仍未提交，回滚必须按文件查看差异后使用最小补丁；
- Runtime Stub、MockMvc、Vitest 或静态检查不能替代真实 CLI、真实用户或生产运维验收；
- Global Context 完整边界未关闭前，状态保持 `Not Ready`。
