# TD-03 阶段 ChatRun 与 SSE

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-01](td-01-runtime-capability-decoupling.md)、[TD-02](td-02-workbench-domain-persistence.md)
> @author alex

## 1. 目标

让四个 Phase 拥有独立会话，并复用现有 ChatRun 的幂等、停止、事件保留和可恢复 SSE。Workbench 不复制
第二套 Run 状态机，也不把 Phase、Capability 和 Repository Scope 规则塞进 ChatRun 聚合。

## 2. 复用边界

保留：

- `ChatRun` 的 `PENDING → RUNNING → terminal` 生命周期；
- `ChatRunEvent` 的单 Run 单调序列；
- `Last-Event-ID`、`after`、heartbeat 和 410 过期语义；
- `ChatToolInvocation` 结构化投影；
- `ChatRunRecoveryService` 的“不自动重放”原则；
- Provider Agent Runtime Registry 与可用性检查。

调整：

- `ChatRun` 增加中性 `RunOrigin` 和 `ExecutionContextReference`；
- 提交协调器不再只接受 Chat 页面参数；
- `ChatRunExecutor` 通过 `ExecutionPlanProvider` 解析来源，而不是硬编码 Chat Prompt；
- Workbench Controller 使用嵌套授权 API，内部复用 Run 服务；
- Tool/File/Test 事件带 Repository Key。

不复用：

- Chat Feedback、Share、Recall、Slash Command 默认行为；
- Chat 页面工作目录作为唯一 Runtime Root 的假设。

## 3. Phase Session

每个 Workbench 在创建时生成四个 Phase 元数据，会话可懒创建：

```text
用户第一次打开/发送某 Phase
→ WorkbenchPhase.requireOrPlanConversation()
→ Application 创建普通持久化 Conversation Session
→ Workbench.bindConversation(phase, sessionId)
```

会话属性：

- `sessionKind=WORKBENCH_PHASE`；
- `contextId=workbenchId:phase`；
- `workingDir=primaryRepositoryRoot` 仅作为 Provider 主目录事实，不代表完整授权范围；
- Agent Type/Environment 来自 Workbench；
- 不开放普通 Chat Share/Feedback/Recall 入口；
- Phase “重新开始会话”创建新的 session generation，不删除旧会话或其他阶段；新会话不默认复制旧消息。

`PhaseConversationReference` 只保存 sessionId。Workbench Domain 不依赖 `domain.chat.ChatSession`。

### 3.1 重新开始 Phase 会话

```text
用户确认重新开始
→ 校验 Phase 无活动 Run、Workbench ACTIVE、Phase 已 reopen/进行中
→ 创建新 Session
→ Workbench.restartConversation(...) 递增 generation 并替换当前引用
→ 旧 Session 标记 retired，保持只读查询
```

该动作不删除 Handoff，不自动改变人工完成状态，也不从旧 Session 复制消息；人工完成 Phase 必须先显式
reopen，避免一个动作同时隐藏两个业务决定。

## 4. 通用提交协调器

将现有提交职责拆分：

```text
ConversationRunSubmissionCoordinator
├── 校验幂等、容量和 session 单活跃
├── 追加用户消息
├── 创建 ChatRun
├── 追加初始 run_status event
└── afterCommit 启动

RunSubmissionPreparationProvider
├── ChatSubmissionPreparationProvider
└── WorkbenchSubmissionPreparationProvider
```

Workbench Provider 负责在进入通用提交事务前准备：

- Owner 与 Phase；
- 当前 Phase 无活动 Run，Workbench 无第二个活动写 Run；
- Handoff Reception；
- Capability/Profile/Override；
- Repository Scope 与 Runtime Preflight；
- `WorkbenchRunSnapshot` Candidate；
- RunMode 与写租约要求。

通用协调器只消费完整 Preparation，不重新解释业务 getter。

## 5. Execution Plan Provider

```java
public interface ExecutionPlanProvider {
    boolean supports(RunOrigin origin);
    AgentExecutionPlan prepare(ChatRun run);
}
```

Registry 在 Spring 装配期验证每个已注册 Origin 恰好一个 Provider，运行时按映射取值。业务服务不写
`if/switch`。Provider 只是读取已持久化事实并组装 Plan：

- Chat Provider：沿用历史消息、resume、PromptAssembly/Recall；
- Workbench Provider：读取 WorkbenchRunSnapshot、Phase 会话历史、Capability Binding 和 Repository Layout。

## 6. Prompt 与历史

Workbench 单轮 Prompt 固定顺序：

```text
平台强制安全规则
→ Environment Guardrail
→ Repository Scope Map 与 RunMode
→ 当前 Phase Rules
→ 已解析 Skill/MCP 使用说明
→ 已接受的上游 Handoff 版本
→ 受控 Workspace Context
→ 当前 Phase 会话历史
→ 当前用户消息
→ 输出/可观察事件要求
```

- 用户问题只注入一次；
- 不复制上游完整聊天和工具输出；
- History 只来自当前 Phase Session；
- Snapshot 保存每个 Prompt Part 的 type/source/hash/size 和 final prompt hash；
- Secret Redactor 扫描后才能把可展示摘要写 Event/API。

## 7. RunMode

```text
DISCUSS_READ_ONLY
MODIFY_WORKSPACE
```

| Phase | 默认 | 允许的显式模式 |
| --- | --- | --- |
| REQUIREMENT_ANALYSIS | DISCUSS_READ_ONLY | 只读 |
| SOLUTION_DESIGN | DISCUSS_READ_ONLY | 只读 |
| IMPLEMENT_TEST | MODIFY_WORKSPACE | 只读或写 |
| REVIEW_REFACTOR | DISCUSS_READ_ONLY | 只读；人工点击“执行已确认重构”后可写 |

PhaseRunPolicy 在 Domain 中返回允许性和所需确认，不由 Controller 根据枚举判断。Review 写运行请求必须带
`reviewConfirmationId`，该 ID 绑定当前人工 Review 意见版本；纯文本“请改一下”不自动获得写权限。

## 8. SSE 协议

沿用：

```http
GET /api/workbenches/{workbenchId}/runs/{runId}/events
Last-Event-ID: 123
Accept: text/event-stream
```

服务端先验证 Run 属于 Workbench 且当前用户可见，再委托通用 Subscription Service。

事件 payload 公共字段：

```json
{
  "schemaVersion": "workbench-run-event@1",
  "runId": "...",
  "workbenchId": "...",
  "phase": "IMPLEMENT_TEST",
  "occurredAt": 0,
  "data": {}
}
```

事件类型详见总览。命令事件不返回完整环境或未经脱敏的原始 stderr；大输出写有界 Evidence Store 时，SSE
只返回安全摘要和 reference。

## 9. Tool/File/Test 投影

扩展 `chat_tool_invocation` 或建立通用 Run Tool Projection：

```text
+ repository_key
+ relative_working_dir
+ command_classification
+ duration_ms
+ redaction_applied
```

文件变化身份固定为 `repositoryKey + relativePath`。运行前后可使用 Workspace Snapshot 做确定性补充，
但 Workbench 不建立 Artifact/Gate；Snapshot 差异只形成可观察事件与 Review 输入。

## 10. 停止、失败与恢复

### 10.1 Stop

```text
POST stop
→ ChatRun.requestCancellation(now)
→ 持久化 CANCEL_REQUESTED + event
→ commit
→ RuntimeGateway.requestStop(handle)
→ 观察退出
→ ChatRun.cancel/fail/succeed
→ Workbench.finishRun 清除 Phase 活动引用；若 MODIFY，同时释放写租约
```

### 10.2 服务重启

- PENDING 且尚未 afterCommit 启动：标记 INTERRUPTED，不自动启动；
- RUNNING/CANCEL_REQUESTED：通过 Runtime Handle 对账；无法证明存活则 INTERRUPTED；
- 终态 Run 不重复处理；
- 活跃写租约以 ChatRun 终态对账释放；
- 不执行 Provider resume 来重放未知写操作。

### 10.3 浏览器重连

- 前端 local marker key 增加 Workbench/Phase；
- 先查询 Workbench 可见的 active runs，再选择当前 Phase Run；
- SSE 从 lastAppliedEventSeq 继续；
- 410 时重新拉消息和 Run 详情，不尝试伪造缺失事件。

## 11. API

```http
POST /api/workbenches/{id}/phases/{phase}/runs
Idempotency-Key: ...
If-Match: <workbench-version>

{
  "message": "...",
  "runMode": "DISCUSS_READ_ONLY",
  "handoffSourceVersion": 3,
  "reviewConfirmationId": null,
  "attachments": []
}
```

响应 `202 Accepted`：

```json
{
  "runId": "...",
  "sessionId": "...",
  "status": "PENDING",
  "phaseStatus": "IN_PROGRESS",
  "workbenchVersion": 8,
  "capabilitySnapshotHash": "...",
  "repositoryScopeHash": "...",
  "replayed": false
}
```

同一幂等键同输入返回相同 Run；runMode/Handoff/Review Confirmation 不同则返回 409。

重新开始会话使用独立幂等入口：

```http
POST /api/workbenches/{id}/phases/{phase}/conversation/restart
Idempotency-Key: ...
If-Match: <workbench-version>
```

响应返回新 sessionId/generation 和旧 session 的只读引用；活动 Run 存在时返回 409。

## 12. 前端复用

从现有 Chat 前端提取：

- `ConversationTimeline`：只展示消息、Tool Block、系统状态；
- `ConversationComposer`：输入、附件、停止；功能开关控制 Slash/Recall；
- `useResumableConversationRun`：参数化 API base、origin context、事件 reducer；
- `workbench-run-event-reducer.ts`：解析 Workbench 事件并维护文件 stale、测试状态和操作卡片。

不在 Workbench 显示 Chat Feedback/Share；Message Markdown 继续复用已净化 formatter。

## 13. 测试

- Domain：ChatRun 现有状态机回归；RunOrigin/PhaseRunPolicy 无 Mock。
- Domain/Application：Phase Conversation generation、restart 幂等、旧消息不复制、活动 Run 拒绝。
- Application：Provider Registry 唯一性、Workbench Preparation、commit-before-launch、租约释放。
- Interface：Owner 404、幂等冲突、SSE cursor、stop 202、410 cursor expired。
- Infrastructure：事件序列、并发 append、重启对账、Redaction、输出截断。
- Vitest：事件 reducer、stale 文件、重连游标、不同 Phase marker 隔离。
- Playwright：刷新恢复、断线重连、运行中切 Phase、第二个写 Run 被拒绝、停止终态。

## 14. 验收标准

- 四阶段消息和 resume 完全隔离；
- Chat 原页面行为不变；
- Workbench 每轮都可查询不可变 Run Snapshot；
- SSE 文本、工具、文件、测试与终态均可恢复；
- 运行中 Capability Override 不改变当前 Plan；
- 未知运行不重放，写租约最终可对账释放；
- Executor 中不存在 Workbench 来源条件链。
