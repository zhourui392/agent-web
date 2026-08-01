# TD-08 高影响操作与 workspace 接入

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-02](td-02-workbench-domain-persistence.md)、[TD-04](td-04-multi-repository-workspace.md)
> @author alex

## 1. 目标与 MVP 边界

commit、push、deploy、production-write 必须通过类型化操作提案和单独人工决策，不能从 Agent 文本、阶段、
RunMode 或自然语言确认中推导授权。

MVP 默认完成：

- 类型化 Operation Proposal；
- 目标、风险、Repository/Branch/Environment 的只读预览；
- Approve/Reject/Expire 和审计；
- 执行器 Feature Flag 与 fail-closed 合同。

MVP 默认不自动开放真实执行器。commit、push、local deploy 可在独立验收后逐项启用；production-write 和正式
requirement-flow 绑定延后。批准一个尚未开放的操作只形成“已授权待外部/后续执行”事实，不会调用 shell。

## 2. Operation 类型

```text
GIT_COMMIT
GIT_PUSH
LOCAL_DEPLOY
PRODUCTION_WRITE
```

不得使用 `CUSTOM` 绕开策略。新增类型需要同时提供：Target 值对象、Policy、Preflight、Executor、Reconciler、
Redactor 和测试矩阵。

## 3. 领域模型

```text
HighImpactOperation
├── operationId
├── workbenchId / sourceRunId / phase
├── type
├── target
├── requestedPayloadHash
├── safeSummary
├── status
├── proposedBy / proposedAt
├── decision(actor, reason, decidedAt)
├── authorizationExpiresAt
├── preflightHash?
├── executionReference?
└── version
```

状态机：

```text
PROPOSED ──approve──> AUTHORIZED ──start──> EXECUTING ──> SUCCEEDED
    │                    │                    ├──────────> FAILED
    │                    │                    └──────────> RECONCILIATION_REQUIRED
    │                    ├──expire──────────> EXPIRED
    └──reject───────────> REJECTED
```

`AUTHORIZED` 不等于执行成功。Feature 未开放时不允许 `start`，Operation 可以保持 AUTHORIZED 供人工处理或
被显式关闭。

## 4. Target 值对象

### 4.1 Git Commit

```text
CommitTarget
├── repositoryKey
├── branch
├── expectedHead
├── expectedStateHash
├── includedPaths[]
└── messageHash / safe message preview
```

- includedPaths 必须属于一个已选仓库；
- 不允许 `git add .` 隐式包含未知文件；
- Commit Message 可以由用户编辑，实际文本在执行前再次展示；
- 不读取或提交 Git ignored Secret。

### 4.2 Git Push

```text
PushTarget
├── repositoryKey
├── remoteName
├── localBranch
├── remoteRef
├── expectedLocalHead
└── forceMode = FORBIDDEN（MVP）
```

Push 授权不继承 Commit 授权；禁止 force、删除远端 ref 和任意 refspec。

### 4.3 Local Deploy

```text
LocalDeployTarget
├── templateId / version / hash
├── repositoryTargets[]
├── environment = LOCAL
├── expectedWorkspaceStateHash
└── rollbackSummary
```

命令只来自管理员版本化模板，不接受 Agent/用户提交 shell 字符串。

### 4.4 Production Write

MVP 固定不可执行。模型保留独立类型是为了明确拒绝和后续 requirement-flow 接入，不得回退为普通 MCP 写。

## 5. Proposal 来源

可靠来源：

1. Agent 调用受控 `propose_high_impact_operation` Tool/MCP；
2. 用户点击固定 UI 操作入口；
3. workspace controller 返回类型化 Proposal。

不可靠来源：

- 正则扫描 Agent 文本；
- 命令字符串包含 `git push`；
- 进入 IMPLEMENT_TEST Phase；
- 用户在普通输入框说“同意”。

Runtime 捕获到未授权的真实高影响命令时必须阻止执行并发出 `operation_blocked` 安全事件，而不是事后补卡片。

## 6. 授权流程

```text
Operation Proposed
→ Application 加载 Workbench/Scope/Source Run
→ Domain OperationPolicy 校验提案可表达
→ 保存 PROPOSED
→ UI 展示类型、仓库、分支、环境、状态 Hash、风险和命令类别
→ Owner 明确 Approve/Reject + reason
→ 保存 AUTHORIZED/REJECTED
→ 若 Executor 已开放：重新 Preflight
→ preflightHash 与授权目标匹配
→ commit
→ afterCommit Launcher 启动
```

授权绑定 payload Hash；目标文件、HEAD、分支、模板或环境变化会让 Preflight 失败并使原授权 EXPIRED/FAILED，
不能弹窗后静默改目标。

## 7. Operation Policy

策略位于 Domain：

```text
HighImpactOperationPolicy
├── propose(type, targetProof, actor)
├── authorize(operation, actor, reason, now)
├── issueExecutionPermit(operation, preflightProof, now)
└── reconcile(operation, observedOutcome, now)
```

Application 只加载 Repository、调用 Gateway 获得技术 Preflight，再把 Proof 传给 Policy。Controller 不比较
branch/HEAD/Hash；Infrastructure 不判断“是否已获批准”。

## 8. Executor 端口

每种操作使用专门端口，不建通用 shell：

```text
GitCommitGateway
GitPushGateway
LocalDeploymentGateway
ProductionWriteGateway（MVP 无实现）
```

端口接收领域已签发的 Permit 和类型化 Spec。Infrastructure：

- 使用 `ProcessBuilder` token；
- Git 身份和 Credential 复用现有用户 Git 配置，但 Secret 只在启动边界解密；
- 不输出密码、Token、完整 remote URL credential；
- local deploy 只执行 allowlisted template；
- 真实副作用提交后启动，未知终态进入对账，不自动重放。

## 9. workspace requirement-flow 接入

后续正式模式使用防腐层：

```text
Workbench Application
→ RequirementFlowPort
→ infra.workspace.RequirementFlowAdapter
→ workspace controller / flow.json projection
```

边界：

- Workbench Phase HUMAN_COMPLETED 不写入 flow.json；
- 正式 Gate/PASS 只读取 workspace controller 投影；
- Workbench 不复制 requirement-flow 状态机；
- Operation 可以引用正式 Flow Command ID，但最终状态仍由 workspace 返回；
- Adapter 不把 Workbench Handoff 当正式 Artifact。

## 10. API

```http
GET /api/workbenches/{id}/operations
GET /api/workbenches/{id}/operations/{operationId}

POST /api/workbenches/{id}/operations/{operationId}/decision
If-Match: <operation-version>

{
  "decision": "APPROVE",
  "reason": "已核对目标仓库、分支和状态"
}
```

Approve 响应必须明确：

```json
{
  "status": "AUTHORIZED",
  "executionAvailable": false,
  "executionMode": "MANUAL_OR_DEFERRED",
  "authorizationExpiresAt": 0
}
```

不能用模糊“操作成功”描述授权成功。

## 11. 数据与审计

```sql
CREATE TABLE workbench_high_impact_operation (
    operation_id             TEXT PRIMARY KEY,
    workbench_id             TEXT NOT NULL,
    source_run_id            TEXT NOT NULL,
    phase                    TEXT NOT NULL,
    operation_type           TEXT NOT NULL,
    target_json              TEXT NOT NULL,
    requested_payload_hash   TEXT NOT NULL,
    safe_summary             TEXT NOT NULL,
    status                   TEXT NOT NULL,
    proposed_by              TEXT NOT NULL,
    proposed_at              INTEGER NOT NULL,
    decided_by               TEXT,
    decision_reason          TEXT,
    decided_at               INTEGER,
    authorization_expires_at INTEGER,
    preflight_hash           TEXT,
    execution_reference      TEXT,
    failure_code             TEXT,
    created_at               INTEGER NOT NULL,
    updated_at               INTEGER NOT NULL,
    version                  INTEGER NOT NULL
);
```

Target JSON 不含 Credential。审计 actor 永远是实际登录用户，Admin 不能伪装 Owner。

## 12. 测试

Domain 无 Mock：

- 每种 Target 不变量；
- 文本/阶段不能授权；
- payload Hash 绑定、过期、重复决策；
- preflight 变化拒绝 Permit；
- 未知终态不允许重放。

Application Mockito：

- 决策保存后才调用 Launcher；
- Feature 关闭不调用 Gateway；
- commit/push/deploy 使用不同端口；
- Owner/Admin 权限；
- workspace 防腐层不映射人工 Phase PASS。

Infrastructure：

- 临时 Git commit 精确 paths、push fixture、credential redaction；
- Deployment Template allowlist、token 化命令、超时与对账；
- 默认测试不访问真实 remote/生产。

Playwright：

- 提案卡片、目标详情、Approve/Reject、版本冲突；
- 未开放执行器明确显示；
- 普通聊天“请 push”不产生授权；
- 未授权命令被阻止并显示安全事件。

## 13. 验收标准

- 四类操作分别建模和授权；
- 默认没有真实自动 commit/push/deploy/production write；
- 授权绑定明确目标和 Hash；
- Runtime 无法从自然语言绕过；
- requirement-flow 接入不污染 Workbench 人工状态。
