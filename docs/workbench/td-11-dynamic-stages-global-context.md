# TD-11 Workbench Dynamic Stage 与全局上下文

> 状态：已接受，实施中
> 日期：2026-08-05
> 前置：[Workbench 技术设计总览](README.md)、[TD-01 公共 Runtime 与 Capability 解耦](td-01-runtime-capability-decoupling.md)、[TD-04 多仓库工作区](td-04-multi-repository-workspace.md)、[TD-06 文档查看器](td-06-document-viewer.md)
> @author alex

## 1. 结论

Workbench 采用 Dynamic Stage 单模型。系统没有需要保留或转换的 Workbench 数据，因此直接以 Stage Catalog、不可变 Stage Snapshot 和 Stage Run 作为唯一在线模型，不写入 Phase 记录，不提供旧接口适配、旧前端状态恢复或应用内模型迁移。

管理员发布 Stage Definition；用户创建 Workbench 时选择 Definition Identifier；服务端按 Published Revision 的 `sequenceNumber` 排序并冻结 Stage Snapshot。已有 Workbench 永远按自身 Snapshot 工作，不回查当前 Catalog 来改变行为。

Stage 之间没有前置、后置、Gate、默认上游或自动流转。顺序只负责管理端排序、创建页展示、Workbench 导航和未来全局上下文分组。

每次 Stage Run 再次冻结实际使用的能力、上下文、工作区和 Prompt 事实。Command、Skill、MCP 必须从 Stage Snapshot 指向的不可变 Artifact 解析，不能从当前 Workspace 重新发现。

完整 Workbench Global Context 尚未进入在线主链。当前 Run 使用确定性空 Manifest：

```text
contextVersion = 0
documents      = []
promptContent  = Context version: 0
                 No published documents.
```

该基线表示当前没有已发布 Context Document，不是其他模型的回退路径。

## 2. 目标关系

```text
Capability Source Configuration
    ├── Command Catalog
    ├── Skill Catalog
    └── MCP Server Catalog
                  │ 发布时校验、归档内容与 Hash
                  ▼
         Workbench Stage Catalog
         ├── Definition
         ├── Draft
         ├── Published Revision
         ├── Disabled
         └── Catalog Version
                  │ 创建时按 Published Sequence 排序并冻结
                  ▼
          Workbench Stage Snapshot
                  │ Run 提交时解析不可变 Artifact
                  ▼
           Workbench Run Binding
           ├── Stage Snapshot Hash
           ├── Command Binding
           ├── Capability Binding
           ├── Runtime Compatibility
           ├── Context Manifest
           ├── Repository Scope
           ├── Workspace Snapshot
           └── Prompt Payload

Stage Run ──提出文档候选──> Owner 确认 ──> Workbench Global Context
                                                   │
                                                   └── 后续 Stage Run 可见
```

## 3. 适用范围与取代关系

本设计是当前 Workbench 实现合同，在冲突范围内取代固定工作单元、阶段交接、专属评审和阶段能力覆盖设计。其他通用 Chat、Workflow 或 Runtime 中出现的 review、handoff 概念不在本设计范围内。

继续保留的公共能力包括：

- Repository Scope 与 Workspace Snapshot；
- ChatRun、SSE、Run History、Stop 与恢复协调；
- 不可变 Capability Binding；
- Document Browser 的安全路径边界；
- Admin Workbench 查询、停止、对账和审计；
- 公共 Runtime Command Policy。

Workbench 当前不提供：

- 固定工作单元 Run、Conversation 或 Attachment；
- Workbench Handoff；
- Workbench Review Opinion 或 Modify Confirmation；
- Workbench Capability Override 或 Profile；
- Admin Phase Capability；
- 类型化高影响操作 Proposal、Approval 或 Execution API。

## 4. 产品决策

| 主题 | 决策 |
| --- | --- |
| Stage 生命周期 | Draft、Published Revision、Disabled |
| Stage 更新影响 | 只影响之后创建的 Workbench |
| 创建选择 | 用户提交 Definition Identifier 集合，不提交排序 |
| Stage 顺序 | 服务端使用 Published Revision 的 `sequenceNumber` |
| Workbench Stage | 创建时冻结，之后不可变 |
| Stage 关系 | 无依赖、Gate、默认上游或自动推进 |
| Conversation | 每个 Stage 独立，并以 Generation 管理重启 |
| 活动 Run | 每个 Stage 最多一个；Workbench 最多一个活动写 Run |
| Run Mode | 来自 Stage Snapshot 的 `allowedRunModes` |
| Capability | 发布时归档精确内容；Run 时按 Artifact 解析 |
| Global Context | Workbench 级发布文档清单，变化只影响下一次 Run |
| 当前 Context | 版本 0 的确定性空 Manifest |
| 高影响操作 | 无业务审批 API；Runtime 在副作用前直接拒绝 |

## 5. 架构决策

### ADR-WB-DYN-001：Stage Catalog 是定义与顺序的权威来源

`WorkbenchStageCatalog` 管理 Definition、Draft、当前 Published Revision、Disabled 状态和 Catalog Version。Stage 全局顺序属于 Published Revision，不属于 Workbench 创建请求。

### ADR-WB-DYN-002：Published Revision 不可变

编辑已发布 Stage 时创建或更新 Draft。只有发布成功才产生下一号 `WorkbenchStageDefinitionRevision`；旧 Revision 与已归档 Artifact 不允许原地修改。

### ADR-WB-DYN-003：服务端冻结顺序

Workbench 创建请求只提交 Definition Identifier 集合和读取时的 Catalog Version。服务端重新加载 Catalog、校验版本、拒绝不存在或 Disabled 的 Definition，并按 Published Sequence 生成非空 Stage Snapshot 集合。客户端数组顺序不是业务事实。

### ADR-WB-DYN-004：Workbench Stage Snapshot 不跟随 Catalog 变化

创建后的 Workbench 不回查当前 Definition 来改变名称、说明、顺序、Rule、Command、Skill、MCP 或 Run Mode。发布、停用和重排只影响之后创建的 Workbench。

### ADR-WB-DYN-005：Stage 没有流程图语义

Stage 的 `sequenceNumber` 只用于展示和分组。聚合与应用服务不得从相邻位置推导前置条件、默认输入、完成 Gate 或下一 Stage。

### ADR-WB-DYN-006：能力边界分层

以下边界不得合并：

1. Capability Source：系统从哪里发现能力；
2. 当前 Catalog：当前能发布哪些能力；
3. Published Revision：某次 Stage 发布选择了什么；
4. Workbench Stage Snapshot：某个 Workbench 创建时冻结了什么；
5. Run Binding：某次运行实际采用了什么。

### ADR-WB-DYN-007：发布时归档精确 Capability Artifact

Stage 发布必须归档 Command、Skill、MCP 的精确内容、业务版本、内容 Hash 和序列化 Payload Hash。Run 只通过 Artifact Registry 解析 Snapshot 引用。来源目录之后被修改或删除不影响已冻结 Workbench；Artifact 缺失、Hash 不匹配或无法解析时失败关闭。

### ADR-WB-DYN-008：唯一 Stage Run 身份

Stage Run 的身份固定为：

```text
originReference    = workbenchId + ":" + stageInstanceIdentifier
runOrigin          = WORKBENCH
sessionKind        = WORKBENCH_STAGE
executionContextId = runId
```

不得从 Stage 名称、数组位置或当前 Catalog 推断 Run 归属。

### ADR-WB-DYN-009：Owner-first 授权

所有 Owner Run 读取、SSE、Stop 和 Capability 查询都必须先确认 Workbench Owner，再解析或访问 Run：

```text
加载 Workbench
→ 校验 Owner
→ 解析 Run ID
→ 查询 Stage Run Snapshot
→ 查询 ChatRun
→ 校验 Workbench、Stage 与 Origin 精确一致
```

Owner、Workbench、Stage、Run、Run ID 或 Origin 任一不匹配，对外统一投影为 Run 不存在。Owner 校验失败前不得访问 Snapshot Repository 或 ChatRun Repository，避免通过时序和错误差异枚举 Run。

### ADR-WB-DYN-010：Run Mode 由冻结 Snapshot 授权

每个 `WorkbenchStageSnapshot` 保存非空 `allowedRunModes`：

- 只有一种模式时，客户端自动采用；
- 有两种模式时，客户端初始不选择，必须由用户明确选择；
- 后端重新校验提交模式属于 Snapshot；
- Run Mode 进入提交指纹，模式变化会使旧失败请求指纹失效。

不得固定使用 `MODIFY_WORKSPACE`，不得按 Stage 名称猜测模式，也不得从当前 Catalog 重新读取模式。

### ADR-WB-DYN-011：Runtime Compatibility 只有一个配置来源

Run Preparation 从 `WorkbenchRunPreparationSettings.getRuntimeCompatibility()` 取得 Runtime Compatibility，并传给 `WorkbenchStageCapabilityResolver`。该值必须与公共 `CodexRuntimeCompatibilityMatrix` 的唯一版本一致。

系统不生成 Stage 专属版本字符串，也不维护多个可接受版本分支。Artifact 与 Runtime 版本不匹配时失败关闭。

### ADR-WB-DYN-012：事务提交后才能启动 Runtime

Run 提交事务必须原子写入 Workbench 状态、ChatRun、Stage Run Snapshot、Prompt Payload 和附件绑定。只有事务成功提交后才能启动外部 Runtime；提交失败不得产生外部执行。

### ADR-WB-DYN-013：终态副作用基于提交后的事实

Run 进入终态时，在事务内：

- 释放 Stage 活动 Run 引用；
- 写模式 Run 同时释放 Workbench 写租约；
- 将本 Run 使用的 Stage 上传附件转为 `RELEASE_PENDING`；
- 持久化终态与恢复事实。

事务提交成功后才记录 Stage-only Telemetry。重启恢复只协调持久化状态，不自动重放外部 Runtime 或其他副作用。

### ADR-WB-DYN-014：Stage-only 直接切换

Workbench 没有待迁移数据。Schema、领域模型、接口和前端均只实现 Dynamic Stage：不创建或转换 Phase 数据，不保留旧 API 请求适配，不提供模型判别工厂，也不恢复旧浏览器存储结构。

### ADR-WB-DYN-015：Global Context 是独立聚合目标

完整 Global Context 必须由 Workbench Context 聚合管理 Document、Revision、Candidate 和 Published Manifest。Stage Run 只读取已发布 Manifest，不能把当前 Workspace 文件列表当作已发布上下文。

在完整聚合上线前，`WorkbenchContextManifestQuery` 只返回确定性空 Manifest。不得通过扫描文件、读取其他表或调用旧流程来补齐上下文。

## 6. Stage Catalog 模型

### 6.1 Definition、Draft 与 Revision

`WorkbenchStageDefinition` 保存稳定 Definition Identifier、当前 Published Revision 引用、可选 Draft 与 Disabled 状态。

`WorkbenchStageDraft` 是可编辑工作副本，不是发布事实。Draft 内容至少包含：

- 显示名称与说明；
- `sequenceNumber`；
- Rules；
- Command 选择；
- Skill 选择；
- MCP Server 选择；
- `allowedRunModes`。

`WorkbenchStageDefinitionRevision` 是发布事实。发布时必须一次性校验所有引用、归档 Artifact、计算 Snapshot 内容 Hash、推进 Catalog Version，并删除已发布 Draft。

### 6.2 Catalog 不变量

- Definition Identifier 稳定且唯一；
- Revision Number 单调递增；
- 当前 Published 且未 Disabled 的 `sequenceNumber` 唯一；
- Published Revision 不允许更新或删除其业务内容；
- Disabled Definition 不允许被新 Workbench 选择；
- Disabled 不影响引用其已发布 Revision 的已有 Workbench；
- Catalog Version 冲突必须拒绝，不能静默覆盖管理员并发修改。

### 6.3 Capability Source

Capability Source Configuration 只配置受信任的 Command 目录、Skill 目录与规范化 MCP 定义来源。保存前进行探测和校验；MCP Secret 只保存引用，不保存明文。

来源配置更新只影响之后的发现与发布，不修改已归档 Artifact。

## 7. Workbench 聚合

### 7.1 创建

唯一创建入口构造非空 Stage 集合：

```java
Workbench.create(
    ...,
    List<WorkbenchStageState> stages,
    Instant now
)
```

每个 Stage State 包含不可变 `WorkbenchStageSnapshot`、稳定 `stageInstanceIdentifier`、人工生命周期、Conversation History 和活动 Run 引用。

创建事务同时持久化：

- Workbench；
- Repository Scope；
- 有序 Stage Snapshot；
- 创建幂等 Receipt。

### 7.2 恢复

Repository 只从 Stage 表恢复聚合：

```java
Workbench.restore(
    ...,
    List<WorkbenchStageState> stages,
    WorkbenchStageRunReference activeWriteRunReference,
    ...
)
```

恢复时 Stage 集合为空、Snapshot Hash 损坏、重复 Stage Instance Identifier、无效活动引用或写租约不一致都必须失败关闭。

### 7.3 生命周期不变量

- Workbench 至少包含一个 Stage；
- Stage Snapshot 创建后不可替换；
- 每个 Stage 最多一个活动 Run；
- 每个 Workbench 最多一个活动写 Run；
- 写租约必须指向某个 Stage 的活动写 Run；
- Archive 前必须满足释放策略；
- Stage Complete、Reopen 与 Conversation Generation 由聚合方法表达，不在 Controller 或 SQL 中拼装规则。

## 8. Stage Conversation

每个 Stage 使用独立 `WorkbenchStageConversationHistory`，以 Generation 表达重启：

- 同一 Stage 只有最新 Generation 可接收新 Run；
- ChatSession 使用 `SessionKind.WORKBENCH_STAGE`；
- Context Identifier 为 `workbenchId:stageInstanceIdentifier`；
- 重启必须同时退役旧 Session、创建新 Session、更新 Workbench 和写入幂等 Receipt；
- 上述修改必须在同一事务完成；
- 重复 Idempotency Key 返回原结果，参数指纹冲突必须拒绝；
- 查询消息时必须校验 Owner、Workbench、Stage、当前 Generation、Session Kind、Context Identifier 与 Stage Origin。

Stage 从 `HUMAN_COMPLETED` Reopen 时：已有当前 Conversation 则回到 `IN_PROGRESS`；尚无 Conversation 则回到 `NOT_STARTED`。

## 9. Stage Run 提交

### 9.1 请求事实

Stage Run 请求至少包含：

- `stageInstanceIdentifier`；
- 用户消息；
- 可选 Command Invocation；
- 明确或自动确定的 Run Mode；
- 上传附件选择；
- Idempotency Key。

服务端不得接受 Stage Snapshot、能力内容、Runtime Compatibility、Repository Scope 或排序作为客户端事实。

### 9.2 Preparation 顺序

推荐编排顺序如下：

```text
加载并校验 Owner Workbench
→ 从聚合取得冻结 Stage Snapshot
→ 校验 Stage 当前可运行且无活动 Run
→ 校验 Run Mode
→ 从 Snapshot 解析 Command Invocation
→ 从 Artifact Registry 解析 Rule、Skill、MCP
→ 校验所有内容 Hash 与 Runtime Compatibility
→ 查询已发布 Context Manifest
→ 获取 Repository Scope 与 Workspace Snapshot
→ 校验并绑定 Stage 上传附件
→ 组合 Prompt Parts
→ 生成提交指纹和持久化计划
```

### 9.3 Command

在线 Command 查询只读取 Workbench 内冻结的 Stage Snapshot，并通过 Artifact Registry 返回该 Stage 授权的 Command。不得使用当前 Workspace 的 Slash Command 发现结果。

Command Invocation 必须精确匹配 Stage Snapshot 内的 Command Identifier 与 Version。展开后的内容进入 Prompt Payload 与 Run Snapshot，确保审计和重试稳定。

### 9.4 Capability Binding

`ResolvedCapabilityBinding` 记录实际使用的 Rule、Skill、MCP、拒绝项、Hash、Runtime Compatibility 和解析事实。Run 启动后不随来源配置、Catalog 或 Stage 发布变化。

Artifact 缺失、内容 Hash 或 Payload Hash 不匹配、MCP 授权不完整、Skill 依赖不满足、Runtime 版本不支持时均拒绝启动。

### 9.5 Prompt

Prompt 由有序 `PromptPartSnapshot` 组成，至少覆盖：

- Stage 指令与 Rules；
- 用户消息与 Command 展开；
- Context Manifest；
- Repository Scope 与 Workspace 事实；
- Attachment Reference；
- Runtime Enforcement。

Snapshot 保存每部分类型、内容 Hash 和最终 Payload，使 Run History 不依赖当前文件或配置重建。

### 9.6 提交事务与幂等

提交指纹必须包含所有会改变执行语义的请求事实，尤其包括 Stage、Conversation Generation、用户消息、Command、Run Mode 与附件选择。

同一 Idempotency Key 且指纹相同返回原提交结果；同一 Key 但指纹不同返回冲突。事务内取得聚合版本与写租约，避免并发 Run 绕过单活动约束。

## 10. Run 读取、SSE 与 Stop 授权

`WorkbenchRunAccessResolver` 是 Owner Run 访问的统一授权边界。它必须执行 ADR-WB-DYN-009 的固定顺序。

成功授权要求同时满足：

- Workbench 存在且 Actor 是 Owner；
- Run ID 合法；
- Stage Run Snapshot 属于该 Workbench；
- ChatRun 存在；
- Run Origin 为 `WORKBENCH`；
- Session Kind 为 `WORKBENCH_STAGE`；
- Snapshot Stage Instance Identifier、ChatRun Origin Reference 与请求上下文精确一致。

Run List、Detail、Events Page、Capability、SSE 与 Stop 不得各自实现宽松版本的校验。

SSE 重连使用持久化事件游标与 Retention Window。游标过期应返回明确的安全错误；不能跨 Run 或跨 Owner 读取事件。

## 11. Attachment

上传附件必须绑定 Owner、Workbench、Stage Instance Identifier 和当前 Conversation Generation。Attachment 状态机至少区分可用、已绑定、`RELEASE_PENDING` 与清理结果。

提交 Run 时：

- 只允许选择当前 Stage、当前 Generation、当前 Owner 的可用附件；
- 校验媒体类型、大小、内容签名、数量与配额；
- 符号链接、路径逃逸或存储边界不一致时拒绝；
- 绑定事实进入 Run Snapshot 与 Prompt Payload。

Run 终态后附件只进入 `RELEASE_PENDING`，由清理服务依据生存期限执行物理删除。清理必须幂等，失败可重试且不改变 Run 终态。

## 12. 终态与重启恢复

### 12.1 正常终态

ChatRun Terminal Participant 接收成功、失败、取消等终态后，使用同一事务协调 ChatRun 与 Workbench Stage 状态。事务提交前不得发送 Stage Telemetry。

提交成功后的遥测只包含低基数字段：

```java
runTerminal(RunMode mode, String status, Duration duration)
```

不得附加固定工作单元标签或高基数 Stage Instance Identifier。

### 12.2 Server Restart

独立 JVM 恢复时，对遗留活动 Workbench Run 标记 `INTERRUPTED / SERVER_RESTARTED`，删除 Runtime Handle，释放 Stage 活动 Run 和写租约，并推进附件终态。恢复不得自动重启 Runtime，不得重新发送外部命令。

恢复对账必须幂等；重复启动或管理员重复 reconcile 不能重复释放、重复审计或产生新的执行。

## 13. Global Context 目标设计

### 13.1 聚合边界

完整实现引入独立 Workbench Context 聚合，至少包含：

- `WorkbenchContext`；
- `ContextDocument`；
- `ContextDocumentRevision`；
- `ContextDocumentCandidate`；
- Published Manifest Version；
- Published Content Hash；
- Owner 操作 Receipt。

Context 聚合只引用 Workbench、Stage Instance Identifier、可选 Source Run 和 Repository Scope 中的逻辑文档地址，不持有 Workbench 聚合对象。

### 13.2 文档发布

文档进入 Global Context 的两条路径：

1. Owner 手动选择 Repository Scope 内文档并指定来源 Stage；Source Run 可为空；
2. Agent 为本 Run 产生 Candidate，Owner 确认后发布。

Agent 不能直接发布。确认时必须重新通过 `ScopedDocumentGateway` 校验路径、读取内容并计算 Hash，不能信任 Candidate 中的旧内容或客户端提交的 Hash。

### 13.3 Revision 与 stale

每次发布或显式刷新产生不可变 Document Revision，并推进 Context Version。Workspace 文件内容变化不会自动替换 Published Hash；检测到不一致时标记 stale，只有 Owner 显式刷新才能发布新 Revision。

删除或撤回文档同样推进 Context Version。活动 Run 继续使用其已冻结 Manifest，变化只影响下一次 Run。

### 13.4 Manifest

Run 默认只注入有界元数据：

- Context Version；
- 来源 Stage；
- 可选 Source Run；
- 文档名称与说明；
- `repositoryKey`；
- `relativePath`；
- Published Content Hash；
- Content State。

正文按需通过受控 Document Gateway 读取。读取必须校验 Owner、Run Binding、Published Hash、Repository Scope、路径规范化与文件大小，并记录 `documentRead` 遥测。

### 13.5 当前基线

当前只有 `WorkbenchContextManifest` 值对象和空查询实现。它始终提供版本 0、空文档集合和确定性 Prompt Content。以下能力仍未实现：

- Context Repository 与持久化表；
- Owner Context API；
- Candidate API；
- Document Revision 与 Published Hash 授权；
- Run 绑定后的正文读取授权；
- 前端 Context Drawer。

这些缺口完成前，TD-11 保持实施中。

## 14. 在线 API 合同

### 14.1 Owner Workbench

```http
POST /api/workbenches
GET  /api/workbenches
GET  /api/workbenches/{workbenchId}
POST /api/workbenches/{workbenchId}/archive
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/complete
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/reopen
```

### 14.2 Stage Conversation 与 Attachment

```http
GET    /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/conversation/messages
POST   /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/conversation
POST   /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/conversation/restart
POST   /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/attachments
DELETE /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/attachments/{attachmentId}
```

实际 Conversation 基础路径以 Controller 的 Stage 路径声明为准；客户端不得构造 Phase 路径。

### 14.3 Stage Run 与 History

```http
GET  /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/commands
POST /api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/runs
GET  /api/workbenches/{workbenchId}/runs
GET  /api/workbenches/{workbenchId}/runs/{runId}
GET  /api/workbenches/{workbenchId}/runs/{runId}/events-page
GET  /api/workbenches/{workbenchId}/runs/{runId}/capability
GET  /api/workbenches/{workbenchId}/runs/{runId}/events
POST /api/workbenches/{workbenchId}/runs/{runId}/stop
```

### 14.4 Catalog 与 Admin

```http
GET  /api/workbench/stage-definitions

GET  /api/admin-settings/workbench/capability-sources
POST /api/admin-settings/workbench/capability-sources/validation
PUT  /api/admin-settings/workbench/capability-sources

GET  /api/admin-settings/workbench/stage-definitions
GET  /api/admin-settings/workbench/stage-definitions/{definitionIdentifier}
POST /api/admin-settings/workbench/stage-definitions
PUT  /api/admin-settings/workbench/stage-definitions/{definitionIdentifier}/draft
POST /api/admin-settings/workbench/stage-definitions/{definitionIdentifier}/publish
POST /api/admin-settings/workbench/stage-definitions/{definitionIdentifier}/disable

GET  /api/admin/workbenches
GET  /api/admin/workbenches/{workbenchId}
GET  /api/admin/workbenches/{workbenchId}/runs
GET  /api/admin/workbenches/{workbenchId}/runs/{runId}
POST /api/admin/workbenches/{workbenchId}/runs/{runId}/stop
POST /api/admin/workbenches/{workbenchId}/runs/{runId}/reconcile
```

Admin Run 访问使用独立管理员授权和审计，不复用 Owner 身份；仍必须校验 Run 与 Workbench、Stage Origin 精确一致。

## 15. Stage-only Schema

新库的 Workbench 核心表为：

```text
workbench_capability_source_configuration
workbench_command_definition_revision
workbench_skill_package_revision
workbench_mcp_server_definition_revision
workbench_stage_catalog
workbench_stage_definition
workbench_stage_draft
workbench_stage_definition_revision
workbench_stage_definition_command
workbench_stage_definition_skill
workbench_stage_definition_mcp_server
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

Schema 不创建固定工作单元、交接、专属评审、能力覆盖、通用 Workbench Run Snapshot 或类型化高影响操作表。

SQLite Repository 必须使用真实 SQLite 测试以下事实：

- 全新 Schema 只存在 Stage 模型表；
- Stage Snapshot JSON 与 Hash 完整往返；
- Stage 顺序稳定；
- Conversation Generation 与当前引用稳定；
- 活动 Stage Run 与 Workbench 写租约一致；
- Prompt Payload、Attachment 与 Restart Receipt 精确绑定；
- 乐观锁冲突不会部分写入。

## 16. 前端合同

前端以 `WorkbenchDetail.stages[]` 为唯一导航来源，不维护固定 Stage 数组，也不按名称赋予行为。

创建页：

- 读取 Published Stage 列表与 Catalog Version；
- 至少选择一个 Stage；
- 只提交 Definition Identifier 集合；
- 展示顺序完全采用服务端响应。

Workbench 页：

- 当前选择使用 Stage Instance Identifier；
- Conversation、Command、Attachment 和 Run 请求全部携带 Stage Instance Identifier；
- 唯一 Run Mode 自动选择，两种模式要求显式选择；
- Run Mode 进入失败请求重试指纹；
- 实时事件与 History 使用 Stage-only Envelope；
- 文件事件通过稳定 `data-test` 与文档引用呈现；
- 不读取旧浏览器键值来构造固定工作单元状态。

当前尚未实现 Global Context Drawer，因此 UI 不得伪造空文档以外的已发布 Context。

## 17. 错误、安全与信息投影

### 17.1 Owner 错误投影

Run 访问的以下情况统一返回 Run 不存在：

- Workbench 不存在；
- Actor 不是 Owner；
- Stage Instance Identifier 错误；
- Run ID 格式错误；
- Snapshot 不存在或属于其他 Workbench；
- ChatRun 不存在；
- Run Origin、Session Kind 或 Origin Reference 不匹配。

日志只记录安全标识或不可逆摘要，不输出 Prompt、Secret、Token、附件内容或绝对路径。

### 17.2 Repository Scope 与路径

`MODIFY_WORKSPACE` 只授权冻结 Repository Scope 内的普通文件修改。所有 Document 与 Attachment 路径必须规范化，并防御绝对路径、`..`、符号链接、链接竞争和边界外真实路径。

### 17.3 高影响命令

当前没有 Workbench 高影响操作审批模型。Runtime Command Policy 必须在产生外部副作用前拒绝：

- commit；
- push；
- 本地部署；
- 生产写入；
- 通过绝对路径、Shell Wrapper、复合命令、别名或等价表达发起的同类命令。

Stage Capability、Run Mode 或 `MODIFY_WORKSPACE` 均不能放宽此政策。

## 18. 并发与一致性

必须覆盖以下冲突：

- Catalog Version 过期；
- Stage 发布顺序重复；
- Workbench 创建幂等 Key 指纹冲突；
- 同一 Stage 并发提交 Run；
- 两个 Stage 并发申请写租约；
- Conversation Restart 与 Run 提交竞争；
- Run Stop 与 Runtime Terminal 竞争；
- Server Restart Recovery 与正常终态竞争；
- Attachment 删除、绑定和清理竞争；
- Admin Reconcile 重复执行。

原则是持久化事实单调、外部副作用在提交后发生、重试不重复执行。

## 19. Telemetry

保留的 Workbench 遥测：

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

Telemetry 禁止使用高基数 Stage Instance Identifier 作为 Prometheus Label，也不保留 Handoff Conflict 或类型化 Operation 指标。

## 20. 测试策略与验收

### 20.1 领域层

- Catalog Draft、Publish、Disable、Sequence 与 Version 不变量；
- Workbench 非空 Stage、Stage 生命周期、活动 Run 与写租约；
- Stage Conversation Generation；
- Run Mode 与 Stage Snapshot 授权；
- exact Stage Origin；
- Capability Artifact Hash 与 Runtime Compatibility；
- Prompt 与空 Context Manifest 确定性；
- 终态释放。

领域测试不模拟聚合，不启动 Spring。

### 20.2 应用层

- 创建、Run Preparation、Submission 与提交后 Runtime 启动顺序；
- Owner-first：Owner 失败前 Repository 零访问；
- Run List、Detail、SSE、Capability 与 Stop 的统一授权；
- Conversation Restart 原子编排；
- Attachment 绑定和终态释放；
- Admin Stop 与 Reconcile 审计；
- Empty Context Manifest 查询。

### 20.3 基础设施层

- 使用真实 SQLite 验证 Stage-only Schema、Repository、Query、Run Submission Transaction；
- 使用临时文件系统验证 Artifact Registry、Attachment 和安全路径；
- 使用 Runtime 替身验证 Command Policy，不调用真实登录态命令行工具。

### 20.4 接口层

- Stage Identifier、Run Mode、Idempotency Key 和请求边界校验；
- Owner 与 Admin 安全错误投影；
- Stage-only 响应 Envelope；
- SSE 状态码、游标和断线重连。

### 20.5 前端与浏览器

- Vitest 覆盖动态导航、Run Mode、重试指纹、事件投影、History 与 Attachment；
- Mocked Workbench E2E 覆盖 Owner 主链；
- Mocked Admin E2E 覆盖 Capability Source 与 Stage Catalog；
- 独立端口、临时 SQLite 的真实 E2E 覆盖只读与可写 Stage、SSE、测试事件和文件事件；
- 独立 JVM 集成测试覆盖 Server Restart Recovery。

## 21. 完成定义

TD-11 完成要求同时满足：

1. 领域、Schema、API 与前端只有 Dynamic Stage 模型；
2. Catalog 发布与不可变 Artifact 闭环通过；
3. Workbench 创建、Conversation、Attachment、Run、History、SSE、Stop、Admin 与恢复门禁通过；
4. Owner-first 授权及错 Origin 安全投影有回归测试；
5. Runtime Compatibility、Run Mode、Repository Scope、高影响命令政策有边界测试；
6. 完整 Global Context 聚合、Owner API、Candidate、Repository、Document Gateway 授权和前端 Drawer 已实现；
7. 后端、`frontend/`、`tests/` 与真实浏览器门禁全部通过；
8. 文档与发布状态反映当前验证证据。

在第 6 项未完成前，即使 Dynamic Stage 主链全部为绿，状态仍为“实施中”，不能标记 Ready。
