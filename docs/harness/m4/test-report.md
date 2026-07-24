# Harness M4 自测报告

> 当前判定：自动化门禁 PASS；M4 Exit NOT COMPLETE
> 执行日期：2026-07-24
> JDK：OpenJDK 21.0.11
> Node.js：24.14.0
> 受控 Runtime：Shell/CMD Codex Stub、真实 SQLite、真实临时 Git、真实 `codex-cli 0.145.0` + 本地确定性 Provider/MCP Fixture
> 真实 Provider：BLOCKED；M0 默认登录态 Smoke 返回 HTTP 401，隔离 Runtime 尚未获得受控凭据；未保存凭据或摘要

## 1. 判定

M4 功能集成、分层测试、真实 SQLite/Git、浏览器 Happy Path、失败/修订/失效、重启恢复和 Feature Flag
关闭回归全部通过。默认自动化不访问真实 Codex Provider，也不执行真实服务部署。

目标机默认 Codex 登录态访问真实 Provider 返回 HTTP 401，而隔离 Harness 不读取用户登录态。受控
Provider Credential Reference 的启动期注入与脱敏已通过测试，但当前尚未提供有效凭据，因此在线
Prompt/四阶段 Skill 和一个真实需求的 local 纵向交付仍无通过证据。本报告据此保持 M4 未关闭；受控
Stub 的 2/2 Playwright 和本地 Provider 兼容测试不能替代真实退出项。

## 2. M4 TDD 红—绿证据

M4 业务分支按 Domain → Application → Infrastructure → Interface/E2E 顺序实施：

1. **固定四阶段与 Artifact 来源**：Domain 测试先因原始需求冻结、批准输入和失效行为缺失而失败；聚合行为完成后转绿。
2. **`WAITING_INPUT`**：测试先证明缺少同 Attempt 问答状态；Run/Stage/Attempt 同步暂停和恢复后转绿。
3. **Artifact 修订和下游失效**：旧版本、Approval Hash 和已通过下游未失效的场景先红；版本递增、Approval 失效和传播规则完成后转绿。
4. **确定性 Gate**：测试先因策略不存在而失败；Schema、Requirement/AC、设计/测试覆盖、TDD、Git、部署和追踪规则收回 Domain 后转绿。
5. **Git 基线与实际证据**：真实临时 Git 测试先因 Gateway 不存在而失败；仓库身份、branch/HEAD/clean/diff/untracked Hash 和精确 Diff 完成后转绿。
6. **Runtime Artifact Bundle**：测试先因输出 Schema/Bundle 不存在而失败；`--output-schema`、`--output-last-message`、类型集合和 Stage 校验完成后转绿。
7. **实际命令观察**：Agent 自报 RED/GREEN 不能证明真实执行的测试先红；JSONL command observation 与 TEST_EVIDENCE 对账完成后转绿。
8. **独立部署 Approval**：缺少当前输入 Hash、local 和独立 Approval 时部署被拒；`DeploymentPermit` 完成后授权场景转绿。
9. **部署模板和 Process Gateway**：未知/test/production 模板、Shell 字符串、失败后继续执行场景先红；token 列表、local allowlist、顺序停止和脱敏完成后转绿。
10. **DeploymentExecution**：Git 二次 Preflight、幂等键、终态和人工对账测试先红；独立聚合和 Repository 完成后转绿。
11. **重启恢复**：PREPARED/RUNNING 外部动作重启后仍悬空的测试先红；Runtime LOST、Deployment RECONCILIATION_REQUIRED 和禁止重放后转绿。
12. **最终报告**：追踪矩阵缺失 Requirement/AC/Design/Test/Implementation/Deployment 映射时 Gate 失败；完整映射后通过。
13. **管理 API/UI**：列表、时间线、问题、Gate、Approval、部署、Artifact 下载和报告入口先缺失；Controller/View/页面完成后转绿。
14. **浏览器纵向流程**：Happy Path、跨语言确认按钮、Gate 失败提示和 Artifact 合同曾逐项失败；稳定 `data-test` 与正确 Bundle 后 2/2 转绿。
15. **Runtime 清理异常**：不可遍历临时子目录触发 `UncheckedIOException`、监控线程无终态；捕获后主结果保留、`temporaryConfigCleaned=false` 明确记录。
16. **DDD 完成性复核**：测试先因 Bundle 校验/Outcome API 不存在而编译失败；状态解释下沉 `RuntimeExecution`、跨聚合改为不可变 Outcome 后转绿。
17. **真实 MCP Fixture 协议**：Vitest 先因 Server 文件不存在而超时失败；无 Secret stdio Server 完成后，`read_fixture` 可调用且未知写工具返回 `-32602`。
18. **Provider Credential Reference**：聚焦测试先因 `providerCredentialReference` 配置不存在而编译失败；实现后证明凭据只注入显式配置的单次 Codex 进程，并从事件与 Runtime Evidence 脱敏；若误把 `OPENAI_API_KEY` 加入通用继承白名单，Preflight 会失败关闭。
19. **在线 Provider live 门禁**：Vitest 先因脚本不存在而得到退出码 127；实现后，无引用、缺失引用值和非法引用名均在 Codex 启动前以退出码 2 失败关闭。成功路径固定读取四阶段真实 Prompt/Skill、使用隔离 Home 与结构化输出；因当前未提供凭据，尚未执行。

Domain 测试不 Mock Domain；Application 只 Mock Repository/Gateway 并使用真实聚合；Infrastructure 使用
真实 SQLite、`@TempDir` 文件系统或临时 Git；浏览器流程使用独立 Harness profile 和受控 Codex Stub。

## 3. 后端聚焦与默认回归

Harness/M4 聚焦命令：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  -Dtest='*Harness*Test,*CapabilitySnapshot*Test,*Mcp*Test,*RuntimeExecution*Test,\
*RuntimeEvidence*Test,*Deployment*Test,*WorkspaceBaseline*Test,ArchitectureTest' \
  test
```

结果：PASS。

默认快速全集：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

最终结果：PASS，214 个测试类，1331 tests，0 failures，0 errors，0 skipped。默认组排除
`live`、`git-integration`、`spring-flow` 和 `process-integration`。

第一次全集在并行负载下出现一次与 M4 无关的
`StreamProcessWatchdogTest.activity_should_reschedule_idle_timeout` 160ms 时序窗口失败；该类单独复跑通过，
第二次 1331 tests 全集通过。相关源码不在 M4 Diff 内，未为追绿修改产品或放宽断言；仍把它记录为既有
短时钟测试的抖动风险。

新增聚焦红—绿修订又单独执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  -Dtest=RuntimeExecutionTest,HarnessRunRuntimeExecutionTest,DeploymentExecutionTest,\
HarnessRuntimeEventServiceTest,HarnessExecutionRecoveryServiceTest,HarnessDeploymentPreparerTest \
  test
```

结果：PASS。

## 4. Spring Flow 与真实 Git

M1/M2/M3 Harness Spring 兼容流程：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  '-Dtest.excludedGroups=live,git-integration,process-integration' \
  -Dtest=HarnessFlowTest,HarnessM2FlowTest,HarnessM3FlowTest \
  -Djunit.jupiter.execution.parallel.enabled=false \
  test
```

结果：PASS，`HarnessFlowTest` 1、`HarnessM2FlowTest` 1、`HarnessM3FlowTest` 2。

真实临时 Git：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
mvn -q \
  '-Dtest.excludedGroups=live,spring-flow,process-integration' \
  -Dgroups=git-integration \
  -Dtest=ProcessWorkspaceBaselineGatewayTest \
  test
```

结果：PASS。测试实际执行 Git init/commit、dirty tracked/untracked、精确 Diff、内容 Hash 和非仓库 fail-closed。

`HarnessExecutionRecoveryPersistenceTest` 位于默认快速组，使用真实 SQLite 连续恢复两次，证明：

- Runtime 最终为 LOST 且只有一个 LOST Event；
- Run/Stage 持久化为 FAILED；
- 两个未完成 Deployment 均为 RECONCILIATION_REQUIRED；
- `findUnfinished()` 清空，Execution 行数不增加，不创建替代动作。

## 5. 前端与浏览器

Vitest：

```bash
cd tests && npx vitest run
```

结果：最终 9 files、110 tests 全部通过。其中 MCP Fixture 协议测试直接启动 Node stdio Server，在线
Provider 脚本测试只覆盖无凭据失败关闭，不访问 Codex 或网络。

Harness Feature Flag 开启：

```bash
cd tests
node scripts/e2e-clean.js
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
npx playwright test -c playwright.harness.config.ts
```

结果：2 passed，约 2.1 分钟：

1. 四阶段 Happy Path，包括原始需求、`WAITING_INPUT` 同 Attempt、三个 Agent Stage、独立 local 部署 Approval、部署 Gate、最终 Approval、Run COMPLETED 和追踪报告。
2. ANALYSIS Gate 失败、Attempt 2 修订、DESIGN 通过、ANALYSIS Attempt 3 重试以及 DESIGN INVALIDATED。

Harness Feature Flag 关闭：

```bash
cd tests
node scripts/e2e-clean.js
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
npx playwright test -c playwright.config.ts e2e/harness-disabled.spec.ts
```

结果：1 passed；Harness API 返回 404、管理导航无入口、直达页面显示关闭提示。

Playwright 登录口令只通过当前测试进程环境注入；未写入仓库、报告或测试数据库明文字段。第一次未设置
`AGENT_E2E_ADMIN_PASSWORD` 时全局登录门禁按设计失败，注入临时测试值并清理专用数据库后重跑通过。

## 6. PMD、语法和 Diff

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q pmd:check
```

结果：命令退出码 0。`target/pmd.xml` 已实际生成，PMD 6.53.0 没有
`Unsupported class file major version 65`、`PMDException` 或其他引擎错误。

项目配置 `failOnViolation=false`，报告包含 520 条跨项目提示/存量违规，其中也包含 M4 接口 Javadoc、
命名、Magic Constant、事务 rollback 规则等非阻断提示。因此本报告只证明 PMD 引擎完成扫描，不声明
“零违规”或“PMD 规则全部通过”。命名抑制只允许用于明确表达 CQRS 技术实现或提交后 Launcher 的类。

最终静态门禁还包括：

```bash
node --check src/main/resources/static/js/admin/pages/harness.js
node --check src/main/resources/static/js/admin/harness-utils.js
node --check src/main/resources/harness/mcp-servers/local-readonly-fixture/1.0.0/server.mjs
sh -n tests/e2e/fixtures/harness/harness-codex-stub.sh
bash -n scripts/harness-m4/verify-cli-contract.sh
git diff --check
```

这些命令将在提交前再次执行，并以最终 worktree 输出为准。

## 7. 安全和失败证据

- Feature Flag 默认 false，关闭时 Bean/API/UI 全部不可用。
- Runtime 使用最小环境、受控 sandbox、单次 Snapshot 参数、输出上限、双 timeout 和进程树取消。
- MCP Server 必须由 Catalog、Stage、Grant、环境 allowlist 和 Enforcement 同时允许；写 Tool/Resource fail-closed。
- 新增 `local-readonly-fixture` 无 Secret、无文件/网络能力，只有静态 READ Tool。
- Deployment Catalog 默认没有可执行模板；只允许 local，test/production 和未知模板拒绝。
- 部署失败立即停止，不自动 rollback，不自动重试。
- Artifact、Runtime Evidence、命令输出和部署输出在落盘/响应前脱敏。
- 重启恢复不重放 Runtime 或 Deployment；不确定部署必须人工对账。
- E2E DB、Artifact、Playwright report、`node_modules` 和认证 storage state 均为忽略产物，不进入提交。

## 8. 真实 Codex 验收

已确认本机命令为 `codex-cli 0.145.0`，并支持 Harness 冻结合同所需的：

```text
--ignore-user-config
--ignore-rules
--ephemeral
--json
--output-schema
--output-last-message
--sandbox
-C
-c
```

没有读取、复制或修改用户级 Codex/Claude 认证目录，也没有在报告中保存 Key、Token 或其摘要。M0 使用
默认登录态执行最小真实 Provider Smoke 时返回 HTTP 401；由于 Harness 的隔离目录不会读取该登录态，
本次新增 `AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE`，只在显式配置时从服务进程环境解析逻辑引用、注入
单次子进程 `OPENAI_API_KEY` 并加入脱敏。

真实 CLI 的本地兼容合同执行：

```bash
scripts/harness-m0/self-test.sh all
scripts/harness-m4/verify-cli-contract.sh
```

结果分别为 11/11 与 2/2 PASS，直接证明：

- 真实 Codex 加载显式 Skill，只调用允许的只读 MCP Tool；
- required 初始化失败关闭、Tool timeout 可观察；
- 进程组取消会停止 Codex/MCP 子进程，即使退出码为 0 仍由取消意图决定终态；
- M4 `local-readonly-fixture.read_fixture` 在 ANALYSIS 兼容命令可调用；Server 不挂载时 Tool 对模型不可见。

上述运行使用隔离 Home 和本地确定性 Responses Provider，不访问在线模型。当前环境没有提供受控在线
凭据，因此以下项目仍为未完成：

- [ ] 在线 Provider Prompt；
- [ ] 在线 Provider 下四阶段真实 Skill 注入；
- [ ] 一个真实需求的四阶段、独立 local Approval、部署、业务 AC 和最终报告。

在线成功路径已经固化为 `scripts/harness-m4/verify-online-provider.sh`；它读取
`AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE` 指向的环境变量值，绝不读取用户认证目录。当前只执行了三种
无凭据失败关闭测试，不能据此勾选在线 Provider 项。

## 9. 残余限制

- Runtime Bundle 在“子进程退出并写完临时文件、数据库事件回调提交前应用崩溃”的窗口可能丢失；恢复会标 LOST，不自动重放。
- 重启恢复当前是安全关闭未知动作，M5 才增强持久接收箱、自动扫描、审计重建和更细人工对账。
- M4 只有 Codex Adapter、只读 MCP 和 local 部署；Claude、写 MCP、test/production、自动 rollback 和 Secret Broker 不在首版。
- 仓库不提供真实部署模板；管理员必须对目标项目提供独立审核的外置 Catalog。
- PMD 全局报告不是零违规；`failOnViolation=false` 的退出码不能作为零违规证明。

## 10. M4 退出结论

当前已完成：受控四阶段功能、自动化门禁、页面、恢复、Feature Flag、限制和运维文档。

当前未完成：在线 Provider 下的四阶段 Prompt/Skill，以及一个真实需求的 local 纵向交付。MVP DoD 与
M4 Exit 因此仍未全部满足，分支不能仅凭本报告标记 M4 Completed。
