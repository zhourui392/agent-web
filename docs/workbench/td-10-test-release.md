# TD-10 Dynamic Stage 测试与发布门禁

> 状态：按 TD-11 Stage-only 模型修订
> 日期：2026-08-05
> 前置：[技术设计总览](README.md)、[TD-11](td-11-dynamic-stages-global-context.md)
> @author alex

## 1. 结论

Workbench 没有需要保留的数据，验收只覆盖 Dynamic Stage 单模型。测试不得通过构造旧工作单元、旧 API、旧表或前端回退来证明已删除合同仍可使用；Stage-only Schema 对旧表“不存在”的断言属于删除合同，应保留。

完成口径由以下证据共同组成：

1. Java 领域、应用、接口和 SQLite 回归。
2. Owner-first 授权拒绝路径。
3. 前端 TypeScript、Lint、Build 和 Vitest。
4. Mocked Owner/Admin 浏览器合同。
5. 真实 Spring、真实临时 SQLite、Runtime Stub、SSE 的浏览器链路。
6. 独立 JVM 强杀与重启恢复。
7. 当前工作树的完整门禁，而不是历史测试数量。

## 2. TDD 门禁

Java 行为修改遵循红—绿—重构：

- 先确认业务规则归属，服务层出现聚合内部判断时先下沉领域模型。
- 授权、并发、幂等、路径、持久化和外部副作用必须先固定失败场景。
- 纯 Mockito 单测使用 `/fast-test`；SQLite、文件和 Spring 边界使用 Maven 对应测试。
- 不为保留已删除合同编写测试。

前端局部行为修改至少运行覆盖改动的 Vitest 与对应 TypeScript 检查；跨页面 Stage 主链使用 Playwright。

## 3. 领域层矩阵

### 3.1 Workbench 与 Stage

- `Workbench.create/restore` 要求非空、不可变的 Stage 集合。
- Stage Definition、Revision、Snapshot 和 Instance Identifier 唯一且精确绑定。
- Stage 可以独立导航、人工完成和重新打开，不存在默认上下游或自动流转。
- 每个 Stage 最多一个活动 Run；每个 Workbench 最多一个活动写 Run。
- Run 终态释放活动引用与写租约；上传附件转为 `RELEASE_PENDING`。
- Run Mode 只来自冻结 Snapshot 的 `allowedRunModes`。

### 3.2 Run 身份

必须固定以下唯一事实：

```text
originReference    = workbenchId + ":" + stageInstanceIdentifier
runOrigin          = WORKBENCH
sessionKind        = WORKBENCH_STAGE
executionContextId = runId
```

Snapshot、ChatRun、Session、Workbench 或 Origin 任一不匹配都失败关闭。

### 3.3 能力、Command 与 Prompt

- Stage 发布时归档精确 Command、Skill 和 MCP Artifact。
- Run 从冻结 Stage Snapshot 与 Artifact Registry 解析能力。
- Command 查询不重新扫描当前 Workspace。
- 未选 Command、Hash 不匹配、Skill 依赖不闭合或 Runtime 不兼容时拒绝。
- `runtimeCompatibility` 必须等于公共 Runtime 的唯一 Compatibility Matrix Version。
- Prompt 固定 Stage、Context、Workspace、History、Attachment 与 Capability Hash。

### 3.4 Attachment 与 Context

- 上传附件绑定 Owner、Workbench、Stage、Conversation Generation。
- 提交时验证 Hash、大小、媒体类型、存活期限和精确 Stage 身份。
- 当前尚无已发布 Global Context 文档时，唯一 Manifest 基线为 `contextVersion=0`、空文档列表和固定空提示。
- 未来 Context 文档读取仍需 Repository Scope、逻辑路径与 Published Hash 精确授权。

## 4. 应用层矩阵

### 4.1 Owner-first 授权

Run 读取、SSE、Stop、History 和 Capability 查询复用同一授权顺序：

```text
加载 Workbench
→ 校验 Owner
→ 解析 Run ID
→ 查询 Stage Run Snapshot
→ 查询 ChatRun
→ 校验精确 Stage Origin
```

错误 Owner、Workbench、Stage、Run、Run ID 或 Origin 对外统一为 Run 不存在；Owner 校验失败前不得访问 Snapshot 或 ChatRun Repository。

### 4.2 Run Preparation 与提交

- Availability 在任何业务读取前失败关闭。
- Preparation 冻结 History、空/已发布 Context Manifest、Development Context、Capability、Attachment、Workspace Snapshot 与 Runtime Preflight。
- Snapshot、Prompt、ChatRun、Workbench 租约和事件在同一事务提交。
- Runtime 只能在事务提交后启动。
- 幂等重试的 canonical request 包含 Stage Instance Identifier、Run Mode、消息、Command 和附件。
- 相同幂等键不同输入必须冲突，不能启动第二个 Runtime。

### 4.3 终态与恢复

- `SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED` 都释放 Stage Run 引用。
- 可写 Run 同时释放 Workbench 写租约。
- 终态提交成功后才记录 Stage-only Telemetry。
- 重启恢复不自动重放外部副作用；未知活动 Run 收口为明确中断终态。

## 5. 基础设施矩阵

### 5.1 SQLite

使用真实临时 SQLite 验证：

- Stage-only Schema 只创建当前表。
- `workbench_stage`、Stage Conversation、Stage Snapshot、Stage Prompt、Stage Attachment 与 Restart Receipt 映射正确。
- Workbench、Snapshot、Prompt、ChatRun 和事件事务原子提交。
- Owner/Admin Query 返回安全投影，且 Stage 按冻结顺序排列。
- Run Identifier、Stage Origin、Hash 或 JSON 损坏时失败关闭。

新库不得创建旧工作单元、交接、专属评审、能力覆盖、旧 Snapshot、旧 Attachment 或高影响操作表。

### 5.2 文件与 Runtime

- 文件测试使用 `@TempDir`，拒绝符号链接、路径逃逸和非普通文件。
- Runtime Stub 只能通过测试夹具调用，不使用真实 CLI 登录态。
- Preflight 校验 Runtime 类型、版本策略、Sandbox、Repository Root 数量与 Capability Binding Hash。
- Runtime Compatibility Matrix 必须与冻结 Binding 完全一致。

## 6. 接口矩阵

唯一在线 Stage Run/Command 路径：

```http
GET  /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/commands
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/runs
```

接口测试覆盖：

- Stage 生命周期、Conversation、Attachment、Run History、SSE 与 Stop。
- `If-Match`、Idempotency Key、参数上限、400/404/409/410/413/503。
- Owner 安全响应不泄漏绝对路径、Secret、完整命令、Prompt 或原始输出。
- Admin 只能查询安全投影、Stop 和单 Run Reconcile，不能代 Owner 提交 Run。
- 已删除路径不通过适配器复活。

## 7. 前端矩阵

- Detail、导航和本地状态只使用 `stages[]` 与 `stageInstanceIdentifier`。
- Stage Snapshot 的 `allowedRunModes` 驱动选择：唯一模式自动采用；双模式必须显式选择。
- 模式进入提交指纹，变化后旧失败请求不能重放。
- Stage Conversation、Attachment、SSE、History 和 Document 状态使用同一精确实例身份。
- 实时测试事件保留 Repository、Suite、Status 和安全摘要。
- Admin 列表和详情只展示 Stage 投影。
- 不读取旧 localStorage Key，不调用已删除接口。

## 8. Playwright

### 8.1 Mocked Owner

```bash
cd tests
npx playwright test -c playwright.workbench.config.ts
```

覆盖 Stage 导航、本地恢复、Run Mode、Attachment/History 和创建时 Published Stage 身份。

### 8.2 Mocked Admin

```bash
cd tests
npx playwright test -c playwright.admin-workbench.config.ts
```

覆盖未登录/普通用户拒绝，以及 Admin 安全投影、Stop 和 Reconcile 二次确认。

### 8.3 真实 Spring 边界

```bash
cd tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
npx playwright test -c playwright.workbench-real.config.ts
```

该配置使用独立端口和 `/tmp` 临时 SQLite，不清理或复用共享业务数据库。覆盖：

1. Stage Run 的 SQLite/SSE、刷新恢复与 Stop。
2. 关闭浏览器后后台 Run 继续，重新打开恢复终态和历史。
3. 多仓 Repository Scope 排除未选择 sibling。
4. 可写 Stage 持久化文件、受影响测试事件和刷新历史。

Runtime Stub 通过只能证明真实应用边界，不等于真实 Codex/Claude 验收。

## 9. Telemetry

保留 Stage-only 指标：

```text
workbenchCreated
runTerminal
writeConflict
sseReconnect
eventLag
capabilityResolution
capabilityVersionChanged
workspaceScopeViolation
documentRead
recoveryReconciliation
```

Run 指标只使用低基数 `mode/status`，不包含旧工作单元标签，也不加入高基数 Stage Instance Identifier。

## 10. 完整发布门禁

后端：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

前端：

```bash
cd frontend
npm run typecheck
npm run lint
npm run build
```

测试工程：

```bash
cd tests
npm run typecheck
npm test -- --run
```

然后执行第 8 节三套 Playwright，并显式运行 `WorkbenchRuntimeRestartRecoveryProcessIntegrationTest`。

## 11. 发布退出标准

- Dynamic Stage 单模型代码、Schema、API、前端和文档一致。
- 全部最小回归和完整门禁在同一候选工作树通过。
- 真实 Spring E2E 4/4、Mocked Owner 4/4、Mocked Admin 2/2。
- 独立 JVM 重启恢复通过；写租约、Runtime Handle 和附件终态可核对。
- 不修改业务本地数据库，不依赖旧数据转换。
- 无真实 CLI/真实用户试点时，交付必须明确该边界，不得把 Stub 结果描述为生产验收。
