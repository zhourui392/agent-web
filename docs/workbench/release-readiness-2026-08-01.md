# Workbench 发布就绪与产品验收矩阵（2026-08-01）

> 状态：可审计快照，未达到真实试点退出标准
>
> 基线：[产品设计 §18.1 / §19](../local-development-workbench-mvp-design.md#18-mvp-范围)、
> [TD-10 测试与发布](td-10-test-release.md)
>
> @author alex

## 1. 结论

Workbench MVP 的 17 项实现范围已经形成可执行证据链，覆盖 Controller、真实临时 SQLite、真实临时 Git
仓库、公共 Runtime 子进程 Stub、SSE、Vue 页面和 Chromium 刷新恢复。当前有明确通过记录的主链包括单仓
Run、多仓 Scope、浏览器关闭后后台 Run 继续、Review 人工确认后 MODIFY/受影响测试，以及服务重启后的未知
写 Run 对账。公共 Runtime、Capability Catalog 和 Workbench 核心 Controller 仍可装配；
14 类 Workbench meter 已通过真实 `PrometheusMeterRegistry.scrape()` 合同测试，7 条 Prometheus 告警已通过
安全 YAML 与静态合同检查。

本轮进一步补齐了 Repository Development Context 自动分类、Handoff Agent Candidate、Review Candidate、
首次下游 Run 事务内接收最新 Handoff、类型化 Operation Proposal、Admin Workbench、浏览器上传附件，以及
Codex Exec Policy 前置阻断。安全与兼容性收口包括长会话分页、Run 快速幂等重放、Capability Override
并发/ABA 防护、仓内/上传附件 Runtime 启动紧前复核、上传原子存储与清理、request hash 向后兼容、
工具真实耗时、安全命令/输出摘要、冻结 Repository Scope 历史追溯和前端异步身份隔离。

当前已显式通过 Mock Workbench E2E 14/14、Admin E2E 2/2、真实 Runtime Stub E2E 4/4、Process
Integration 19 项、Git Integration 53 项、Spring Flow 53 项，以及不读取登录态/不访问网络的 Codex
Exec Policy live parser 1/1。这些自动化证明 MVP 实现边界，但不替代真实用户和真实 CLI 试点。

当前结论不是”产品真实试点完成”。仍缺少：

- 真实用户使用真实 Codex/Claude CLI 完成单仓、多仓和完整四阶段试点；
- Prometheus target、规则加载、Alertmanager/接收端投递演练；
- feature flag 关闭、应用回滚、silence 移除和恢复开放的完整演练；
- 性能与容量基线；

## 2. 证据口径

| 标记 | 含义 |
| --- | --- |
| `A-通过` | 本快照有明确执行通过记录；只证明该自动化边界，不自动等同人工产品验收 |
| `A-覆盖` | 实现和自动化用例已存在，但本快照没有单独记录该用例的最新通过输出 |
| `G-通过` | 负向守护已验证，例如禁止自动授权或禁止删除公共能力 |
| `P-待验` | 必须由真实用户、真实 CLI、真实部署或真实监控链路补证 |

“真实边界 E2E”在本文中特指：真实 Spring Controller、HTTP、临时 SQLite、临时 Git 仓库、真实 OS
子进程边界、公共 Runtime Adapter、SSE 和真实 Vue/Chromium 页面；模型输出由受控 Runtime Stub
确定性产生。它不访问真实模型，不使用真实 Codex/Claude CLI 登录态，不能替代产品 Phase 4 真实试点。

## 3. §18.1 MVP 范围审计

| §18.1 范围 | 当前可核验事实 | 状态 |
| --- | --- | --- |
| 1. Workbench 创建、列表和恢复 | Owner Controller、SQLite Query/Repository 和前端 Shell 已实现；真实 E2E 覆盖创建、刷新和历史恢复 | `A-通过` |
| 2. Workspace Root 扫描和多仓库显式选择 | Inspect/Create 两阶段与 Repository Selection 已实现；真实多仓 E2E 从三个临时 Git 仓库中显式选择两个 | `A-通过` |
| 3. 主仓库与写入范围确认 | 不可变 Repository Scope 冻结主仓、READ/MODIFY；真实多仓 E2E 排除未选 sibling，Git Integration 覆盖父目录与 symlink 边界 | `A-通过` |
| 4. 四个固定阶段和人工切换 | Workbench 聚合一次创建四阶段，不依赖生成内容 Gate；Domain、Controller 与浏览器导航均已执行 | `A-通过` |
| 5. 每阶段独立会话 | Phase/Generation 绑定稳定会话；消息分页、重开和前端迟到响应隔离已由后端与 Vitest 覆盖 | `A-通过` |
| 6. 每阶段开箱即用的 Phase Capability Profile | 四阶段版本化默认 Profile 可直接运行；Repository Development Context 自动识别 Java/Node/Python/Go/Rust 及对应构建工具 | `A-通过` |
| 7. 可选能力详情和高级覆盖 | Profile/Catalog/Override 已实现；Override 使用 tombstone 单调 token 防 ABA，Profile 升级时原子重基 | `A-通过` |
| 8. 单次运行能力和仓库范围冻结 | Run Snapshot 冻结 Rules/Skills/MCP、风险、Repository Scope、Handoff Reception、Prompt 和 Runtime Enforcement | `A-通过` |
| 9. Agent 流式输出、工具过程和运行状态 | SSE 投影文本、工具、文件、测试和终态；工具耗时来自单调时钟，命令/输出只公开安全摘要 | `A-通过` |
| 10. 停止、断线重连和刷新恢复 | 真实 E2E 覆盖 Stop 明确终态、刷新恢复和关闭浏览器后后台 Run 继续；Process Integration 覆盖强杀重启对账 | `A-通过` |
| 11. 结构化、人工可编辑的上下文包 | 五字段 Handoff、版本、stale/diff、Agent Candidate 与人工采用/编辑/拒绝已实现；首次下游 Run 在同一事务接收最新版本 | `A-通过` |
| 12. 第四阶段人工 Review、重构确认和回归 | Review Candidate 可逐项采用/忽略；真实 E2E 覆盖人工 Opinion、精确 Confirmation、MODIFY、文件落盘和测试 RUNNING/PASSED | `A-通过` |
| 13. 右侧只读文档查看 | Document API 使用 repositoryKey + relative path；真实 Review E2E 从文件事件打开并读取正文 | `A-通过` |
| 14. 文档区伸缩、收起、恢复、最大化和布局记忆 | Vue 组件、Vitest 与浏览器真实几何/Pointer drag/刷新用例覆盖 25%～70%、collapsed 和 maximized | `A-通过` |
| 15. 文件变化提示和手动刷新 | reducer 仅将当前文档置 stale，不替换正文或滚动位置；用户显式刷新后才读取新版本 | `A-通过` |
| 16. 路径、仓库、能力、进程和敏感信息安全边界 | Scope/symlink/secret/XSS/Owner 测试、Codex Exec Policy、上传附件 Hash/NOFOLLOW/原子存储/清理和 Runtime 启动紧前复核均通过 | `A-通过`、`G-通过` |
| 17. Workbench 与专用领域模块解耦 | ArchitectureTest 18/18；spring-flow 已迁移到公共 Capability 资源路径 | `A-通过`、`G-通过` |

范围实现不等于真实使用验收。尤其是对话空间、布局手感、配置复杂度、真实模型行为和多仓修改体验，仍需
真实用户试点记录。

### 3.1 TD-01～TD-10 验收追踪

| 技术设计 | 当前证据 | 状态 |
| --- | --- | --- |
| TD-01 Runtime/Capability 解耦 | 公共 Runtime、Workspace、Capability 已解耦；ArchitectureTest、Chat 默认测试及 Runtime Contract 均通过 | `A-通过`、`G-通过` |
| TD-02 Workbench Domain/Persistence | 四阶段聚合、不变量、Owner/Archive、会话代际、写租约、乐观锁、幂等、additive schema、Query 投影和真实 SQLite 事务测试通过 | `A-通过` |
| TD-03 Phase/ChatRun/SSE | 四阶段消息与 resume 隔离、不可变 Snapshot、文本/工具/文件/测试/终态恢复、Stop、浏览器关闭、服务重启未知写 Run 不重放均有自动化证据 | `A-通过` |
| TD-04 Multi-Repository Workspace | 非 Git Root sibling Inspect、不可变 Scope、单/多仓统一 Plan、未选 sibling/父目录拒绝、结构化 repositoryKey/path 和真实 Git Integration 通过 | `A-通过` |
| TD-05 Rules/Skills/MCP | 四阶段版本化 Profile、公共 Catalog、零配置运行、Snapshot Hash、Override 权限求交/NEXT_RUN、required capability fail-closed 与自动 Development Context 通过 | `A-通过` |
| TD-06 Document Viewer | API 拒绝绝对路径/未选仓库，Pane drag/collapse/maximize/restore、stale/manual refresh、Markdown/XSS 净化和不依赖 `/api/fs` 通过 | `A-通过` |
| TD-07 Phase Handoff | 五字段持久化、人工编辑、Agent Candidate、Reception 版本/Hash、stale/diff、首轮事务接收、并发冲突和无大段聊天复制通过 | `A-通过` |
| TD-08 High-Impact Operations | 四类 Proposal/Target/Hash 分别建模，只创建 `PROPOSED`，独立授权且默认无 Executor；Exec Policy 阻断自然语言/命令变体绕过 | `A-通过`、`G-通过` |
| TD-10 Test/Release | 默认测试、前端门禁、Vitest、三组 E2E、process/git/spring-flow/live parser 已通过；真实 CLI 试点、监控、开关、回滚和容量仍未完成 | 自动化 `A-通过`；发布退出 `P-待验` |

## 4. 产品 §19 二十项验收矩阵

| # | 产品验收项 | 实现与自动化证据 | 状态与未决项 |
| --- | --- | --- | --- |
| 1 | 创建时明确选择一个或多个仓库和主仓库 | [真实多仓 E2E](../../tests/e2e/workbench-real.spec.ts) 使用三个临时 Git 仓库，经 Inspect 与真实 Controller 创建两仓/单仓 Workbench；Controller DTO 测试见 [WorkbenchControllerTest](../../src/test/java/com/example/agentweb/interfaces/workbench/WorkbenchControllerTest.java) | `A-通过`；真实用户选择体验待试点 |
| 2 | 未选择 sibling 不进入本轮写入范围 | [RepositoryScope](../../src/main/java/com/example/agentweb/domain/workspace/RepositoryScope.java) 与 [WorkbenchExecutionPlanProviderTest](../../src/test/java/com/example/agentweb/app/runtime/WorkbenchExecutionPlanProviderTest.java) 约束可读/可写根；真实多仓 E2E 断言 `service-b/service-c` 不进入单仓 Scope | `A-通过`；真实 CLI 多仓写入仍为 `P-待验` |
| 3 | 任一阶段对话区都是页面主要区域 | [Workbench.vue](../../frontend/js/pages/Workbench.vue)、[WorkbenchConversationPanel.vue](../../frontend/js/components/WorkbenchConversationPanel.vue)；[浏览器布局用例](../../tests/e2e/workbench.spec.ts) 已验证四阶段真实几何比例 | `A-通过`；真实用户可用性仍为 `P-待验` |
| 4 | 文档区收起后对话区占满宽度 | [workbench-document-state.spec.ts](../../tests/unit/workbench-document-state.spec.ts) 验证 collapsed 全宽；浏览器布局用例已验证页面真实宽度 | `A-通过` |
| 5 | 文档区可拖动且刷新恢复布局 | [workbench-document-pane.spec.ts](../../tests/unit/workbench-document-pane.spec.ts) 验证拖动、25%～70% 限制和身份键恢复；浏览器布局用例已用 Pointer drag 并验证刷新恢复 | `A-通过`；真实用户手感为 `P-待验` |
| 6 | 点击带仓库标签路径在右侧打开 | [WorkbenchDocumentPane.vue](../../frontend/js/components/WorkbenchDocumentPane.vue) 使用 repository key + relative path；真实 Review E2E 点击 `review-service/review-e2e.txt` 并读取右侧正文 | `A-通过` |
| 7 | 当前文档变化只提示更新，不打断阅读 | [workbench-document-state.spec.ts](../../tests/unit/workbench-document-state.spec.ts) 验证 `FILE_CHANGED` 只置 stale、不替换正文和滚动位置；[workbench-document-pane.spec.ts](../../tests/unit/workbench-document-pane.spec.ts) 验证手动刷新；本轮 Vitest 全量已通过 | `A-通过` |
| 8 | 不打开能力设置也能在四阶段直接对话 | 默认 Profile、[PhaseCapabilityBindingResolver](../../src/main/java/com/example/agentweb/domain/workbench/PhaseCapabilityBindingResolver.java)、Repository Development Context 和 Run preparation 自动解析；真实单仓 E2E 未操作能力 Drawer 即启动 Run，并在历史中看到冻结能力 | `A-通过`；真实 CLI Profile 可用性为 `P-待验` |
| 9 | 四阶段消息、Profile、上下文包、最近文档隔离 | 阶段会话、Profile、Handoff 与前端 storage key 均包含 Workbench/Phase/Generation；消息默认/最大 50 条按正数游标向前分页，前端按 messageId 去重升序合并并拒绝跨 Workbench/Phase/Generation 迟到响应 | `A-通过` |
| 10 | Run 可追溯实际 Rules、Skills、MCP | [WorkbenchRunSnapshot](../../src/main/java/com/example/agentweb/domain/workbench/WorkbenchRunSnapshot.java) 冻结 binding 与 Repository Scope；浏览器历史用例追溯三类能力，MCP 明示 `READ/WRITE` 风险，真实单仓 E2E 从历史读取实际默认 Profile | `A-通过` |
| 11 | 高级覆盖下一轮生效，运行中 Profile 不变 | [PhaseCapabilityConfiguration](../../src/main/java/com/example/agentweb/domain/workbench/PhaseCapabilityConfiguration.java) 与 Run Snapshot 分离；首次写、并发 CAS、delete/recreate tombstone、ABA 防护和 Profile rebase 已由 Domain/App/真实 SQLite/Controller 测试覆盖 | `A-通过`；既有 active token 部署后整体 +1 的兼容说明见 §5.7 |
| 12 | Handoff 包含五个规定字段 | [PhaseHandoff](../../src/main/java/com/example/agentweb/domain/workbench/PhaseHandoff.java)、Candidate generator、SQLite round-trip 和真实 API 覆盖 Summary、Decisions、Open Questions、Pinned Files、Referenced Runs；候选只能由人工采用/编辑/拒绝，首次下游 Run 在提交事务内接收 latest Handoff | `A-通过` |
| 13 | 可手动完成、重开和任意切换阶段 | [Workbench](../../src/main/java/com/example/agentweb/domain/workbench/Workbench.java) 承载人工状态转换；Domain、Controller、Vitest 和 Mock 浏览器导航已在本轮执行，Review 允许无会话人工完成并可重开 | `A-通过` |
| 14 | 不因生成内容缺少格式或字段阻止切换 | Workbench 阶段状态不使用生成内容 Gate；`ArchitectureTest` 防止回引生成内容 Gate，Domain 与浏览器任意导航用例已执行 | `A-通过`、`G-通过`；真实异常输出体验为 `P-待验` |
| 15 | Review 意见由人确认后才执行重构 | Review Candidate 只产生候选并支持逐项采用/忽略；[ReviewOpinion](../../src/main/java/com/example/agentweb/domain/workbench/ReviewOpinion.java)、[ReviewModifyConfirmation](../../src/main/java/com/example/agentweb/domain/workbench/ReviewModifyConfirmation.java) 与 MODIFY 请求绑定；真实 E2E 先保存 Opinion、精确确认，再提交 MODIFY | `A-通过` |
| 16 | 重构后提示并执行用户要求的受影响测试 | 真实 Review E2E 的 Runtime Stub 写入受控文件，产生测试 `RUNNING → PASSED`、完成提示并持久化到历史 | `A-通过`；真实项目测试命令与真实 CLI 为 `P-待验` |
| 17 | 流式文本、工具、文件变化和终态可恢复 | [workbench-run-state.spec.ts](../../tests/unit/workbench-run-state.spec.ts) 覆盖全部语义事件；工具仅在唯一完整 started→finished 生命周期公开真实 `durationMs`，命令与输出只公开有界安全摘要；真实单仓/Review E2E 及进程集成测试覆盖恢复 | `A-通过`；真实部署版本回滚仍单独待验 |
| 18 | commit、push、部署不因阶段或聊天自动授权 | 四类类型化 Proposal 使用严格 Target、canonical Hash 和幂等键，只创建 `PROPOSED`；授权仍由独立 Operation 决策完成；Codex Exec Policy live parser 验证 direct/绝对路径/shell wrapper/compound/alias 高影响命令均在进程启动前阻断 | `A-通过`、`G-通过`；真实 Executor 演练待验 |
| 19 | Workbench 架构边界守护 | [ArchitectureTest](../../src/test/java/com/example/agentweb/ArchitectureTest.java) 18/18，防止 Spring AOP 代理类被声明为 `final`；配置测试验证公共 Runtime/Catalog 与代表性 Controller 可独立装配 | `A-通过`、`G-通过` |
| 20 | 公共能力独立可装配 | 公共 Runtime/Catalog 独立存在且可装配；真实试点未完成，不得删除公共能力 | `G-通过`；真实试点尚未完成 |

## 5. 当前明确通过的自动化证据

### 5.1 真实 Controller + SQLite + Runtime Stub 浏览器边界

[workbench-real.spec.ts](../../tests/e2e/workbench-real.spec.ts) 经
[playwright.workbench-real.config.ts](../../tests/playwright.workbench-real.config.ts) 启动真实 Spring 应用和 Vue
页面，当前记录为 4 项通过：

1. 单仓 Run：真实 Controller/SQLite/Runtime Stub/SSE，刷新恢复，历史能力追溯，Stop 后明确取消终态；
2. 浏览器关闭：关闭 Chromium 页面不发送取消，重新打开后恢复同一 Run 的持久化事件、历史和明确终态；
3. 多仓 Scope：真实临时 Git 仓库扫描，保留主仓与已选仓，排除未选择 sibling；
4. Review：人工 Opinion 与精确 Confirmation 后才提交 MODIFY，Runtime Stub 写文件并产生受影响测试事件，
   SQLite 事件页与刷新历史均恢复文件、测试和终态。

以上 4/4 不使用真实 Codex/Claude CLI，不构成产品 Phase 4 真实试点。

### 5.2 Mock 浏览器合同

[workbench.spec.ts](../../tests/e2e/workbench.spec.ts) 使用严格、与真实公共 API 对齐的 fixture，当前记录
14/14 通过。除四阶段主对话空间、布局、默认 Capability、历史冻结、会话 ensure、Run 提交和安全投影外，
还覆盖 Handoff Candidate、Review Candidate、类型化 Operation Proposal、仓内文档与浏览器上传附件联合提交、
上传失败保留草稿并重试、精确 DELETE、归档只读以及刷新恢复。fixture 不携带生产合同禁止公开的原始
command/token 字段，也不会通过放宽前端 parser 掩盖接口漂移。

[admin-workbench.spec.ts](../../tests/e2e/admin-workbench.spec.ts) 由独立 Admin MPA 配置运行，当前记录 2/2：
未登录与普通用户均不能访问；ADMIN 只能看到安全投影，并在二次确认后以固定 `{}` 请求 Stop/Reconcile。

### 5.3 架构

- [ArchitectureTest](../../src/test/java/com/example/agentweb/ArchitectureTest.java)：当前记录 18/18 通过；新增守卫
  扫描 `@Repository`、类级及方法级 `@Transactional`，禁止需要 Spring AOP 子类代理的类声明为 `final`；

### 5.4 Telemetry 聚焦测试

当前已知聚焦记录：

- `WorkbenchCreationAppServiceTest`、`WorkbenchRunPreparationServiceTest`、
  `WorkbenchChatRunTerminalParticipantTest` 已验证 creation、capability resolution 和持久化终态后的
  Run 指标调用边界；
- `MicrometerWorkbenchTelemetryTest`、`WorkbenchActiveGaugeTest` 与
  [WorkbenchPrometheusExporterContractTest](../../src/test/java/com/example/agentweb/infra/workbench/metrics/WorkbenchPrometheusExporterContractTest.java)
  已通过隔离 Java 8 source/target 编译和执行；Exporter 测试使用真实
  `PrometheusMeterRegistry.scrape()`，不是仅查询 `SimpleMeterRegistry`。

### 5.5 强杀与重启恢复

[WorkbenchRuntimeRestartRecoveryProcessIntegrationTest](../../src/test/java/com/example/agentweb/process/WorkbenchRuntimeRestartRecoveryProcessIntegrationTest.java)
已显式运行并通过 1/1（31.92 秒）。测试先按正式合同保存并显式接收
`SOLUTION_DESIGN → IMPLEMENT_TEST` Handoff，再 fork 两个独立 Spring JVM，复用同一临时 SQLite：第一个 JVM
在写 Run 已持久化 Runtime Handle 和写租约后被 `destroyForcibly` 强杀；第二个 JVM 启动时走生产 Recovery
路径，将旧 Run 收口为 `INTERRUPTED / SERVER_RESTARTED`，删除 Handle、释放 Workbench/Phase 写租约，且
Runtime 审计次数保持 1，证明旧写操作没有自动重放。恢复后再次经真实 Controller 提交写 Run，最终
`SUCCEEDED`，审计次数从 1 增至 2，写租约再次释放。

### 5.6 同一候选工作树的 push 前门禁

2026-08-02 最终提交候选记录：

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B test`：2442 项通过，0 failure、0 error、0 skipped；ArchitectureTest 18/18；
- `cd frontend && npm run typecheck && npm run lint && npm run build`：全部通过；
- `cd tests && npm run typecheck && npm test`：typecheck 通过，Vitest 50 个文件、523 项通过；
- Workbench Mock E2E：14/14；Admin Workbench E2E：2/2；真实 Runtime Stub E2E：4/4；
- Process Integration：19 项；Git Integration：53 项；Spring Flow：53 项；Codex Exec Policy live parser：1/1。

Vite 仅保留已有的外部 CSS 构建期解析、VueUse PURE 注释和大 chunk warning，不影响退出码。

### 5.7 本轮安全、并发与恢复收口

| 能力 | 当前自动化证据与边界 |
| --- | --- |
| 长会话分页 | Phase 消息默认/最大 50 条；SQLite 以 `messageId` 游标 DESC 读取 `limit + 1` 后恢复升序，响应返回 `nextCursor`；非法 `beforeMessageId/limit` 返回 400 |
| 单条消息边界 | 单条公开正文上限 1 MiB；SQL 先用 `CASE` 限制投影，超大历史消息不进入 Java，并稳定返回 HTTP 413 / `WORKBENCH_PHASE_MESSAGE_TOO_LARGE` |
| 前端异步隔离 | ensure、restart、submit 的每个 await 后验证冻结的 owner/workbench/phase/generation 与 request token；迟到请求不关闭新 SSE、不清空新草稿、不覆盖新 loading |
| Run 幂等快速重放 | 首次事务已提交但 202 响应丢失时，相同 owner、Workbench、Phase、幂等键和 canonical request 可携旧 `If-Match` 精确重放；不重复写消息/Snapshot/Prompt 或启动 Runtime；同键异请求仍冲突 |
| Run request hash 兼容 | `workbench-run-submit-request@1` 保持 `REPOSITORY_DOCUMENT` 的既有 canonical 编码；仅 `UPLOADED_CONVERSATION` 使用新的类型化字段，避免部署前后仓内附件幂等 hash 漂移 |
| Capability 并发 | initial absent token 为 0，首次 present 为 1；删除写 tombstone 并持续递增 token，消除 delete/recreate ABA；Profile ID/version 升级后显式 PUT 原子重基 Profile、Override、Audit 和 version |
| Repository Development Context | 仅在已选仓库有限 marker 根中识别 Java/Node/Python/Go/Rust 及 Maven/Gradle/Node/PyProject/Pip/Go Modules/Cargo；Capability Preview 与真实 Run 使用同一冻结 Scope 和分类；明确不读取或注入 `AGENTS.md`、`CLAUDE.md` |
| Handoff/Review Candidate | Assistant 消息选择、五字段提取、Scope/DocumentReference、Review Item/受影响文件/测试规则均在 Domain generator；Application 只把 CQRS 投影编排为领域输入，候选不会自动写入正式状态 |
| 首次下游 Handoff | 首次下游 Run 不依赖额外预调用，在 Run 提交事务中接收 latest Handoff 并冻结 Reception 版本/Hash；上游后续更新只产生 stale，不静默覆盖 |
| 类型化 Operation Proposal | `GIT_COMMIT`、`GIT_PUSH`、`LOCAL_DEPLOY`、`PRODUCTION_WRITE` 使用各自严格 Target 和 canonical Hash；幂等创建仅进入 `PROPOSED`，不因聊天文本或阶段切换获得授权 |
| 仓内附件启动复核 | Snapshot 只保存 repositoryKey、relativePath、SHA-256、mediaType 和 size；Runtime 在 `ProcessBuilder.start()` 紧前拒绝 symlink/非 regular file，并精确复核大小、SHA-256、读取前后文件身份和 real path |
| 浏览器上传附件 | 上传记录绑定 Owner/Workbench/Phase/Generation，SQLite 与原子文件存储分离；提交时事务绑定 Run，Runtime 只读复制并再次校验 Hash/NOFOLLOW；清理覆盖过期与终态，精确 410 DELETE 重试视为幂等释放成功 |
| 附件读写分治 | 写侧 `UploadedConversationAttachmentRepository` 只保留 5 个聚合 lifecycle 方法；纯配额统计拆为 Domain `UploadedConversationAttachmentQueryService`，Application 不注入带 SQL/ORM 注解的实现类型 |
| Admin Workbench | 独立 Query 投影不返回消息正文、Prompt、Secret 或原始命令；ADMIN 仅能查询、Stop、单 Run Reconcile，不能代 Owner 对话、修改 Handoff/Override 或批准 Operation；动作写入独立审计仓储 |
| Spring 真实装配 | 移除三个被 Spring AOP 代理类的 `final`；ArchitectureTest 扫描 Repository 与 Transactional 类/方法，防止 `Cannot subclass final class` 回归 |
| 工具耗时 | 每 execution 独立使用 `System.nanoTime()`；只有唯一、完整、同 execution/callId 的 started→finished 才输出 `durationMs`，缺失、重复、乱序、跨 execution、回退或超过 24 小时均省略 |
| 安全命令/输出摘要 | 公共事件只按 repositoryKey、命令类别、status、exitCode 生成最长 1024 字符且无控制字符的 `commandSummary/outputSummary`；不读取或变形 command、cwd、env、stdout/stderr、绝对路径或 Secret，前端详情默认折叠 |
| 冻结能力与仓库范围 | 历史 Run 同时冻结 Rules/Skills/MCP、MCP `READ/WRITE` 风险、Repository Scope hash、主仓和精确 READ/MODIFY 投影，避免以后配置变化改写历史事实 |

Capability token 的部署兼容性需要明确告知调用方：既有 active 数据行无需迁移且数据库值不变，但公开
token 在部署后整体增加 1；部署前已打开页面可能安全收到一次 409，刷新后继续。旧实现已经物理删除的历史
无法恢复，只能从当前 absent token 0 建立新生命周期；部署后的删除均保留 tombstone。

附件复核已把验证放到 `ProcessBuilder.start()` 紧前并执行读取前后身份检查，但 Java `Path` API 不能从
内核层完全消除最终复核返回到 OS 创建子进程之间的极小 TOCTOU 窗口；因此它是当前架构下的严格
fail-closed 边界，而不是形式化的零竞态保证。

### 5.8 慢分组与真实进程边界

- Process Integration 19 项：`AgentCliGatewayTest` 9 项、`AgentCliInvokerImplTest` 9 项、双 JVM
  `WorkbenchRuntimeRestartRecoveryProcessIntegrationTest` 1 项；
- Git Integration 53 项：真实临时 Git/worktree、Repository Inspect/Scope、父目录、sibling 与 symlink 边界；
- Spring Flow 53 项：完整分组 35 项，加显式 `ScheduledTaskTest` 18 项；公共 `capability/rules`、
  `capability/skills` 和 `agent.capability.mcp-server-root` 配置路径就绪，Feedback 不存在会话合同对齐
  404 / `SESSION_NOT_FOUND`；
- Codex Exec Policy live parser 1/1：只调用本机 `codex execpolicy check`，不启动 Agent、不读取用户登录态、
  不访问网络，覆盖 direct、绝对路径、shell wrapper、compound command 与 alias 形式的高影响命令。

## 6. 指标与告警证据

### 6.1 十四类 Prometheus meter

Exporter 合同已触发并验证以下 14 类 meter；Timer/Summary 在 exposition 文本中展开为
`count`、`sum`、`max` series：

1. `workbench_creation_total{result}`；
2. `workbench_active` Gauge；
3. `workbench_run_total{phase,mode,status}`；
4. `workbench_run_duration_seconds_{count,sum,max}{phase,mode}`；
5. `workbench_write_conflict_total`；
6. `workbench_sse_reconnect_total{result}`；
7. `workbench_event_lag_seconds_{count,sum,max}`；
8. `workbench_capability_resolution_total{result}`；
9. `workbench_capability_version_change_total`；
10. `workbench_workspace_scope_violation_total`；
11. `workbench_document_read_total{kind,result}`；
12. `workbench_handoff_conflict_total`；
13. `workbench_operation_total{type,status}`；
14. `workbench_recovery_reconciliation_total{result}`。

创建计数不能使用 `created`：OpenMetrics/Prometheus 将 `_created` 作为保留后缀；活跃数量可下降，必须是
`workbench_active` Gauge，不能伪装成 `_total` Counter。

### 6.2 七条告警

[workbench-alerts.yml](../../ops/prometheus/workbench-alerts.yml) 已包含并通过安全 YAML/静态合同检查：

1. `WorkbenchRunFailureRatioHigh`；
2. `WorkbenchWriteConflictBurst`；
3. `WorkbenchSseReconnectFailureRatioHigh`；
4. `WorkbenchEventLagHigh`；
5. `WorkbenchCapabilityResolutionFailureRatioHigh`；
6. `WorkbenchRecoveryReconciliationFailed`；
7. `WorkbenchWorkspaceScopeViolation`。

规则名称唯一，`severity/service/component` 标签和指标名称静态合同已验证；Recovery 与 Scope 规则为
`critical`。当前环境没有 `promtool`，尚未验证生产 Prometheus 加载，也没有执行 Alertmanager/接收端投递。

## 7. 发布前待验证清单

| 待验证项 | 当前证据 | 完成所需记录 | 状态 |
| --- | --- | --- | --- |
| 真实单仓试点 | 真实边界 Stub E2E 已通过 | 真实用户、真实 Codex/Claude CLI、真实仓库需求、参与者、输入、Run/文件/测试结果与反馈 | `P-待验` |
| 真实多仓试点 | 临时 Git 多仓 Scope E2E 已通过 | 真实 CLI 在已选多仓读取/修改，未选 sibling 无读写，主仓 `-C` 与附加目录证据 | `P-待验` |
| 完整四阶段/Review 试点 | Review Stub E2E 已通过 | 真实用户完整四阶段、五字段 Handoff、人工 Review、真实重构与真实受影响测试 | `P-待验` |
| 服务进程重启对账 | 进程集成测试已显式通过 1/1；覆盖双 JVM、强杀、同库恢复、未知写 Run、Runtime Handle、双重写租约、明确终态和不自动重放 | 自动化证据已记录在 §5.5；真实应用版本回滚仍由下方独立门禁约束 | `A-通过` |
| 指标采集 | exporter 合同通过 | 生产式 Spring Actuator target 为 `UP`，14 类 series 在 collector 可查询 | `P-待验` |
| 告警加载与路由 | 7 条规则安全 YAML/静态检查通过 | `promtool` 或生产 Prometheus rule load；warning/critical 测试告警到达有 Owner 的接收端 | `P-待验` |
| feature flag 关闭 | 配置和告警说明已实现 | 关闭 write/create/high-impact；已有 Run 仍可停止/恢复；无活动 Run 后关闭总开关的 dated record | `P-待验` |
| 应用回滚 | additive schema 与回滚说明已设计 | time-bounded ticket-linked silence、版本回滚、SQLite/runtime 证据保留、未知写 Run 不重放、移除 silence、恢复开放 | `P-待验` |
| 性能与容量 | TD-10 只有目标值 | 试点机对列表、10k 事件、SSE 首事件、2 MiB 文档、50 repos 和长对话的实际基线 | `P-待验` |
| 自动 Profile 真实仓库试点 | Repository Development Context 已接入 Preview 与 Run，覆盖五类语言生态和七类构建工具；仓内说明只以安全引用表达，不读取 Agent 指令文件 | 在真实 Java/Node/Python/Go/Rust 仓库核对分类、默认能力质量、冻结 Snapshot 与降级提示 | `P-待验` |
| Handoff/Review Candidate 真实模型质量 | Candidate 生成、人工采用/编辑/拒绝、结构化文件引用、首次下游事务接收和 Review 逐项处理已实现并自动化通过 | 真实 CLI 输出下核对候选准确率、人工修改成本、五字段质量和受影响测试建议 | `P-待验` |
| 高影响操作真实执行器 | 四类 Proposal、独立授权状态机、Codex Exec Policy 阻断与安全投影已自动化通过；默认未开放真实 Executor | 在独立安全环境逐项验收 commit、push、local deploy；production-write 与正式交付仍保持关闭 | `P-待验` |
| 上传附件运维 | SQLite、原子文件存储、Run 绑定、Runtime 只读复制、Hash/NOFOLLOW 复核、过期/终态清理与 DELETE 幂等已自动化通过 | 试点环境核对容量、清理调度、磁盘告警和异常退出后的残留扫描 | `P-待验` |
| 最终发布门禁 | 同一候选工作树已完成 Backend、Frontend、Vitest、mock/真实 Stub E2E、强杀恢复和 Repository Scope/Git 集成门禁 | 结果和数量见 §5.6；真实 CLI/用户试点与运维门禁仍独立保留 | `A-通过` |

## 8. 更新与签署规则

- 每项从 `A-覆盖` 提升为 `A-通过` 时，记录命令、提交 SHA、日期、环境和结果；
- 每项从 `P-待验` 关闭时，链接不可变的演练或试点记录，不使用口头“已测过”；
- 真实 CLI/真实用户证据不得由 Runtime Stub、MockMvc、Vitest 或静态检查替代；
- 告警规则存在不得替代 collector `UP`、规则加载和接收端投递；
- 进程集成测试存在不得替代真实服务 kill/restart 对账；
- §19 二十项和 TD-10 发布退出标准全部关闭前，不得把本文状态改为”Ready”。
