# TD-01 公共 Runtime 与 Capability 解耦

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：无；这是 Workbench 实现的 Phase 0 门禁
> @author alex

## 1. 目标

在不改变现有 Chat/Harness 可观察行为的前提下，把 Agent 进程执行、Rules/Skills/MCP Catalog 和 Workspace
多仓库模型从 Harness 专用包中解耦。迁移完成后：

- Workbench 可以依赖中性 Runtime、Capability、Workspace 能力；
- Harness 在退役窗口内作为公共能力的消费者继续运行；
- 公共包不出现 `HarnessRun`、`HarnessStage`、Artifact、Gate、Approval 类型；
- 不创建一个充满 nullable 字段的“万能 Gateway”；
- Chat、Workbench、Harness 的业务准备各自保留，只有外部执行内核和 Catalog 事实共享。

## 2. 当前耦合清单

| 能力 | 当前位置 | 耦合问题 |
| --- | --- | --- |
| Codex 沙箱、命令、临时 Home、进程树、取消、JSONL | `infra.harness.CodexHarnessRuntimeGateway` | Harness Adapter 同时承担了公共执行机制与交付语义 |
| Runtime Preflight | `app.harness.port.RuntimePreflightGateway` | 参数直接使用 Harness Stage |
| Prompt Pack/Skill/MCP Catalog | `domain.harness`、`infra.harness` | 定义本身中性，但包和配置由 `agent.harness.enabled` 控制 |
| Capability Snapshot | `domain.harness.CapabilitySnapshot` | 绑定 Run/Stage/Attempt、Artifact Prompt 和 Gate 输入 |
| 多仓库模型 | `domain.harness.RepositorySelection` 等 | Workbench 直接引用会违反退役边界 |
| Chat Runtime | `app.agentrun.port.AgentGateway` | 能流式执行，但规格只覆盖单 workingDir 和 Chat resume 语义 |

## 3. 目标模块与依赖

```text
domain.workspace
  RepositorySelection / RepositoryScope / WorkspaceTopology
  RepositoryBaseline / WorkspaceSnapshot / WorkspaceSnapshotReference

domain.capability
  RuleDefinition / SkillPackage / McpServerDefinition
  CapabilityBinding / CapabilityAccess / CapabilityRisk
  CatalogTrust / CapabilityHash

app.runtime.port
  AgentExecutionPlan / AgentExecutionGateway / RuntimeEventSink
  RuntimePreflightGateway / RuntimeObservation

infra.runtime
  AgentProcessKernel
  RuntimeProcessRegistry
  RuntimeCommandFactory
  RuntimeWorkspaceMaterializer
  RuntimeEventDecoder
  RuntimeOutputRedactor
  RuntimeCredentialResolver

infra.capability
  FileSystemRuleCatalog / FileSystemSkillCatalog / FileSystemMcpServerCatalog

infra.workspace
  GitWorkspaceInspector / GitWorkspaceSnapshotGateway / ScopedPathResolver
```

允许的迁移期依赖：

```text
domain.harness → domain.workspace / domain.capability
app.harness → app.runtime.port
infra.harness adapter → infra.runtime kernel
```

禁止反向依赖：

```text
domain.workspace ↛ domain.harness
domain.capability ↛ domain.harness
app.runtime ↛ app.harness
infra.runtime ↛ infra.harness
Workbench 任意包 ↛ Harness 任意包
```

## 4. Runtime 合同

### 4.1 不使用万能可选参数

`AgentExecutionPlan` 由必需的组合对象构成：

```text
AgentExecutionPlan
├── executionIdentity
│   ├── executionId
│   ├── ownerId
│   └── originReference
├── runtimeSelection
│   ├── agentType
│   ├── runtimeVersionPolicy
│   └── credentialReference
├── promptPayload
│   ├── finalPrompt
│   ├── promptHash
│   └── historyDelivery
├── workspaceLayout
│   ├── primaryRepositoryRoot
│   ├── readableRoots
│   ├── writableRoots
│   └── sandboxMode
├── capabilityBindings
│   ├── skills
│   ├── mcpServers
│   └── ruleHashes
└── runtimeLimits
    ├── timeout
    ├── maxOutputBytes
    └── environmentAllowlist
```

所有集合创建时非空/空集合明确化，不用 null 表示“按默认猜测”。Chat/Harness/Workbench 分别负责把自身已
验证的业务事实转换为完整 Plan；公共 Runtime 只执行 Plan，不判断阶段是否允许写。

### 4.2 端口职责

```java
public interface AgentExecutionGateway {
    RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink);
    void requestStop(RuntimeHandle handle);
    RuntimeObservation observe(RuntimeHandle handle);
}

public interface RuntimePreflightGateway {
    RuntimePreflightResult inspect(RuntimePreflightRequest request);
}
```

- `start` 必须异步返回稳定 Handle；不能把 Process 暴露到 Application。
- `requestStop` 幂等，负责进程树终止但不改变业务聚合状态。
- `observe` 只返回技术事实，是否转成 INTERRUPTED/FAILED 由调用方领域生命周期处理。
- `RuntimeEventSink` 接收统一事件，不接受 Provider 原始 Secret 或未截断 stderr。

### 4.3 Runtime Kernel 拆分

| 组件 | 单一职责 |
| --- | --- |
| `AgentProcessKernel` | 启动、超时、输出限额、退出、进程树终止 |
| `RuntimeProcessRegistry` | executionId 与真实 Handle 的进程内映射 |
| `RuntimeCommandFactory` | 根据 Provider Plan 产生 `ProcessBuilder` token |
| `RuntimeWorkspaceMaterializer` | 准备临时 Home、配置和只允许的目录布局 |
| `RuntimeEventDecoder` | Provider JSONL/stream-json → 公共 Runtime Event |
| `RuntimeOutputRedactor` | 命令、环境、路径和输出脱敏/截断 |
| `RuntimeCredentialResolver` | 启动期解析 credential reference，不向内层返回明文 |
| `RuntimeCleanup` | 临时目录和内存 Secret 清理，记录 cleanup 结果 |

`CodexHarnessRuntimeGateway` 迁移后只保留 Harness Execution Spec → 公共 Plan 的 Adapter；Workbench 使用
独立 Mapper，不调用 Harness Adapter。

## 5. Capability 公共模型

### 5.1 Catalog 只表达事实

公共 Catalog 返回版本化定义：

```text
RuleDefinition(id, version, source, contentHash, mandatory, summary)
SkillPackage(id, version, source, packageHash, compatibleRuntimes, trustTier)
McpServerDefinition(id, version, source, definitionHash, access, risk, transports)
```

Catalog 不知道 Workbench Phase 或 Harness Stage。消费者提供 `CapabilityUseCase` 与自己的 Policy：

- Workbench：`PhaseCapabilityPolicy`；
- Harness：保留 `StageCapabilityPolicy`；
- Chat：只有显式启用相关能力时才建立自己的 Policy。

选择算法的公共部分只处理信任、版本、兼容性、required/optional 和 deny；“某阶段默认选什么”归消费者领域。

### 5.2 Snapshot 分层

不直接把 Harness `CapabilitySnapshot` 改成公共类型：

- 公共 `ResolvedCapabilityBinding`：规则/Skill/MCP 的不可变选择结果和 Hash；
- Workbench `WorkbenchRunSnapshot`：绑定 Phase、Repository Scope、Handoff、Prompt；
- Harness `CapabilitySnapshot`：继续绑定 Attempt、Artifact、Enforcement；
- 两者都组合公共 Binding，但互不引用。

## 6. Workspace 模型迁移

现有以下类行为保留并迁包：

| 当前类 | 目标类/包 | 调整 |
| --- | --- | --- |
| `domain.harness.RepositorySelection` | `domain.workspace.RepositorySelection` | 去掉 Harness Javadoc |
| `WorkspaceTopology` | `domain.workspace.WorkspaceTopology` | Hash schema 保持兼容 |
| `RepositoryBaseline` | `domain.workspace.RepositoryBaseline` | 临时 legacy adapter 留在 Harness 侧 |
| `WorkspaceSnapshot` | `domain.workspace.WorkspaceSnapshot` | purpose 改为值对象/枚举参数，不保留 Harness 常量 |
| `WorkspaceSnapshotReference` | `domain.workspace.WorkspaceSnapshotReference` | 保持不可变引用语义 |
| Change Evidence 类型 | `domain.workspace` | Harness Artifact 转换放回 Harness Domain Adapter |

先迁类和测试，再逐调用方改 import；不要复制两套同名模型。Hash schema 与序列化字段在迁包中不改变，避免
把纯依赖调整混入数据语义变更。

## 7. 配置迁移

新增中性配置：

```yaml
agent:
  capability:
    rule-root: ${AGENT_CAPABILITY_RULE_ROOT:src/main/resources/capability/rules}
    platform-skill-root: ${AGENT_CAPABILITY_SKILL_ROOT:src/main/resources/capability/skills}
    approved-user-skill-root: ${AGENT_CAPABILITY_USER_SKILL_ROOT:}
    mcp-server-root: ${AGENT_CAPABILITY_MCP_ROOT:src/main/resources/capability/mcp-servers}
  runtime:
    temp-root: ${AGENT_RUNTIME_TEMP_ROOT:data/runtime}
    max-output-bytes: ${AGENT_RUNTIME_MAX_OUTPUT_BYTES:8388608}
```

迁移窗口内：

- Harness 旧变量作为 Harness Adapter 的兼容别名；
- 中性组件本身不读取 `agent.harness.*`；
- 同时配置新旧值时新值优先，并记录不含路径明文的 deprecation warning；
- Workbench 只读取新配置，禁止回退 Harness 配置。

## 8. 实施顺序

1. 为现有 Catalog、Runtime Preflight、Codex 命令和 Workspace Hash 补齐行为锁定测试。
2. 迁移 `domain.workspace`，只改包与依赖，测试保绿。
3. 迁移 `domain.capability` 和 `infra.capability`，Harness 改为依赖公共 Catalog。
4. 从 `CodexHarnessRuntimeGateway` 按组件逐个抽取 Kernel，每次仍由原 Gateway 调用。
5. 引入 `app.runtime.port`，让 Harness Adapter 映射到公共 Plan。
6. 增加 Workbench Adapter Contract Test，尚不接 UI。
7. 增加 ArchUnit 门禁后才能开始 Workbench Domain 开发。

## 9. 架构测试

新增规则：

- `domain.workspace..`、`domain.capability..` 不依赖 `domain.harness..`；
- `app.runtime..` 不依赖 `app.harness..`；
- `infra.runtime..`、`infra.capability..` 不依赖 `infra.harness..`；
- `..workbench..` 不依赖任何 `..harness..`；
- Provider SDK/CLI 类型仍不得进入 Domain、Application、Interface；
- Workbench Interface 只能访问 `app.workbench` 与公共 DTO，不访问 Repository。

## 10. 验收标准

- Harness 默认测试和真实受控 Fixture 行为不变；
- Chat 默认测试不变；
- 公共包在 `agent.harness.enabled=false` 时仍可装配给 Workbench；
- Workbench 编译依赖图中不存在 Harness 包；
- Workspace Hash 迁包前后相同；
- Codex Plan 能明确证明主仓库、附加仓库、Sandbox 和能力绑定；
- Runtime Kernel 的命令、环境、取消、超时、输出限额和清理均有 Contract Test。
