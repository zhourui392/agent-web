# Harness M3 自测报告

> 当前判定：PASS / M3 Exit Complete
> 执行日期：2026-07-23
> 完成性复核：2026-07-23；M0 ADR 对照后撤回“可关闭 M3”结论
> 重新验收完成：2026-07-23；修订实现、聚焦/默认/Spring Flow/PMD/Diff 门禁全部通过
> JDK：OpenJDK 21.0.11
> 外部 Codex/Claude/MCP/HTTP Provider：未调用
> 测试 Runtime：临时 Shell CLI Stub + 文件 MCP Catalog Fixture

## 1. 结论

初次实现测试仍是有效回归基线；完成性复核发现的 M0 Runtime 合同缺口已经全部补齐。纵向 Stub 现在直接记录并断言单次 `-c` MCP/Skill 覆盖，不读取 `CODEX_HOME/config.toml`；Domain/M3.1 Snapshot、项目配置/Repo Skill 旁路、实际 CLI 版本/PID、跨 Stage 幂等冲突和 Execution 持久化保留均已有直接证据。

初次基线覆盖：HTTP 创建 Run/Attempt、固化临时 M3 Snapshot、只读 MCP 五维授权、提交后启动 Codex Stub、JSONL 事件、SQLite、脱敏 Artifact、成功终态，以及先提交取消意图再终止 Stub 的取消链路。

默认测试没有调用真实 Codex、Claude、MCP Server 或外部 Provider。Stub 已准确复现并断言 M0 冻结的命令合同；真实安装版本的兼容性仍需在 M4 目标环境执行显式 `live`/手工验证，但该外部环境确认不再阻断 M3 的确定性退出门禁。

## 2. TDD 红—绿证据

本阶段按分层和风险顺序获得以下先红后绿证据：

1. **M3.0 Snapshot 级联删除**：真实 SQLite 测试先证明旧 Run Repository 更新 Artifact/Gate/Approval/取消时删除 Attempt 并级联删除 Snapshot；改为 Stage/Attempt 增量持久化后，Snapshot 始终存在且 Hash 不变。
2. **MCP Domain**：测试先因 MCP Server/Capability/Secret/Selection/Enforcement 类型不存在而编译失败；最小领域模型和 `McpAuthorizationPolicy` 完成后，只读允许、WRITE 整服拒绝、未 Grant、环境拒绝、Tool allowlist 不足和 required fail-closed 转绿。
3. **RuntimeExecution Domain**：状态机测试先因聚合与信号类型不存在而失败；实现 PREPARED/STARTING/RUNNING/取消/终态、Sequence 幂等和清理状态后转绿。
4. **HarnessRun 集成**：Snapshot/Execution 引用、一个 Attempt 一个 Execution、取消中间态和 Runtime 终态映射测试先红；不变量收回聚合后转绿。
5. **Application prepare/launch/cancel**：Mock 测试先因 Execution Service/Port 不存在而失败；事务准备组件和非事务 Launcher 完成后，启动与取消调用顺序转绿。
6. **真实提交边界**：Spring + SQLite 测试先暴露仅验证 `save()` 顺序不能证明 commit；拆成 `HarnessExecutionPreparer` 事务组件与 `HarnessExecutionLauncher` 非事务外壳后，Gateway 调用时事务已结束且 DB 可见 `STARTING/CANCEL_REQUESTED`。
7. **SQLite Runtime 与老库迁移**：Repository/Migration 测试先因表、列和实现不存在而失败；实现唯一约束、事件幂等和幂等迁移后转绿。旧 M2 Snapshot Hash 不重算，MCP 保持空列表。
8. **Runtime Evidence Store**：测试先因 `FileSystemRuntimeEvidenceStore` 不存在而失败；接入现有 Artifact Store 后返回逻辑 `artifact:*` 引用且不暴露物理路径。
9. **Codex Adapter 基线**：测试先因隔离配置、Secret、JSONL、输出上限、超时、取消和清理能力缺失而失败；逐项实现后转绿。追加红测又覆盖 `turn.failed + exit 0`、取消后管道 OUTPUT、Event Sink 异常、Evidence 和清理失败状态。
10. **Interface/Feature Flag**：Controller 测试先因 Execution API 不存在而失败；完成 202/Location、幂等键、404、脱敏 View 和关闭装配后转绿。
11. **M3 Spring 纵向流程**：成功链路和取消链路先因 Runtime 装配缺失而失败；接入受控 Stub、真实 SQLite 和 Artifact Store 后两条链路转绿。重新验收又把 Stub 改为直接检查真实参数数组。
12. **MCP Domain 修订**：测试先因 required、Tool allow/deny、双 timeout 和扩展 Enforcement Profile 不存在而编译失败；实现稳定排序、混合 READ/WRITE 显式求交、WRITE-only/Resource/Enforcement fail-closed 后转绿。
13. **Workspace Repo Skill 信任**：测试先因 Workspace Inventory/Trust Policy 类型不存在而编译失败；实现边界、规范入口路径、Entry Hash、Inventory Hash 及未注册/Hash 变化拒绝后转绿。
14. **M3.1 Snapshot/Codec**：真实 SQLite 测试先因 `SCHEMA_M3_1`、Workspace Inventory 和新工厂不存在而编译失败；实现 M2/临时 M3/M3.1 分版本读取、完整字段 Hash 和老 Schema 不重算后转绿。
15. **Capability Application 修订**：测试先因 `RuntimePreflightReport` 不存在而编译失败；Application 改为编排 Preflight Report、Workspace Trust、Skill/MCP Policy 并创建 M3.1 Snapshot 后转绿。
16. **MCP Catalog 双 timeout**：测试先证明旧 Catalog 仍读取单 timeout；Manifest 改为必需的 `startupTimeoutSeconds/toolTimeoutSeconds` 后转绿，缺失 Tool timeout 会 fail-closed。
17. **Runtime Preflight 与 CLI 合同**：`CodexHarnessRuntimeGatewayTest` 先因版本探测配置和 Workspace Inventory 缺失而编译失败；实现有界 `--version`、兼容清单、项目配置/Repo Skill 扫描、启动前 Hash 复核、完整 MCP/Skill `-c` 和真实 PID 后，14 个 Adapter 用例转绿。
18. **Execution 幂等资源语义**：测试先因 `RuntimeExecutionIdempotencyConflictException` 不存在而编译失败；把同 Run/Stage 判断收回 Domain，并让 Launcher/Controller 返回持久化真实状态和 canonical Location 后转绿。
19. **完整 SQLite 引用保留**：真实 SQLite 在外键开启状态下绑定 M3.1 Snapshot 与 Execution，执行 Runtime 回写、Artifact、Gate、Approval 和取消后，直接 SQL 与聚合恢复均证明两行和 Attempt 两个引用不变。
20. **M3.1 CQRS 解码**：重新验收的 Spring Flow 先返回 `corrupted capability snapshot field timeoutSeconds`；Execution Query 携带 `schema_version` 并按 M3/M3.1 分版本解码后，纵向流程 2/2 转绿，并由真实 SQLite 聚焦测试固定 M3.1 Execution View 回归。
21. **M2 纵向兼容**：完整 Spring Flow 首轮发现旧 M2 Fixture 在新 Snapshot Preflight 中调用默认 Codex 而失败；为 Fixture 增加只响应 `--version` 的 0.145.0 Stub 后，M2 快照不可变流程恢复通过。

Domain 测试不 Mock Domain；Application 只 Mock Repository/Gateway 并使用真实聚合；Infrastructure 使用真实 `@TempDir` 文件系统或 SQLite；Spring Flow 使用 Stub，不调用外部 CLI/MCP。

修订设计早期局部复验执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  -Dtest='McpAuthorizationPolicyTest,WorkspaceSkillTrustPolicyTest,\
SqliteCapabilitySnapshotRepositoryTest,HarnessCapabilityServiceImplTest,\
FileSystemMcpServerCatalogTest' \
  test
```

结果：PASS。该命令只作为修订过程中的阶段性证据；最终退出结论以后续完整聚焦、纵向、默认与静态门禁为准。

## 3. M3 聚焦测试与架构测试

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  -Dtest='*Harness*Test,*CapabilitySnapshot*Test,*Mcp*Test,*RuntimeExecution*Test,*RuntimeEvidence*Test,ArchitectureTest' \
  test
```

结果：PASS。

覆盖：

- `McpAuthorizationPolicyTest`：五维授权、只读、整服拒绝、required fail-closed；
- `RuntimeExecutionTest`：九态转换、事件幂等、取消优先、timeout/lost、终态不可重启；
- `HarnessRunRuntimeExecutionTest`：Attempt/Snapshot/Execution 绑定、取消与终态映射；
- `HarnessExecutionLauncherTest`、`HarnessRuntimeEventServiceTest`：Application 编排和聚合回调；
- `HarnessExecutionCommitBoundaryTest`：真实事务的 commit-before-launch/cancel；
- `SqliteCapabilitySnapshotRepositoryTest`：Snapshot 保留和 M2/M3 兼容；
- `SqliteHarnessM3MigrationTest`、`SqliteRuntimeExecutionRepositoryTest`：老库迁移、唯一约束和事件幂等；
- `FileSystemMcpServerCatalogTest`：Catalog 解析、Hash、路径约束；
- `CodexHarnessRuntimeGatewayTest`、`FileSystemRuntimeEvidenceStoreTest`：隔离执行、安全、Evidence 和清理；
- `HarnessExecutionControllerTest`、Feature Flag 测试：API 契约、脱敏和关闭装配；
- `ArchitectureTest`：Domain/Application/Infrastructure/Interface 与 Workflow 边界。

PMD 常量/集合容量修正后额外执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q -Dtest=McpAuthorizationPolicyTest test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q -Dtest=HarnessExecutionControllerTest test
```

结果：PASS。

## 4. M3 Spring 纵向流程

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  '-Dtest.excludedGroups=live,git-integration,process-integration' \
  -Dtest=HarnessM3FlowTest \
  -Djunit.jupiter.execution.parallel.enabled=false \
  test
```

结果：PASS，2/2。

成功链路实际经过：

```text
HTTP 创建 Run / 启动 ANALYSIS Attempt
→ 文件 Prompt/Skill/MCP Catalog
→ Runtime Preflight
→ Domain MCP 五维授权
→ 固化 M3.1 Snapshot
→ 保存 Run Execution 引用与 PREPARED Execution
→ commit
→ STARTING commit
→ 启动隔离 Codex Stub
→ STARTED/OUTPUT/SUCCEEDED Runtime Event
→ SQLite 状态与事件
→ 脱敏 JSONL Artifact
→ RuntimeExecution SUCCEEDED
```

取消链路实际经过：

```text
启动慢 Codex Stub
→ HTTP cancel
→ Run/Stage/Attempt CANCELLING + Execution CANCEL_REQUESTED
→ commit
→ 终止进程树
→ CANCELLED 回调
→ RuntimeExecution 与 Run 均 CANCELLED
```

两条流程均确认 Snapshot Hash 执行前后不变，终态后 Runtime 临时根为空。Stub 直接断言本次命令包含 MCP command/args/env_vars/required、READ allow、WRITE deny、启动/Tool 双 timeout 和 Repo Skill `skills.config`，并断言不存在 `CODEX_HOME/config.toml`；同 key 同 Stage 终态命中、canonical Location、跨 Stage 409 与不重复启动也在该流程中验证。

## 5. 默认后端快速回归

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

结果：PASS。

按项目默认配置排除 `live`、`git-integration`、`spring-flow`、`process-integration`；未调用真实 CLI、MCP、数据库服务或外部 Provider。

## 6. 完整 Spring Flow 回归

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  '-Dtest.excludedGroups=live,git-integration,process-integration' \
  '-Dgroups=spring-flow' \
  -Djunit.jupiter.execution.parallel.enabled=false \
  test
```

结果：PASS，33/33：

| 测试类 | 用例数 |
| --- | ---: |
| `ChatFlowTest` | 1 |
| `FeedbackFlowTest` | 5 |
| `HarnessFlowTest` | 1 |
| `HarnessM2FlowTest` | 1 |
| `HarnessM3FlowTest` | 2 |
| `ResumeSessionTest` | 5 |
| `ScheduledTaskTest` | 18 |

该组串行执行，避免既有 Spring Flow 使用 SQLite shared-cache 时并行争用表锁。并行运行曾出现 `ChatFlowTest` 表锁，单独运行与本次串行全组均通过，因此没有将共享测试数据库的并行限制误判为 M3 业务失败。

## 7. Secret、Evidence 与 API 脱敏证据

测试使用固定假 Secret `secret-value-never-persist`，只用于临时测试进程：

- Stub 断言 Secret 通过目标进程环境变量可见；
- 本次 CLI 参数只包含 `READER_API_KEY` 环境变量名，不包含 Secret，且隔离目录中不存在 `config.toml`；
- Snapshot JSON、最终 Prompt、Runtime API 都不含 Secret；
- Capability/Runtime API 不返回 MCP command 或 `secretReferences`；
- Runtime Event 的 summary/evidence reference 不含 Secret；
- SQLite 查询确认 Snapshot Prompt/MCP JSON 和 Runtime Event 中假 Secret 出现次数为 0；
- 原始 JSONL Artifact 把假 Secret 替换为 `[REDACTED]`；
- Artifact API/查询只使用 `artifact:*` 逻辑引用，不暴露物理路径；
- 终态后临时目录为空。

Adapter 不记录完整命令、完整进程环境或 Secret，因此没有需要额外扫描的 Runtime 明文日志载荷。

## 8. 失败、取消与清理证据

`CodexHarnessRuntimeGatewayTest` 覆盖：

- Codex 版本探测成功、格式错误、非兼容版本、超时、非零退出和输出上限；
- 祖先 `.codex/config.toml`、Repo Skill 未注册/Hash 变化/符号链接逃逸和 Snapshot 后工作区变化 fail-closed；
- 完整 MCP/Skill `-c` 参数、真实 `pid:<pid>` Handle 和 Preflight 后 spawn 失败清理；
- 成功执行后清理；
- spawn 启动失败后清理；
- `turn.failed`/非零退出的运行失败后清理；
- idle timeout、最大运行时限与输出上限 fail-closed；
- 取消后终止进程树并清理；
- Event Sink 回调异常后仍终止进程并清理；
- Evidence 持久化失败转为明确失败；
- 文件权限无法清理时返回 `temporaryConfigCleaned=false`，聚合保存 `cleanupStatus=FAILED`；
- `turn.failed + exit 0` 仍为 `FAILED`；
- 已提交取消意图后，即使终端信号/退出码表示成功也保持 `CANCELLED`；
- 取消后已进入输出管道的 OUTPUT 可以幂等接收，不覆盖取消终态。

`RuntimeExecutionTest` 进一步证明终态不能 `beginLaunch`，`LOST` 不能自动重启。M3 只冻结 `LOST` 对账语义，没有实现服务启动时的自动扫描/对账任务；该任务属于 M5。

## 9. PMD 与 Diff

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn pmd:check
```

结果：命令退出码 0，`BUILD SUCCESS`。项目配置 `failOnViolation=false`，报告仍有 453 条跨项目存量 PMD 违规，因此不能写成“PMD 全量零违规”。

对 `target/pmd.xml` 按 M3 新增文件过滤后，没有未抑制的 M3 新违规。两项命名规则采用有理由的局部抑制：

- `HarnessExecutionLauncher` 是非事务执行外壳，不是传统 Service Impl；
- `SqliteRuntimeExecutionQueryService` 按现有 SQLite CQRS 命名约定保留技术前缀。

PMD 仍报告的 `infra/harness` 项属于 M1/M2 存量文件，例如 Prompt/Skill Catalog 常量和既有 QueryService 命名，不在 M3 本轮扩大处理。

最终还执行：

```bash
git diff --check
```

结果：PASS。

## 10. 未执行项与残余风险

- 未运行真实 Codex/Claude CLI、真实 MCP Server、OpenAI/Anthropic Provider 或外部 HTTP；默认测试全部使用 Stub/Fake。
- 未执行显式 `live` 测试或目标机手工 CLI 验证；目标机 Codex 0.145.0 仍需在 M4 真实试点前验证 Prompt、Skill、MCP 与取消合同。
- 未实现自动重启对账任务；M3 已保存状态/事件并禁止自动重放，当前需要人工将无法确认的执行归类为 `LOST`，自动扫描与恢复属于 M5。
- 未执行 `mvn package`、`mvn verify`、服务重启、部署、Git push 或真实业务需求验收。
- 未新增前端 Playwright 场景；M3 新增的是后端 Execution API，完整四阶段页面与浏览器纵向流程属于 M4。
- Harness 仍默认关闭；本报告没有修改 `agent-paths.yml`、`data/` 敏感文件或本地生产数据库。

## 11. 重新验收完成清单

以下重新验收证据已全部补齐：

- [x] Domain 红测：required 固化、Tool allow/deny、双 timeout、Resource fail-closed、Repo Skill 信任和稳定 Hash。
- [x] Infrastructure 红测：版本探测的成功/失败/超时/输出上限/不兼容版本，祖先 `.codex/config.toml` 和 Repo Skill 扫描/禁用/TOCTOU。
- [x] CLI Stub：直接断言 command/args/env_vars/required/startup timeout/tool timeout/enabled/disabled/skills.config 的每个 `-c` 参数和实际 `pid:<pid>` Handle。
- [x] Application/Interface：同 key 同 Stage 各状态幂等、不同 Stage 冲突、同步启动失败真实状态和 canonical Location。
- [x] 真实 SQLite：Snapshot + Execution 绑定后经过 Runtime 回写、Artifact/Gate/Approval/取消更新，断言两行与 Attempt 引用不变。
- [x] M3 Spring 纵向 Stub、聚焦测试、默认测试、串行 Spring Flow、ArchitectureTest、PMD 和 `git diff --check` 全部通过。

重新验收保留初次实现的历史基线，并以本节完整门禁作为 M3 完成判定；没有用局部 PASS 替代退出结论。
