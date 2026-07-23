# Harness M2 完成记录

> 状态：Completed；M3.0 持久化缺口已于 2026-07-23 修复
> 完成日期：2026-07-23
> Feature Flag：`agent.harness.enabled=false`

## 1. 结论

M2 已完成 Harness 的 Prompt/Skill 能力平面。处于 `RUNNING` 的 Stage Attempt 可以从管理员配置的文件 Catalog 热读取四阶段 Prompt Pack 与 Skill 包，经 Domain Policy 完成信任、阶段、Runtime、显式选择、技术标签、一层依赖、冲突和文件/命令授权求交，再把 Prompt Parts、资源 Hash、选择/拒绝原因和最终 Hash 固化为不可覆盖的 `CapabilitySnapshot`。

M2 不启动 Agent、不执行 Skill 脚本、不挂载 MCP，也不把“Skill 已选中”解释成“命令已授权”。Codex Runtime Adapter、MCP Registry、Runtime Execution、取消和对账属于 M3。

2026-07-23 的 M3 设计复核曾发现一个跨 Repository 测试缺口：旧 `HarnessRunRepository.update()` 删除并重插 Attempt 时，会触发 `harness_capability_snapshot` 外键的 `ON DELETE CASCADE`。因此 M2 当时的 Snapshot 独立保存、恢复和 `saveIfAbsent` 结论成立，但尚未证明后续 Run 更新也会保留 Snapshot。该问题已在 M3.0 通过真实 SQLite 红测复现，改为 Stage/Attempt 增量持久化后转绿；Artifact、Gate、Approval、取消更新现在均保持 Snapshot 存在且 Hash 不变。M3 的完整 Snapshot + RuntimeExecution 保留证据仍在重新验收，详见 [M3 记录](../m3/README.md)。

## 2. 分层与领域边界

| 责任 | 层/对象 | 说明 |
| --- | --- | --- |
| Skill 信任、阶段/Runtime 兼容、选择、依赖、冲突 | Domain `SkillSelectionPolicy` | Application 不遍历 Manifest 重组规则 |
| 文件/命令能力请求与 Grant 求交 | Domain `CapabilityRequest`、`CapabilityGrant`、`StageCapabilityPolicy` | 最终授权还受 Stage 上限约束 |
| Prompt Pack/Skill 不变量 | Domain `PromptPack*`、`SkillManifest`、`SkillPackage` | 不依赖 Path、YAML、Spring 或 JDBC |
| 固定 Prompt 顺序与 Hash | Domain `HarnessPromptAssembler` | 输出不可变 Parts、最终 Prompt 和 SHA-256 |
| Attempt 绑定与 Snapshot Hash | Domain `CapabilitySnapshot` | Hash 不含 Run ID、Attempt 编号和创建时间，内容相同即可稳定复现 |
| 用例编排 | Application `HarnessCapabilityServiceImpl` | 先查旧 Snapshot，再加载 Catalog、调用 Policy/Assembler、`saveIfAbsent` |
| YAML、路径、符号链接、原始字节 Hash | Infrastructure `FileSystem*Catalog` | 每次解析重新扫描，不缓存文件内容 |
| Snapshot 生命周期 | Domain `CapabilitySnapshotRepository` + Infra SQLite 实现 | `(run_id, stage, attempt_number)` 主键禁止覆盖 |
| 管理预览 | Application QueryService + Infra SQLite 投影 + Interface Controller | API 返回 DTO/视图，不返回半截聚合 |

应用层唯一的条件判断是 Repository 幂等编排：如果 Attempt 已有 Snapshot，直接返回旧值且不再读取 Catalog。Stage 是否允许固化、Runtime 是否可用、能力是否授权均由 Domain 判定。

## 3. Prompt Pack 1.0.0

内置资源位于：

```text
src/main/resources/harness/prompt-packs/
├── analysis/1.0.0/
├── design/1.0.0/
├── implementation/1.0.0/
└── deployment/1.0.0/
```

每个包均包含 `manifest.yml`、`system.md`、`task.md`、`output-contract.md` 和 `gate-hints.md`。Manifest Schema 版本为 `1`，必需资源缺失、重复角色、未知 Stage、多个版本同时命中、绝对路径、`..` 逃逸或符号链接逃逸均 fail-closed。

Prompt 固定装配顺序为：

```text
PLATFORM_SAFETY
→ ENVIRONMENT_GUARDRAIL
→ STAGE_CONTRACT
→ STAGE_SYSTEM
→ STAGE_TASK
→ STAGE_GATE_HINTS
→ SELECTED_SKILLS
→ UPSTREAM_ARTIFACTS
→ CURRENT_INPUT
→ OUTPUT_CONTRACT
```

每个 Part 保存来源、原始内容和 SHA-256；最终 Prompt 保存完整文本与 Hash。Markdown 首尾换行属于原始资源内容并参与 Hash，不做静默裁剪。

`AGENTS.md` 和 `CLAUDE.md` 不被自动扫描。未在 Manifest 声明时它们不进入 Package Hash；若 Manifest 显式声明则 Catalog 拒绝，以免 Harness 与 CLI 原生规则重复注入。

## 4. Skill Catalog 与选择规则

内置平台 Skill：

| Stage | 默认 Skill | 主要技术标签 |
| --- | --- | --- |
| ANALYSIS | `domain-modeling-audit@1.0.0` | `java`、`ddd` |
| DESIGN | `java-ddd-design@1.0.0` | `java`、`ddd` |
| IMPLEMENTATION | `java-tdd@1.0.0` | `java`、`spring` |
| DEPLOYMENT | `release-verification@1.0.0` | `release`、`spring` |

选择优先级固定为 Stage 默认、用户显式、技术标签、一层必需依赖。同一 ID 出现多个被选版本、缺失依赖、超过一层依赖、循环依赖、冲突 Skill、Stage 不兼容或 Runtime 不兼容都会阻断解析。

信任来源由 Catalog 根配置决定，不信任 Manifest 自报值：

- 平台根固定为 `PLATFORM`；
- 管理员批准的用户根固定为 `APPROVED_USER`；
- 工作区根固定为 `WORKSPACE`，还必须在本次 Snapshot 请求中按 Skill ID 显式批准。

完整 Package Hash 至少覆盖 Manifest、`SKILL.md` 入口和 Manifest 声明资源；Catalog 每次调用重新读取，因此新 Attempt 能发现文件变化。

## 5. 文件与命令授权

Skill Manifest 可以声明 `FILE/READ`、`FILE/WRITE` 和 `COMMAND/EXECUTE` 逻辑能力。有效授权为显式 Grant 与 Stage 策略的交集：

| Stage | M2 允许进入有效集合的能力 |
| --- | --- |
| ANALYSIS / DESIGN | 文件只读 |
| IMPLEMENTATION | 文件读写、逻辑命令 `mvn-test` |
| DEPLOYMENT | 文件只读、逻辑命令 `mvn-verify` |

未显式 Grant 返回 `NOT_GRANTED`；虽有 Grant 但超出 Stage 上限返回 `STAGE_POLICY_DENIED`；两者同时满足才返回 `EXPLICITLY_GRANTED`。M2 只固化逻辑能力，不执行命令；Runtime 是否能强制这些能力将在 M3 再做 Preflight。

## 6. Snapshot、存储与不变性

SQLite 新增：

```text
harness_capability_snapshot
```

主键为 `(run_id, stage, attempt_number)`，并外键关联 `harness_stage_attempt`。Snapshot 保存 Runtime、Environment、Policy Version、Prompt Pack 身份/Hash、各资源 Hash、Skill 身份/Package Hash、选择与拒绝原因、能力决策、Prompt Parts、最终 Prompt、Prompt Hash、Snapshot Hash 和创建时间。

Resolver 顺序为：

```text
校验当前 RUNNING Attempt
→ 查询 Attempt 旧 Snapshot
→ 若存在则原样返回且不读 Catalog
→ 热读取 Prompt/Skill Catalog
→ Domain 选择、依赖、冲突与授权求交
→ Domain 固定装配 Prompt
→ SQLite save-if-absent
→ 返回管理预览
```

Repository 使用 `INSERT OR IGNORE` 配合主键处理并发重复固化；竞争失败时读取并返回第一个 Snapshot，禁止用后到内容覆盖旧证据。

M2 交付时上述结论只覆盖 Snapshot Repository 自身，旧 HarnessRun 聚合更新方式可能级联删除 Snapshot。M3.0 已改为 Stage/Attempt 增量更新，并补齐“Artifact/Gate/Approval/取消后 Snapshot 仍存在且 Hash 不变”的真实 SQLite 回归；本段保留原缺口的发现背景，不再代表当前实现风险。

## 7. 管理 API 与页面

新增 ADMIN API：

```text
POST /api/harness/runs/{runId}/stages/{stage}/capability-snapshot
GET  /api/harness/runs/{runId}/stages/{stage}/attempts/{attemptNumber}/capability-snapshot
```

解析失败返回稳定 422 `code`；Snapshot 不存在返回 404。`/api/harness` 继续受 ADMIN Filter 保护，Feature Flag 关闭时 Controller、Application Service、Catalog 和 SQLite Adapter 均不注册。

管理页位于 `/admin/harness.html`。页面展示 Prompt Pack/Skill Package Hash、选择和拒绝原因、文件/命令能力决策、Prompt Parts、最终 Prompt、Prompt Hash 与 Snapshot Hash；浏览器不重新执行选择或授权规则。

## 8. 配置与部署边界

开发态默认根：

```yaml
agent:
  harness:
    prompt-pack-root: src/main/resources/harness/prompt-packs
    platform-skill-root: src/main/resources/harness/skills
    approved-user-skill-root: ""
    workspace-skill-root: ""
```

这些 Catalog 必须是运行时可读文件目录。打包/JAR 部署不能依赖源码相对路径，应通过 `AGENT_HARNESS_*_ROOT` 指向管理员维护的外置目录。敏感信息不得放进 Catalog；M2 Manifest 不支持 Secret 值。

## 9. 退出门禁证据

| 门禁 | 证据 |
| --- | --- |
| 四阶段 Prompt Pack Schema/必需资源 | `FileSystemPromptPackCatalogTest` |
| 不同 Stage 自动选中不同 Skill | `StageCapabilityPolicy` + `HarnessPromptAssemblerTest` |
| Workspace Skill Run 级批准 | `SkillSelectionPolicyTest` |
| 版本、依赖、循环、冲突、Runtime | `SkillSelectionPolicyTest` |
| 路径/符号链接逃逸、原生规则文件隔离 | `FileSystemPromptPackCatalogTest`、`FileSystemSkillCatalogTest` |
| Prompt 固定顺序与稳定 Hash | `HarnessPromptAssemblerTest` |
| Snapshot SQLite 独立恢复与 save-if-absent 不可覆盖 | `SqliteCapabilitySnapshotRepositoryTest` |
| Application 只编排 | `HarnessCapabilityServiceImplTest`、`ArchitectureTest` |
| API、错误映射、Feature Flag | `HarnessCapabilityControllerTest`、`HarnessCapabilityFeatureFlagTest` |
| 管理页纯展示映射 | `harness-utils.spec.ts` |
| 资源变化只影响新 Attempt | `HarnessM2FlowTest` |

完整命令、红绿证据和工具链边界见 [`test-report.md`](test-report.md)。

## 10. 回滚与下一步

运行期回滚仍只需保持：

```yaml
agent:
  harness:
    enabled: false
```

关闭后不注册 Harness Bean；不主动清理 SQLite Snapshot、RuntimeExecution 或 Artifact。M3.0 增量持久化基线与 M3 Runtime 合同已完成重新验收；下一阶段按[里程碑](../03-milestones.md)进入 M4 四阶段纵向切片。
