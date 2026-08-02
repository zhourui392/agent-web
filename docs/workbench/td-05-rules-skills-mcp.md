# TD-05 Rules、Skills 与 MCP

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-01](td-01-runtime-capability-decoupling.md)、[TD-04](td-04-multi-repository-workspace.md)
> @author alex

## 1. 目标

为四阶段提供开箱即用、确定性、可追溯的 Phase Capability Profile。正常用户无需选择 Rules、Skills、MCP；
高级 Override 只影响下一轮，且不能覆盖平台安全规则、环境限制和 Repository Scope。

## 2. 概念边界

| 概念 | 归属 | 职责 |
| --- | --- | --- |
| Rule/Skill/MCP Definition | `domain.capability` | 公共版本、Hash、信任和兼容性事实 |
| Phase Capability Profile | `domain.workbench` | Workbench 某阶段默认请求和降级策略 |
| Capability Override | `domain.workbench` | 用户对某 Workbench/Phase 的可选覆盖 |
| Capability Binding | `domain.capability` | 已解析的不可变选择结果 |
| WorkbenchRunSnapshot | `domain.workbench` | 把 Binding 与 Run/Phase/Scope/Handoff 固化 |
| Catalog Adapter | `infra.capability` | 从可信文件目录读取定义和内容 |

Catalog 不包含 Phase 业务规则；Workbench 不直接读取文件或解析 YAML。

## 3. Profile 资源结构

```text
src/main/resources/workbench/profiles/
├── requirement-analysis/
│   ├── manifest.yml
│   └── rules.md
├── solution-design/
├── implement-test/
└── review-refactor/
```

Manifest 示例：

```yaml
schema: workbench-phase-profile@1
id: workbench-requirement-analysis
version: "1"
phase: REQUIREMENT_ANALYSIS
rules:
  - id: platform/read-only-analysis
    required: true
skills:
  - id: code-search
    required: true
  - id: requirement-analysis
    required: false
mcp_servers:
  - id: repository-query
    required: false
fallback:
  optional_skill_missing: CONTINUE_WITH_WARNING
  optional_mcp_missing: CONTINUE_WITH_WARNING
```

Profile 启动期/热读取时做 schema、唯一性、引用和 Hash 校验。无合法 Profile 的 Phase 标记不可运行，不在
Application 创建临时默认值。

## 4. 四阶段默认策略

| Phase | RunMode | Rules | Skill 意图 | MCP 上限 |
| --- | --- | --- | --- | --- |
| REQUIREMENT_ANALYSIS | 只读 | 核实事实、澄清范围、不得写代码 | 需求分析、代码检索、服务导航 | 只读 |
| SOLUTION_DESIGN | 只读 | 业务建模优先、方案比较、风险与回滚 | 架构、契约、数据、测试设计 | 只读 |
| IMPLEMENT_TEST | 可写 | 仓内规范、TDD、最小修改、命令证据 | 目标端开发、测试、受控本地操作 | 按明确授权 |
| REVIEW_REFACTOR | 默认只读 | 人工意见优先、不扩大范围 | Review 辅助、重构、回归测试 | 代码/测试为主 |

这些策略由 `PhaseCapabilityPolicy` 返回语义查询，例如 `maximumMcpAccess()`、
`requiresExplicitModifyIntent()`；Application 不按 Phase 写 switch 组合权限。

## 5. 合并与授权顺序

能力解析输入按以下优先级求交，不是后项覆盖前项：

```text
平台强制安全规则
∩ Environment Guardrail
∩ Repository Scope / Workspace Trust
∩ Phase Capability Profile
∩ Runtime Compatibility
∩ 管理员允许 Catalog
∩ Capability Override
= Resolved Capability Binding
```

Rules Prompt 顺序固定：

1. 平台安全；
2. 环境限制；
3. Repository Scope 与路径映射；
4. Phase Rules；
5. 仓库/Workspace 只能收紧的规范；
6. 用户 Override 中允许追加的非安全偏好。

Override 不能删除 mandatory Rule、提升 MCP access、加入未信任 Skill、扩大 writable roots 或改变 Runtime
credential mode。

## 6. Capability Override

允许：

- 从 Profile 可选集合/管理员 allowlist 中增加或移除 optional Skill；
- 从允许集合中选择 optional MCP；
- 追加受长度和内容策略约束的阶段偏好 Rule；
- 恢复默认。

禁止：

- 任意本地路径 Skill/MCP；
- MCP Secret/Token；
- shell 命令、环境变量和值；
- 关闭平台安全/环境规则；
- 把只读 MCP 改成写；
- 运行中修改 Snapshot。

Override 保存时校验一次，下一轮解析时按最新 Catalog 再校验。失效项返回 warning 并忽略；若失效项原来被
标记 required，则阻止 Run，不能替换成未知能力。

## 7. Snapshot 内容

`ResolvedCapabilityBinding` 保存：

```text
policyVersion
profileId / profileVersion / profileHash
rules[]: id / version / source / hash / mandatory / safeSummary
skills[]: id / version / source / packageHash / trustTier
mcpServers[]: id / version / definitionHash / access / transport
rejected[]: id / reasonCode
runtimeCompatibility
bindingHash
```

不保存：

- Secret 明文；
- credential 文件或用户 Home 内容；
- MCP Token；
- 未脱敏命令和完整外部 stderr。

工作区 Rule 正文只在本轮 Prompt 准备中读取；Snapshot 保存可追溯 Hash 和安全摘要。若审计要求保存完整规则，
必须先引入 Secret Scanner 和单独加密存储设计，MVP 不做。

## 8. Runtime Materialization

Runtime Adapter 把 Binding 转为 Provider 配置：

- 使用每 Run 临时 Home/配置目录；
- Skill 只从已解析 Catalog 物化，文件 Hash 必须匹配 Snapshot；
- MCP Server 只写必要定义，Secret 通过启动期 Reference 注入；
- Codex 使用一次性 `-c`/config，不改写用户级 `~/.codex`；
- Claude 适配器遵循同等临时配置和最小环境；
- 结束后清理临时目录，清理失败记录技术状态但不泄漏路径。

本机登录模式让 CLI 正常使用服务账户已有登录态，不读取、复制或记录认证文件内容。

## 9. API

### 9.1 Effective Profile

```http
GET /api/workbenches/{id}/phases/{phase}/capability-profile
```

返回：状态、Profile 版本、Rules 摘要、Skills/MCP、来源、required/optional、warning 和下一轮生效说明。

### 9.2 Override

```http
PUT /api/workbenches/{id}/phases/{phase}/capability-override
If-Match: <override-version>

{
  "optionalSkillIds": ["..."],
  "optionalMcpServerIds": ["..."],
  "additionalRule": "..."
}
```

运行中允许保存，响应必须带：

```json
{
  "version": 4,
  "effectiveFrom": "NEXT_RUN",
  "activeRunSnapshotHash": "..."
}
```

`DELETE` 恢复默认，也只影响下一轮。

## 10. 失败与降级

| 情况 | 行为 |
| --- | --- |
| optional Skill/MCP 不可用 | 按 Profile 固定策略继续并显示 warning |
| required 能力不可用 | `422 WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE` |
| Profile 损坏/缺失 | `503 WORKBENCH_PROFILE_UNAVAILABLE` |
| Override 版本冲突 | `409 WORKBENCH_CAPABILITY_VERSION_CONFLICT` |
| Override 尝试提升权限 | `403 WORKBENCH_CAPABILITY_ESCALATION_DENIED` |
| Runtime 不支持绑定能力 | `422 WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE` |
| Catalog 运行期变化 | 当前 Run 不变；下一轮重新解析并提示版本变化 |

## 11. 配置

```yaml
agent:
  workbench:
    capability:
      profile-root: ${AGENT_WORKBENCH_PROFILE_ROOT:src/main/resources/workbench/profiles}
      policy-version: workbench-policy@1
      max-additional-rule-chars: 4000
      hot-reload-enabled: true
```

Catalog 根使用 TD-01 的 `agent.capability.*`。

## 12. 测试

Domain 无 Mock：

- 四 Phase Policy、权限求交、required/optional、Override 不可提升权限；
- Binding Hash 与输入顺序无关；
- Profile/Override 版本变化不改变旧 Snapshot。

Application Mockito：

- Catalog → Policy → Runtime Preflight → Snapshot 的编排顺序；
- required 失败不创建 Run；
- optional 降级产生 warning；
- 运行中保存 Override 不更新 active Snapshot。

Infrastructure：

- Profile YAML/Markdown schema、路径、Hash、热更新；
- Skill/MCP trusted root、symlink、文件变化和 TOCTOU 校验；
- Runtime 临时配置不包含未选能力/Secret；
- 清理、输出脱敏和 credential reference。

Vitest/Playwright：

- 正常用户不打开设置也能运行；
- Drawer 展示来源/版本；
- Override 下一轮生效提示；
- required 能力失败给出可恢复原因。

## 13. 验收标准

- 四阶段默认 Profile 确定且版本化；
- 用户无需选择能力即可运行；
- Snapshot 可追溯实际 Rule/Skill/MCP 和 Hash；
- Override 不能提升权限且只影响下一轮；
- 必需能力失败时进程不会启动。
