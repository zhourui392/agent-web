# Workbench 发布就绪与产品验收矩阵（2026-08-01）

> 状态：可审计快照，未达到真实试点退出标准
>
> 基线：[产品设计 §18.1 / §19](../local-development-workbench-mvp-design.md#18-mvp-范围)、
> [TD-10 测试与发布](td-10-test-release.md)
>
> @author alex

## 1. 结论

Workbench 已建立覆盖 Controller、真实临时 SQLite、真实临时 Git 仓库、公共 Runtime 子进程 Stub、SSE、
Vue 页面和浏览器刷新的自动化真实边界。当前有明确通过记录的主链包括单仓 Run、多仓 Scope 和 Review
人工确认后 MODIFY/受影响测试；Harness 关闭时公共 Runtime、Capability Catalog 和 Workbench 核心
Controller 仍可装配；14 类 Workbench meter 已通过真实 `PrometheusMeterRegistry.scrape()` 合同测试，
7 条 Prometheus 告警已通过安全 YAML 与静态合同检查。

当前结论不是“产品真实试点完成”，也不是“Harness 可以退役”。仍缺少：

- 真实用户使用真实 Codex/Claude CLI 完成单仓、多仓和完整四阶段试点；
- Prometheus target、规则加载、Alertmanager/接收端投递演练；
- feature flag 关闭、应用回滚、silence 移除和恢复开放的完整演练；
- 性能与容量基线。

在这些证据补齐前，必须保持 Harness 迁移窗口与公共能力，不得进入 TD-09 的删除阶段。

## 2. 证据口径

| 标记 | 含义 |
| --- | --- |
| `A-通过` | 本快照有明确执行通过记录；只证明该自动化边界，不自动等同人工产品验收 |
| `A-覆盖` | 实现和自动化用例已存在，但本快照没有单独记录该用例的最新通过输出 |
| `G-通过` | 负向守护已验证，例如禁止依赖 Harness、禁止自动授权或禁止删除公共能力 |
| `P-待验` | 必须由真实用户、真实 CLI、真实部署或真实监控链路补证 |

“真实边界 E2E”在本文中特指：真实 Spring Controller、HTTP、临时 SQLite、临时 Git 仓库、真实 OS
子进程边界、公共 Runtime Adapter、SSE 和真实 Vue/Chromium 页面；模型输出由受控 Runtime Stub
确定性产生。它不访问真实模型，不使用真实 Codex/Claude CLI 登录态，不能替代产品 Phase 4 真实试点。

## 3. §18.1 MVP 范围审计

| §18.1 范围 | 当前可核验事实 | 状态 |
| --- | --- | --- |
| 创建、列表、恢复；Workspace 扫描、多仓选择、主仓与写范围 | Workbench Controller、SQLite Repository、Repository Scope 已实现；真实单仓/多仓边界 E2E 已通过 | `A-通过` |
| 四阶段、人工切换、每阶段独立会话 | 固定四阶段 Domain、阶段会话和前端状态隔离均有测试；浏览器导航用例存在 | `A-覆盖` |
| 默认 Phase Capability、能力详情、Override、Run 冻结 | Profile/Catalog/Override/Binding/Snapshot 已实现；真实单仓 E2E 可从历史查看冻结能力 | `A-通过` |
| 流式输出、工具过程、状态、停止、断线与刷新恢复 | 真实单仓 E2E 覆盖刷新恢复和 Stop 终态；真实 Review E2E 覆盖文件/测试/终态持久化恢复 | `A-通过` |
| 结构化上下文包、人工 Handoff | 五字段 Handoff、版本、Reception、stale 和 SQLite round-trip 有 Domain/Interface/Infra 证据 | `A-覆盖` |
| Review、重构确认和回归 | 真实 Review E2E 覆盖精确人工确认、MODIFY、文件落盘、测试 RUNNING/PASSED 和刷新恢复 | `A-通过` |
| 只读文档、伸缩/收起/恢复/最大化、布局记忆、stale/刷新 | Vue 组件、state/composable、Vitest 和浏览器用例均存在；真实 Review E2E 覆盖文件事件后打开文档 | `A-覆盖` |
| 路径、仓库、能力、进程和敏感信息安全 | Domain/Runtime/Controller/Frontend 安全测试覆盖 scope、symlink、secret、XSS 和授权边界 | `A-覆盖` |
| Workbench 与 Harness 解耦 | ArchUnit 17 项通过；Harness disabled ApplicationContextRunner 1 项通过 | `A-通过`、`G-通过` |

范围实现不等于真实使用验收。尤其是对话空间、布局手感、配置复杂度、真实模型行为和多仓修改体验，仍需
真实用户试点记录。

## 4. 产品 §19 二十项验收矩阵

| # | 产品验收项 | 实现与自动化证据 | 状态与未决项 |
| --- | --- | --- | --- |
| 1 | 创建时明确选择一个或多个仓库和主仓库 | [真实多仓 E2E](../../tests/e2e/workbench-real.spec.ts) 使用三个临时 Git 仓库，经 Inspect 与真实 Controller 创建两仓/单仓 Workbench；Controller DTO 测试见 [WorkbenchControllerTest](../../src/test/java/com/example/agentweb/interfaces/workbench/WorkbenchControllerTest.java) | `A-通过`；真实用户选择体验待试点 |
| 2 | 未选择 sibling 不进入本轮写入范围 | [RepositoryScope](../../src/main/java/com/example/agentweb/domain/workspace/RepositoryScope.java) 与 [WorkbenchExecutionPlanProviderTest](../../src/test/java/com/example/agentweb/app/runtime/WorkbenchExecutionPlanProviderTest.java) 约束可读/可写根；真实多仓 E2E 断言 `service-b/service-c` 不进入单仓 Scope | `A-通过`；真实 CLI 多仓写入仍为 `P-待验` |
| 3 | 任一阶段对话区都是页面主要区域 | [Workbench.vue](../../frontend/js/pages/Workbench.vue)、[WorkbenchConversationPanel.vue](../../frontend/js/components/WorkbenchConversationPanel.vue)；[浏览器布局用例](../../tests/e2e/workbench.spec.ts) 已验证四阶段真实几何比例 | `A-通过`；真实用户可用性仍为 `P-待验` |
| 4 | 文档区收起后对话区占满宽度 | [workbench-document-state.spec.ts](../../tests/unit/workbench-document-state.spec.ts) 验证 collapsed 全宽；浏览器布局用例已验证页面真实宽度 | `A-通过` |
| 5 | 文档区可拖动且刷新恢复布局 | [workbench-document-pane.spec.ts](../../tests/unit/workbench-document-pane.spec.ts) 验证拖动、25%～70% 限制和身份键恢复；浏览器布局用例已用 Pointer drag 并验证刷新恢复 | `A-通过`；真实用户手感为 `P-待验` |
| 6 | 点击带仓库标签路径在右侧打开 | [WorkbenchDocumentPane.vue](../../frontend/js/components/WorkbenchDocumentPane.vue) 使用 repository key + relative path；真实 Review E2E 点击 `review-service/review-e2e.txt` 并读取右侧正文 | `A-通过` |
| 7 | 当前文档变化只提示更新，不打断阅读 | [workbench-document-state.spec.ts](../../tests/unit/workbench-document-state.spec.ts) 验证 `FILE_CHANGED` 只置 stale、不替换正文和滚动位置；[workbench-document-pane.spec.ts](../../tests/unit/workbench-document-pane.spec.ts) 验证手动刷新 | `A-覆盖` |
| 8 | 不打开能力设置也能在四阶段直接对话 | 默认 Profile、[PhaseCapabilityBindingResolver](../../src/main/java/com/example/agentweb/domain/workbench/PhaseCapabilityBindingResolver.java) 和 Run preparation 自动解析；真实单仓 E2E 未操作能力 Drawer 即启动 Run，并在历史中看到默认能力 | `A-通过`；真实 CLI Profile 可用性为 `P-待验` |
| 9 | 四阶段消息、Profile、上下文包、最近文档隔离 | 阶段会话、Profile、Handoff 与前端 storage key 均包含 Workbench/Phase/Generation；[workbench-state.spec.ts](../../tests/unit/workbench-state.spec.ts) 和 [workbench-document-state.spec.ts](../../tests/unit/workbench-document-state.spec.ts) 验证隔离 | `A-覆盖` |
| 10 | Run 可追溯实际 Rules、Skills、MCP | [WorkbenchRunSnapshot](../../src/main/java/com/example/agentweb/domain/workbench/WorkbenchRunSnapshot.java) 冻结 binding；浏览器历史用例追溯三类能力，真实单仓 E2E 从历史读取实际默认 Profile | `A-通过` |
| 11 | 高级覆盖下一轮生效，运行中 Profile 不变 | [PhaseCapabilityConfiguration](../../src/main/java/com/example/agentweb/domain/workbench/PhaseCapabilityConfiguration.java) 与 Run Snapshot 分离；[PhaseCapabilityConfigurationTest](../../src/test/java/com/example/agentweb/domain/workbench/PhaseCapabilityConfigurationTest.java) 及 capability 前端单测覆盖 NEXT_RUN | `A-覆盖` |
| 12 | Handoff 包含五个规定字段 | [PhaseHandoff](../../src/main/java/com/example/agentweb/domain/workbench/PhaseHandoff.java)、[PhaseHandoffTest](../../src/test/java/com/example/agentweb/domain/workbench/PhaseHandoffTest.java) 和 SQLite round-trip 覆盖 Summary、Decisions、Open Questions、Pinned Files、Referenced Runs；真实 Review E2E 经真实 API 保存并接收 | `A-通过` |
| 13 | 可手动完成、重开和任意切换阶段 | [Workbench](../../src/main/java/com/example/agentweb/domain/workbench/Workbench.java) 承载人工状态转换；[WorkbenchTest](../../src/test/java/com/example/agentweb/domain/workbench/WorkbenchTest.java)、Controller 与浏览器导航用例覆盖 | `A-覆盖` |
| 14 | 不因生成内容缺少格式或字段阻止切换 | Workbench 阶段状态不使用生成内容 Gate；`ArchitectureTest` 防止回引 Harness Gate，Domain 任意导航用例存在 | `A-覆盖`、`G-通过`；真实异常输出体验为 `P-待验` |
| 15 | Review 意见由人确认后才执行重构 | [ReviewOpinion](../../src/main/java/com/example/agentweb/domain/workbench/ReviewOpinion.java)、[ReviewModifyConfirmation](../../src/main/java/com/example/agentweb/domain/workbench/ReviewModifyConfirmation.java) 与 Review MODIFY 请求绑定；真实 Review E2E 先保存 Opinion、再精确确认、后提交 MODIFY | `A-通过` |
| 16 | 重构后提示并执行用户要求的受影响测试 | 真实 Review E2E 的 Runtime Stub 写入受控文件，产生测试 `RUNNING → PASSED`、完成提示并持久化到历史 | `A-通过`；真实项目测试命令与真实 CLI 为 `P-待验` |
| 17 | 流式文本、工具、文件变化和终态可恢复 | [workbench-run-state.spec.ts](../../tests/unit/workbench-run-state.spec.ts) 覆盖全部语义事件；真实单仓 E2E 覆盖刷新/SSE/Stop，真实 Review E2E 覆盖 file/test/terminal 的 SQLite 事件页和刷新历史；进程集成测试覆盖强杀和重启恢复 | `A-通过`；真实部署版本回滚仍单独待验 |
| 18 | commit、push、部署不因阶段或聊天自动授权 | [HighImpactOperation](../../src/main/java/com/example/agentweb/domain/workbench/HighImpactOperation.java) 使用独立类型、目标、Proof 和过期策略；[HighImpactOperationTest](../../src/test/java/com/example/agentweb/domain/workbench/HighImpactOperationTest.java) 与浏览器 Review/Operation 用例验证聊天文本不授权、批准仅到 `AUTHORIZED` | `A-覆盖`、`G-通过`；真实 Executor 演练待验 |
| 19 | Workbench 不依赖 Harness 专用状态或 API | [ArchitectureTest](../../src/test/java/com/example/agentweb/ArchitectureTest.java) 的 A9～A14 防止 Workspace/Capability/Public Runtime/Workbench 依赖 Harness；[HarnessDisabledWorkbenchConfigurationTest](../../src/test/java/com/example/agentweb/config/HarnessDisabledWorkbenchConfigurationTest.java) 在 `agent.harness.enabled=false` 时验证公共 Runtime/Catalog 和三个 Workbench Controller 可装配 | `A-通过`、`G-通过` |
| 20 | 真实试点完成前不删除仍需迁移的公共能力 | Harness-disabled Context 已验证公共 Runtime/Catalog 独立存在，Harness 专用 Bean 关闭；仓库仍保留 Harness 迁移窗口，未执行 TD-09 删除 | `G-通过`；真实试点尚未完成，因此删除条件明确未满足 |

## 5. 当前明确通过的自动化证据

### 5.1 真实 Controller + SQLite + Runtime Stub 浏览器边界

[workbench-real.spec.ts](../../tests/e2e/workbench-real.spec.ts) 经
[playwright.workbench-real.config.ts](../../tests/playwright.workbench-real.config.ts) 启动真实 Spring 应用和 Vue
页面，当前记录为 3 项通过：

1. 单仓 Run：真实 Controller/SQLite/Runtime Stub/SSE，刷新恢复，历史能力追溯，Stop 后明确取消终态；
2. 多仓 Scope：真实临时 Git 仓库扫描，保留主仓与已选仓，排除未选择 sibling；
3. Review：人工 Opinion 与精确 Confirmation 后才提交 MODIFY，Runtime Stub 写文件并产生受影响测试事件，
   SQLite 事件页与刷新历史均恢复文件、测试和终态。

这三项不使用真实 Codex/Claude CLI，不构成产品 Phase 4 真实试点。

### 5.2 Harness 解耦

- [ArchitectureTest](../../src/test/java/com/example/agentweb/ArchitectureTest.java)：当前记录 17/17 通过；
- [HarnessDisabledWorkbenchConfigurationTest](../../src/test/java/com/example/agentweb/config/HarnessDisabledWorkbenchConfigurationTest.java)：
  当前记录 1/1 通过；验证公共 `AgentProcessKernel`、`RuntimePreflightGateway`、Rule/Skill/MCP/Profile
  Catalog、Workbench Policy 和三个代表性 Controller 存在，同时 Harness Runtime/Persistence/API/Policy
  Bean 不存在。

### 5.3 Telemetry 聚焦测试

当前已知聚焦记录：

- `WorkbenchCreationAppServiceTest`、`WorkbenchRunPreparationServiceTest`、
  `WorkbenchChatRunTerminalParticipantTest` 已验证 creation、capability resolution 和持久化终态后的
  Run 指标调用边界；
- `MicrometerWorkbenchTelemetryTest`、`WorkbenchActiveGaugeTest` 与
  [WorkbenchPrometheusExporterContractTest](../../src/test/java/com/example/agentweb/infra/workbench/metrics/WorkbenchPrometheusExporterContractTest.java)
  已通过隔离 Java 8 source/target 编译和执行；Exporter 测试使用真实
  `PrometheusMeterRegistry.scrape()`，不是仅查询 `SimpleMeterRegistry`。

### 5.4 强杀与重启恢复

[WorkbenchRuntimeRestartRecoveryProcessIntegrationTest](../../src/test/java/com/example/agentweb/process/WorkbenchRuntimeRestartRecoveryProcessIntegrationTest.java)
已显式运行并通过 1/1（27.82 秒）。测试 fork 两个独立 Spring JVM，复用同一临时 SQLite：第一个 JVM
在写 Run 已持久化 Runtime Handle 和写租约后被 `destroyForcibly` 强杀；第二个 JVM 启动时走生产 Recovery
路径，将旧 Run 收口为 `INTERRUPTED / SERVER_RESTARTED`，删除 Handle、释放 Workbench/Phase 写租约，且
Runtime 审计次数保持 1，证明旧写操作没有自动重放。恢复后再次经真实 Controller 提交写 Run，最终
`SUCCEEDED`，审计次数从 1 增至 2，写租约再次释放。

### 5.5 同一候选工作树的 push 前门禁

2026-08-01 最终记录：

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B test`：2254 项通过，0 failure、0 error、0 skipped；
- `cd frontend && npm run typecheck && npm run lint && npm run build`：全部通过；
- `cd tests && npm run typecheck && npm test`：typecheck 通过，Vitest 44 个文件、375 项通过；
- 真实 Workbench E2E：3/3 通过；浏览器布局 E2E：1/1 通过。

Vite 仅保留已有的外部 CSS 构建期解析、VueUse PURE 注释和大 chunk warning，不影响退出码。

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
| 服务进程重启对账 | 进程集成测试已显式通过 1/1；覆盖双 JVM、强杀、同库恢复、未知写 Run、Runtime Handle、双重写租约、明确终态和不自动重放 | 自动化证据已记录在 §5.4；真实应用版本回滚仍由下方独立门禁约束 | `A-通过` |
| 指标采集 | exporter 合同通过 | 生产式 Spring Actuator target 为 `UP`，14 类 series 在 collector 可查询 | `P-待验` |
| 告警加载与路由 | 7 条规则安全 YAML/静态检查通过 | `promtool` 或生产 Prometheus rule load；warning/critical 测试告警到达有 Owner 的接收端 | `P-待验` |
| feature flag 关闭 | 配置和告警说明已实现 | 关闭 write/create/high-impact；已有 Run 仍可停止/恢复；无活动 Run 后关闭总开关的 dated record | `P-待验` |
| 应用回滚 | additive schema 与回滚说明已设计 | time-bounded ticket-linked silence、版本回滚、SQLite/runtime 证据保留、未知写 Run 不重放、移除 silence、恢复开放 | `P-待验` |
| 性能与容量 | TD-10 只有目标值 | 试点机对列表、10k 事件、SSE 首事件、2 MiB 文档、50 repos 和长对话的实际基线 | `P-待验` |
| 最终发布门禁 | 同一候选工作树已完成 Backend、Frontend 和 Vitest 强制门禁；真实边界及布局 E2E 也已通过 | 结果和数量见 §5.5；真实 CLI/用户试点与运维门禁仍独立保留 | `A-通过` |

## 8. 更新与签署规则

- 每项从 `A-覆盖` 提升为 `A-通过` 时，记录命令、提交 SHA、日期、环境和结果；
- 每项从 `P-待验` 关闭时，链接不可变的演练或试点记录，不使用口头“已测过”；
- 真实 CLI/真实用户证据不得由 Runtime Stub、MockMvc、Vitest 或静态检查替代；
- 告警规则存在不得替代 collector `UP`、规则加载和接收端投递；
- 进程集成测试存在不得替代真实服务 kill/restart 对账；
- §19 二十项和 TD-10 发布退出标准全部关闭前，不得把本文状态改为“Ready”，不得启动 Harness 删除。
