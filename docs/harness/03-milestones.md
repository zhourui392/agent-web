# 研发交付 Harness 建设阶段与里程碑

> 状态：M0—M3 已完成；下一阶段为 M4 四阶段纵向切片与 MVP 发布
> 最后更新：2026-07-23
> 目标态：[研发交付 Harness 目标架构](01-target-harness-architecture.md)
> MVP 边界：[研发交付 Harness 首版能力范围](02-mvp-capabilities.md)
> 当前阶段记录：[M3 MCP 与 Runtime 执行平面](m3/README.md)

## 1. 路线原则

本路线按“先验证关键不确定性，再建立领域内核，最后打通真实纵向流程”推进。里程碑不以写了多少类或页面作为完成依据，而以可演示结果和退出门禁为准。

在团队人数、目标环境和 CLI 版本未确定前，本文不承诺日历日期。每个里程碑完成评估后，再结合实际吞吐制定排期。里程碑顺序和依赖是确定的，时间估算需要在 M0 后补充。

执行原则：

- 每个里程碑只引入下一步真实需要的抽象；
- 有业务分支的 Java 改动严格执行先测试见红、最小实现见绿、重构保绿；
- 首先运行匹配风险的最小测试，不默认打包、重启或部署；
- Feature Flag 默认关闭，不影响现有 Chat、Workflow 和 Diagnose；
- 每个里程碑都有演示场景、测试证据、文档和回滚方式；
- 生产部署能力不与 MVP 同时开放。

## 2. 路线总览

```mermaid
flowchart LR
    M0[M0 关键决策与 Spike] --> M1[M1 Harness 领域内核]
    M1 --> M2[M2 Prompt 与 Skill 装配]
    M2 --> M3[M3 MCP 与 Runtime 适配]
    M3 --> M4[M4 四阶段纵向切片 MVP]
    M4 --> M5[M5 自动门禁与恢复增强]
    M5 --> M6[M6 部署治理与第二 Runtime]
    M6 --> M7[M7 生产加固与 GA]
```

| 里程碑 | 目标 | 可见交付 | 退出后状态 |
| --- | --- | --- | --- |
| M0（已完成） | 消除 Codex/MCP/存储关键不确定性 | ADR、Spike、Fixture、受控样例 | 可以安全开始领域实现 |
| M1（已完成） | 建立 Run/Stage/Artifact/Approval 领域内核 | API 可创建和查询 Run，状态可持久化恢复 | 有控制平面，无真实 Agent 执行 |
| M2（已完成） | 完成阶段 Prompt 和 Skill 动态装配 | 可预览 Capability Snapshot 和 Prompt Hash | Prompt/Skill 能力平面可用 |
| M3（已完成） | 回补 Snapshot 持久化并完成只读 MCP、RuntimeExecution 和 Codex Adapter | 单阶段 Agent 在提交执行记录后，仅通过 Snapshot 对应的单次 CLI 覆盖使用指定 Skill/MCP | 受控 Runtime 执行平面可用 |
| M4 | 先完成四阶段能力，再做真实纵向验收 | 一个真实需求完成本机部署验证 | MVP 发布候选 |
| M5 | 增强自动 Gate、重启对账和审计 | 失败、重试、失效、恢复场景稳定 | 可供更多测试项目试用 |
| M6 | 第二 Runtime、部署/回滚治理 | Claude/Codex 双 Runtime，受控环境发布 | 具备受控生产试点前提 |
| M7 | 安全、性能、运维与产品化 | SLO、告警、保留策略、安全评审 | GA 候选 |

M0—M4 构成首版范围。M5—M7 属于目标态演进，不应阻塞 MVP 真实验证。

## 3. M0：关键决策与技术 Spike

> 状态：Completed，2026-07-22；完成记录见 [`m0/README.md`](m0/README.md)。

### 3.1 目标

在修改 Harness 业务代码前，用最小可丢弃实验确认当前 Codex CLI 的真实能力和边界，冻结首版合同。Claude 不属于首版 Runtime 范围。

### 3.2 交付物

- Harness 术语、Stage Contract 和 Artifact Schema 初稿；
- 覆盖四阶段合同的受控样例和可重复 Fixture；
- CLI 兼容性矩阵；
- Prompt/Skill/MCP 临时配置 Spike；
- Runtime 取消、超时和结构化事件 Fixture；
- Artifact 存储 ADR；
- Codex 首发 Runtime 决策记录和兼容性 ADR；
- MCP 首版安全策略 ADR；
- Feature Flag、权限和测试分组方案；
- 现有 Workflow/AgentRun 复用与隔离说明。

### 3.3 必须验证的问题

1. 当前 Codex 安装版本如何为单次运行使用隔离配置。
2. Codex 如何明确请求或启用一个指定 Skill。
3. MCP Server 能否按单次 Run 挂载，是否支持 Tool Allowlist。
4. 当 Runtime 不能细粒度限制 Tool 时，如何实现整服不挂载。
5. Runtime Execution ID、取消、超时和退出状态是否可稳定归一化。
6. Artifact 是采用 SQLite、小文件存储还是混合实现。
7. 本机部署使用哪些预配置命令，如何判断版本一致。

### 3.4 测试与演示

- 使用临时目录和无敏感凭据的 Echo/Fake MCP；
- 启动 CLI，证明允许阶段能访问 MCP、禁止配置下不能访问；
- 指定一个只读 Skill，证明实际 Runtime 读取或执行了它；
- 模拟 CLI 超时和取消，收集事件 Fixture；
- 不修改生产配置，不部署服务。

### 3.5 退出门禁

- [x] Codex Runtime 已有证据支持 Prompt、Skill、只读 MCP 和取消。
- [x] 无法强制的权限有明确 fail-closed 方案。
- [x] 首版 Stage Contract 与 Artifact Schema 无阻断问题。
- [x] Artifact、Runtime、MCP/Skill 三个 ADR 已批准。
- [x] 受控样例和 Fixture 覆盖四阶段合同及至少一个 Skill/MCP，不依赖尚未提供的真实需求。
- [x] MVP DoD 根据 Spike 结果更新，未保留未经验证的 CLI 假设。

## 4. M1：Harness 领域内核与持久化

> 状态：Completed，2026-07-23；完成记录见 [`m1/README.md`](m1/README.md)。

### 4.1 目标

建立独立 Harness 限界上下文，使阶段状态、Artifact 版本、Approval 和失效传播由 Domain 管理，而不是由 Controller/Application 的条件分支拼装。

### 4.2 交付物

- `HarnessRun` 聚合及 Stage/Attempt 状态模型；
- Stage Contract 值对象；
- Artifact Descriptor、GateResult、Approval 模型；
- Run、Artifact 元数据等 Domain Repository；
- SQLite 实现和幂等 Schema 迁移；
- Harness Run QueryService 投影；
- 创建、查看、开始、批准、拒绝、重试、取消的管理 API；
- Feature Flag 与 ADMIN 鉴权；
- 审计事件最小模型。

### 4.3 关键业务规则

- 上一阶段未通过不能启动下一阶段；
- Approval 绑定 Artifact Hash；
- 修改上游 Artifact 会使后续阶段失效；
- Retry 创建新 Attempt，不覆盖旧证据；
- 终态不允许普通执行；
- 同一 Run 只有一个可写 Attempt；
- 非法状态转换由 Domain 拒绝。

### 4.4 TDD 顺序

1. Domain 状态转换和失效传播测试先红；
2. 最小聚合实现见绿；
3. Repository 接口和 Application 编排测试；
4. 真实 SQLite Repository 测试；
5. Controller 鉴权、参数和状态码测试；
6. 架构测试确认没有新增分层违规。

### 4.5 演示

不调用真实 Agent：通过 API 创建 Run，上传模拟 Artifact，通过/拒绝 Gate，批准需求阶段，重启应用后仍能查询完整时间线。

### 4.6 退出门禁

- [x] 所有已实现命令的合法/非法状态转换有 Domain 测试。
- [x] Application 只编排 Repository/Gateway，不承载业务状态判断。
- [x] SQLite 重启恢复和幂等迁移测试通过。
- [x] ADMIN 鉴权、幂等键和错误映射测试通过。
- [x] Feature Flag 关闭时现有接口行为不变。
- [x] 没有复用或污染现有 Workflow 状态语义。

## 5. M2：Prompt Pack 与 Skill 动态装配

> 状态：Completed，2026-07-23；完成记录见 [`m2/README.md`](m2/README.md)。

### 5.1 目标

建立第一部分能力平面：阶段启动前能从可信 Catalog 解析 Prompt Pack 和 Skill，并生成不可变 Capability Snapshot。

### 5.2 交付物

- 四阶段 Prompt Pack `1.0.0`；
- Prompt Pack Manifest 与解析器；
- Skill Manifest、Catalog 和 Package Hash；
- Stage 默认 Skill、用户显式 Skill 和技术标签选择；
- 一层依赖、冲突和 Runtime 兼容性校验；
- 文件/命令能力请求模型；
- Capability Resolver 与 Snapshot 持久化；
- Prompt 装配清单、Parts 和最终 Hash；
- 管理 API/页面中的 Snapshot 预览和选择理由。

### 5.3 设计约束

- Prompt/Skill 的文件读取属于 Infrastructure；选择和授权规则属于 Domain Policy；Application 只串联。
- 必需 Prompt 资源失败必须阻断，可选知识上下文才允许降级。
- `AGENTS.md`/`CLAUDE.md` 继续由 CLI 原生加载，Harness 默认不重复注入。
- Skill 被选择不等于其脚本可执行；脚本必须经过命令策略授权。
- 当前 [`FileSlashCommandScanner`](../../src/main/java/com/example/agentweb/infra/FileSlashCommandScanner.java) 保持现有 UI 功能，新的 Skill Catalog 不用 `SlashCommand` 领域对象代替。

### 5.4 TDD 顺序

1. Skill 适用阶段、信任、冲突和依赖规则的 Domain 测试；
2. Capability Resolver Application 编排测试；
3. `@TempDir` Catalog 解析、路径逃逸、Hash 和热发现测试；
4. Prompt 固定顺序、缺失必需资源和 Hash 稳定性测试；
5. Snapshot API 和页面纯逻辑测试。

### 5.5 演示

分别启动 ANALYSIS 和 IMPLEMENTATION 的模拟 Attempt：两者加载不同 Prompt Pack/Skill，展示选择原因、被拒能力、Package Hash 和最终 Prompt Hash；修改 Skill 后旧 Attempt Snapshot 保持不变，新 Attempt 使用新 Hash。

### 5.6 退出门禁

- [x] 四阶段 Prompt Pack 通过 Schema 和快照测试。
- [x] 至少两个不同阶段自动选中不同 Skill。
- [x] 未批准工作区 Skill 不可被启用。
- [x] Skill 版本冲突、循环依赖和路径逃逸会 fail-closed。
- [x] 同一输入与资源生成稳定 Snapshot Hash。
- [x] 资源变化不会改变已运行 Attempt。

设计复核曾发现：M2 独立 Snapshot Repository 的不可覆盖测试已通过，但旧 `HarnessRunRepository.update()` 删除并重插 Attempt 时会触发 Snapshot 外键级联删除。该问题不推翻 M2 的 Prompt/Skill 领域能力结论，已在 M3.0 通过真实 SQLite 红测复现，并改为 Stage/Attempt 增量持久化后关闭。

## 6. M3：MCP 注册与 Codex Runtime Adapter

> 状态：Completed，2026-07-23；完成性审计曾重新打开 Runtime 合同，修订实现与全部退出门禁现已通过，详见 [`04-m3-detailed-design.md`](04-m3-detailed-design.md)与 [`m3/test-report.md`](m3/test-report.md)。

### 6.1 目标

完成能力平面与执行平面的最小闭环：先修复不可变 Snapshot 的持久化基线，再让一个 Stage 使用包含 Prompt、Skill、只读 MCP 和 Runtime Enforcement 的完整 Snapshot，由 Codex CLI Runtime 在执行记录提交后受控执行。

### 6.2 交付物

- M3.0 HarnessRun 子表增量持久化与 Snapshot 保留回归；
- Harness 配置迁入 `config/harness` 并按 Catalog、Runtime、Security 拆分；
- MCP Registry 配置格式和解析器；
- MCP 信任、阶段适用性和 READ/WRITE 分类规则；
- Secret Reference 和 Redaction；
- Runtime 兼容性与可强制权限检查；
- 完整 `M3.1` Capability Snapshot Schema：MCP required、Tool allow/deny、启动/调用超时、Repo Skill 清单、实际 Runtime 版本与 Enforcement 全部参与 Hash，并提供老库迁移；
- Domain `RuntimeExecution` 聚合、Execution Permit 和取消中间态；
- `app/harness/port` 统一 Agent Execution Spec 与 `AgentRuntimeGateway`；
- Infrastructure Codex Runtime Adapter；
- 仅从 Snapshot 生成的单次 `-c` MCP/Skill 覆盖、隔离临时环境与清理；
- 实际 Codex 版本的有界探测和兼容清单 fail-closed；
- 工作目录祖先 `.codex/config.toml` 阻断、Repo Skill 信任校验及 `skills.config` 显式禁用；
- Execution 真实 PID/进程组 Handle 和跨 Stage 幂等冲突语义；
- RuntimeExecution/Event 持久化、事件归一化、超时、取消和 `LOST` 对账语义基线；自动重启对账任务在 M5 完成；
- Fake MCP、CLI Stub 和真实 CLI `live`/手工验证边界说明。

### 6.3 安全约束

- 首版仅启用只读 MCP；
- MCP 启动配置只能来自管理员 Catalog；
- Secret 不进入 Snapshot、Prompt、日志和 API；
- 无法限制敏感能力时整个 Server 不挂载；
- MCP Server 必须同时下发 `enabled_tools`、`disabled_tools`、`required`、`startup_timeout_sec` 和 `tool_timeout_sec`；
- `--ignore-user-config` 下不得依赖 `CODEX_HOME/config.toml` 注入能力，必须使用本次 CLI `-c` 覆盖；
- 工作目录祖先存在 `.codex/config.toml`、Repo Skill 未注册/Hash 不匹配或实际 Codex 版本不兼容时 fail-closed；
- Secret 明文只在 Infrastructure Runtime Adapter 启动期解析，不返回 Domain/Application；
- Runtime 启动前必须提交完整 Snapshot 和 `PREPARED` Execution 记录；
- 有活动 Runtime 时先提交 `CANCELLING/CANCEL_REQUESTED`，再终止进程；
- 取消/重试不能重复执行未确认副作用。

### 6.4 TDD 顺序

1. 保留真实 SQLite Snapshot 级联删除回归；新增 Snapshot 与 Execution 一起绑定后的 Run 更新保留红测；
2. MCP required、Tool allow/deny、双 timeout、Resource fail-closed 和 Runtime Enforcement Policy 的 Domain 红测；
3. Snapshot `M3.1` Schema/Hash、临时 M3 只读兼容和老库迁移测试；
4. Preflight 的版本探测、`.codex/config.toml` 阻断、Repo Skill 清单/Hash 和启动前复核测试；
5. CLI Stub 直接断言单次 `-c` 参数、隔离环境、实际 PID Handle、事件、超时、取消、输出上限和全部清理路径；
6. Application 相同 key 原资源返回、不同 Stage 冲突、同步启动失败真实状态和 canonical Location 测试；
7. Fake MCP 的允许/禁止阶段、required 失败关闭、Tool timeout 和 Secret Redaction 测试；
8. 真实事务 commit-before-launch 证明及 M3 Spring 纵向 Stub 回归；
9. 真实 CLI 兼容性验证保留为显式 `live`/手工步骤，不进入默认快速集，并在 M4 真实试点前执行。

### 6.5 演示

- ANALYSIS Stage 的 `M3.1` Snapshot 选择只读 MCP，实际 Codex 命令只包含该 Snapshot 派生的 Server、Tool allow/deny、required 和双 timeout `-c` 覆盖；
- 未选择或合同未授权该 MCP 时，实际命令中不存在此 Server；工作区 Repo Skill 被显式禁用，祖先 `.codex/config.toml` 会在 Preflight 阻断；
- 取消正在运行的 Stage 时先观察到持久化取消意图，再终止进程，最终保留执行和清理证据；
- API 展示 MCP ID、版本、能力和 Hash，但不展示凭据。

### 6.6 退出门禁

- [x] Artifact/Gate/Approval/取消等 Run 更新后，旧 Snapshot、Execution 与 Attempt 引用仍存在且 Hash/ID 不变。
- [x] `M3.1` Domain/Codec 可一次性保存 Prompt、Skill/Repo Skill 清单、MCP required/allow/deny/双 timeout 和 Runtime Enforcement，稳定 Hash 与旧 Schema 兼容测试通过，实际 CLI 事实由 Preflight 固化。
- [x] Runtime 启动发生在 Snapshot 与 `PREPARED` Execution 事务提交之后。
- [x] 受控 Codex Stub 直接验证单次 `-c` Prompt/Skill/MCP 合同与取消，不以临时 `config.toml` 代替真实加载证明。
- [x] 禁止阶段、项目 `.codex/config.toml` 和 Snapshot 外 Repo Skill/MCP 没有能力泄漏。
- [x] Secret 扫描和 Redaction 测试通过。
- [x] 实际 CLI 版本不兼容或 Runtime 不支持 Tool allow/deny、Resource/Skill 隔离时启动前失败。
- [x] required/allow/deny/双 timeout 均下发，Execution 保存实际 Runtime 版本和 PID/进程组 Handle。
- [x] 幂等命中返回原资源真实状态和 canonical Location，同 key 不同 Stage 返回冲突。
- [x] 取消意图先提交，退出码 0 不会覆盖取消语义；终态不明的执行不会自动重放。
- [x] 默认测试全部使用 Stub/Fake，不访问真实外部 CLI/MCP。
- [x] 临时配置在成功、启动失败、运行失败、超时和取消后均清理；清理失败留下明确状态。

## 7. M4：四阶段纵向切片与 MVP 发布

### 7.1 目标

先用受控样例完成四阶段功能集成；功能达到可验收状态后，再接收一个真实需求完成端到端交付，并满足[首版 Definition of Done](02-mvp-capabilities.md#11-mvp-definition-of-done)。真实需求不是 M0—M3 的前置条件，但真实纵向验收没有通过时不能关闭 M4。

### 7.2 交付物

- 四阶段 Application 用例编排；
- Artifact Schema 和确定性 Gate；
- `WAITING_INPUT` 问题与回答流程；
- Approval UI/API；
- 实现阶段 Git 基线、Diff、命令与测试证据；
- 本机部署命令模板、Preflight 和业务验收；
- 最小管理页面和运行时间线；
- 最终追踪矩阵与交付报告；
- 启用、关闭、恢复、限制和故障排查文档。

### 7.3 分阶段演示里程碑

#### M4-A：需求分析通过

- Agent 读取原始需求和只读代码/MCP 上下文；
- 生成 Requirement/AC/Impact Artifact；
- Schema Gate 通过；
- 人工批准绑定当前 Hash。

#### M4-B：方案设计通过

- 只消费已批准需求版本；
- 输出分层落点、变更清单和测试策略；
- 每个 Requirement/AC 映射到设计和测试；
- 人工批准设计 Hash。

#### M4-C：TDD 实现通过

- 记录 Git 基线；
- 至少一条业务规则留下测试先红后绿证据；
- 只执行允许的聚焦测试；
- 保存 Diff、Changed Files 和追踪矩阵；
- 人工批准待部署版本。

#### M4-D：部署验证通过

- 只部署到 local；
- 部署前校验 Git/Artifact Hash 未变化；
- 独立 Approval 后执行预配置命令；
- 技术健康和业务 AC 均通过；
- 生成最终报告。

### 7.4 测试与演示

- Domain/Application/Infrastructure/Interface 最小测试集；
- Playwright Happy Path；
- Playwright 或应用测试覆盖 Gate 拒绝、修订和重试；
- 应用重启恢复演示；
- 真实 CLI + 只读 MCP 的受控手工验证；
- 本机 local 部署演示，测试服务器和生产环境明确拒绝。

### 7.5 退出门禁

- [ ] MVP DoD 全部完成，没有以“后续补”绕过的必需项。
- [ ] 一个真实需求生成完整追踪矩阵和最终报告。
- [ ] 四阶段中任一上游 Artifact 修改都会使下游结果正确失效。
- [ ] 部署失败时安全停止，不会自动重复部署。
- [ ] 服务重启后未完成 Run 可恢复，外部动作需要人工对账。
- [ ] 最小页面无需改数据库即可完成全流程。
- [ ] Feature Flag 关闭回归通过。
- [ ] 已记录首版限制和下一阶段优先问题。

## 8. M5：自动门禁、恢复与审计增强

### 8.1 目标

把 MVP 中依赖人工核查但可以确定性判断的事项自动化，并覆盖复杂失败场景。

### 8.2 交付物

- Requirement/AC/Design/Test/Validation 机器可读追踪检查；
- 测试报告解析器，而不只依赖进程退出码；
- 架构、敏感文件、凭据和无关 Diff Gate；
- Runtime Reconciliation Worker；
- 工作空间租约和孤儿执行治理；
- Artifact 保留、归档和清理任务；
- 完整 Audit Timeline 和导出；
- Stage/Runtime 指标与基础告警。

### 8.3 退出门禁

- [ ] 关键 Gate 不依赖模型自评。
- [ ] 重复事件、超时回调和应用重启不会产生重复副作用。
- [ ] 工作空间并发写冲突能提前阻断。
- [ ] 审计记录可重建 Run 的主要状态变化。
- [ ] Artifact 清理不删除仍被当前 Run/Approval 引用的版本。

## 9. M6：第二 Runtime 与部署治理

### 9.1 目标

让 Claude/Codex 均通过统一 Harness 端口工作，并把部署、回滚和外部写能力纳入独立治理。

### 9.2 交付物

- 第二个 CLI Runtime Adapter；
- Runtime 契约测试套件和兼容矩阵；
- MCP 能力在两种 Runtime 上的差异降级策略；
- 测试/预发布 Deployment Adapter；
- 自动回滚状态机和幂等策略；
- 外部写 MCP 的动作级 Approval；
- 可选的需求系统/代码托管/发布系统集成；
- 部署后观察窗口和失败判定。

### 9.3 退出门禁

- [ ] 同一 Harness Definition 可在两种 Runtime 上完成合同测试。
- [ ] Runtime 差异不会泄漏进 Domain。
- [ ] 部署和回滚都有独立幂等 ID 与证据。
- [ ] 外部写能力不能通过 Stage Prompt 或 Skill 自行开启。
- [ ] 测试/预发布失败可自动或半自动回到已知安全版本。

生产仍默认关闭；是否进入生产试点由单独安全评审决定。

## 10. M7：生产加固与 GA

### 10.1 目标

完成多用户安全、容量、运维和合规要求，使 Harness 可以作为稳定产品能力发布。

### 10.2 交付物

- 生产 Approval Policy 和角色分离；
- Secret Store/Broker 对接；
- Artifact 数据分级、保留、导出和删除策略；
- 并发、配额、限流和容量测试；
- SLO、Dashboard、告警和 Runbook；
- 灾难恢复和数据库备份验证；
- Prompt Injection、路径逃逸、命令注入、MCP 供应链安全评审；
- 生产灰度、紧急关闭和回滚演练；
- 用户文档和管理员运维文档。

### 10.3 GA 退出门禁

- [ ] 安全评审的高风险问题清零或有正式豁免。
- [ ] 生产权限与测试权限隔离并经过演练。
- [ ] SLO、告警和 Runbook 已由非开发人员演练。
- [ ] 数据备份恢复和 Artifact 恢复验证通过。
- [ ] Feature Flag 可在不重启或受控重启下快速关闭 Harness 入口。
- [ ] 至少多个不同类型试点需求完成，失败/回退数据可接受。
- [ ] 已明确支持的 CLI/MCP 版本范围。

## 11. 运行时四阶段自身的里程碑

前面的 M0—M7 是“建设 Harness 产品”的里程碑。对于每一次具体 Harness Run，四个业务阶段也有独立里程碑：

| Run 里程碑 | 必需结果 | 通过证据 | 未通过处理 |
| --- | --- | --- | --- |
| R1 需求基线冻结 | Requirement、AC、范围、风险明确 | Artifact Schema + 人工批准 Hash | 补充问题或新 Analysis Attempt |
| R2 设计基线冻结 | 领域/分层/变更/测试/部署方案明确 | 追踪映射 + 架构检查 + 人工批准 | 修订设计；需求变化则退回 R1 |
| R3 待部署版本冻结 | 实现与测试完成，Diff 明确 | TDD 证据 + 聚焦测试 + Diff 审批 | 新 Implementation Attempt |
| R4 部署验收完成 | 正确版本部署且技术/业务验证通过 | 制品 Hash + 健康检查 + AC 结果 | 回滚/人工处理，Run 不得标完成 |

任何上游基线的新版本都会使依赖它的后续 Run 里程碑失效。

## 12. 每个建设里程碑的统一交付门禁

每个 M 里程碑关闭前统一检查：

### 12.1 架构

- 新业务规则是否位于 Domain；
- Application 是否只编排；
- 写 Repository 是否位于 Domain；
- 读模型是否使用 QueryService/DTO；
- 是否新增 app → infra 直接依赖；
- 是否误把 Harness 特殊状态塞进现有 Workflow。

### 12.2 TDD 与测试

- 有分支的业务逻辑是否有先红证据；
- Domain 是否不 Mock；
- Application 是否只 Mock 端口；
- Infrastructure 是否用 `@TempDir`/SQLite/Stub 隔离真实外部系统；
- Controller 是否覆盖参数、状态码和鉴权；
- 是否选择了与风险匹配的最小测试。

### 12.3 安全

- 能力是否默认拒绝；
- Secret 是否只以引用出现；
- 路径、命令、MCP 和环境是否白名单；
- 是否有 Prompt Injection 越权测试；
- 部署/回滚是否独立批准；
- 日志和 Artifact 是否脱敏。

### 12.4 可恢复与审计

- 状态改变是否持久化；
- 重试是否保留旧 Attempt；
- 幂等键是否覆盖副作用入口；
- 应用重启后行为是否明确；
- Prompt/Skill/MCP/Policy/Artifact/Version Hash 是否可追踪。

### 12.5 文档与演示

- 文档与真实行为一致；
- 配置、启用、关闭、限制和恢复说明完整；
- 演示使用可重复 Fixture；
- 未完成项和残余风险明确记录。

## 13. 依赖与关键路径

关键路径是：

```text
CLI/MCP Spike
→ Stage/Run 领域模型
→ Capability Snapshot
→ Runtime Adapter
→ Artifact/Gate/Approval
→ 四阶段纵向验证
```

可以在不破坏关键路径的前提下并行：

- Prompt Pack 内容设计与 M1 领域实现；
- 管理页面原型与 M1 API 契约；
- Fake MCP/CLI Fixture 与 M2 能力装配；
- Artifact Schema 与 QueryService 投影。

不能提前并行：

- 未完成 M0 就同时实现两种 CLI 的 MCP 适配；
- 未冻结状态不变量就实现 Controller 状态分支；
- 未完成 Snapshot 就开放动态 Skill/MCP；
- 未完成本机部署验证就开放测试服务器或生产发布；
- 未建立幂等和 Reconciliation 就自动重试有副作用动作。

## 14. 风险与里程碑控制

| 风险 | 最早暴露里程碑 | 控制方式 |
| --- | --- | --- |
| CLI 不支持单次运行隔离 MCP 配置 | M0 | Spike；必要时使用隔离 Home/Config，仍无法隔离则缩小 MVP |
| Skill 在 Claude/Codex 的语义不同 | M0/M2 | 统一 Manifest + Runtime Adapter；不把私有格式放入 Domain |
| Tool Allowlist 无法强制 | M0/M3 | 敏感 Server 整服不挂载；首版只读 MCP |
| Workflow 与 Harness 概念混用 | M1 | 独立限界上下文和 Repository；架构测试 |
| Application 出现状态业务判断 | M1 | 聚合行为测试先行；Application 测试只验证编排 |
| 工作区原有脏改动被误部署 | M4 | 创建 Run 时记录基线；无法区分时阻断部署 |
| 应用重启重复部署 | M4/M5 | Execution ID、幂等键、人工对账，不自动重放 |
| Artifact/日志泄漏 Secret | M3 起 | 引用式凭据、Redaction、大小和访问控制 |
| MVP 变成通用编排平台 | 全程 | 固定四阶段；按 MVP 不做清单控制范围 |
| 生产能力过早开放 | M4—M6 | 环境白名单；生产适配在独立安全评审后启用 |

## 15. 决策检查点

以下检查点需要用户/产品/架构共同确认：

1. **M0—M2（已完成）**：Codex/MCP 约束、Harness/Workflow 分离和 Prompt/Skill 能力平面已经确认。
2. **M3.0（已完成）**：增量持久化及 Snapshot/Execution/Attempt 引用完整回归均已通过真实 SQLite 验证。
3. **M3 Domain/M3.1 Snapshot（已完成）**：RuntimeExecution、取消中间态、MCP required/Tool allow-deny/双 timeout、Repo Skill 信任和新 Snapshot Hash 已通过分层测试。
4. **M3 Runtime（已完成）**：单次 `-c`、工作区旁路防护、实际版本/Handle、幂等返回、提交边界和清理证据已通过 Stub 与 Spring 纵向验证。
5. **M4 功能集成完成后**：选择真实需求，批准本机部署命令模板和回滚手册，再开始最终纵向验收。
6. **M4 结束**：根据真实试点决定 M5 优先级，避免按假设扩建。
7. **M6 结束**：单独决定是否进入生产试点；不因技术完成自动开放生产。

## 16. 推荐的下一步

M3 全部门禁已经关闭，下一步进入 M4 四阶段纵向切片：

1. 先实现 ANALYSIS → DESIGN → IMPLEMENTATION → DEPLOYMENT 的固定阶段编排，不扩成任意 DAG；
2. 补 `WAITING_INPUT`、Artifact 追踪矩阵、Git 基线/Diff、确定性 Gate 与部署前独立 Approval；
3. 使用受控样例完成四阶段功能集成后，再选择真实需求做本机纵向验收；
4. M4 真实试点前执行 Codex 0.145.0 的显式 `live`/手工 Prompt、Skill、MCP 与取消兼容性验证；
5. 保持 `agent.harness.enabled=false` 默认关闭，不提前开放测试/生产部署能力。
