# TD-04 多仓库工作区

> 状态：Accepted / Implemented（TD-11 已吸收后续动态阶段变化）
> 日期：2026-08-01
> 前置：[TD-01](td-01-runtime-capability-decoupling.md)
> @author alex

## 1. 目标

为 Workbench 提供“一个 Workspace Root + 一个或多个显式仓库 + 一个主仓库”的统一模型。Workspace Root
是发现和访问上界，不是 Agent 的默认可写目录；未选 sibling 仓库不得出现在 Runtime 写根、文档查看或
文件事件中。

## 2. 公共领域模型

`domain.workspace` 提供统一的仓库选择与授权边界模型。Workbench 使用：

```text
RepositorySelection
  用户提交的规范化相对路径集合和主仓库

RepositoryScope
  已经基础设施真实路径校验后的不可变授权边界
  workspaceRoot / primary / RepositoryReference[] / scopeHash

WorkspaceTopology
  稳定的工作区成员身份与 topologyHash

WorkspaceSnapshot
  某次真实 Git 观察：Topology + RepositoryBaseline[] + stateHash
```

`RepositorySelection` 不接触文件系统；Infrastructure 解析真实路径后，用受信任的
`ResolvedRepository` 参数创建 `RepositoryScope`。Application 不根据 getter 重做路径和集合规则。

## 3. Repository Scope 不变量

1. 至少一个、最多配置上限个仓库；默认上限 50。
2. 主仓库必须且只能是已选仓库之一。
3. Repository Key 是规范化 POSIX 相对路径；拒绝空、绝对路径、`.`、`..`、回退和重复。
4. 每个 Repository Root 的 `toRealPath()` 必须位于 Workspace Root 的真实路径内。
5. 不接受符号链接仓库入口；扫描不使用 `FOLLOW_LINKS`。
6. 不允许同时选择互相包含的两个独立仓库；Submodule 首版不展开。
7. 每个仓库必须能解析 Git top-level 和 HEAD。
8. 同一真实仓库只允许一个 Repository Key。
9. Scope 创建后不可修改；需要改变集合时创建新 Workbench。
10. 单仓库只是集合大小为 1，不建立分支流程。

## 4. Inspect 与 Create 两阶段

### 4.1 Inspect

```text
POST /api/workbench/workspaces/inspect
→ WorkspacePathPolicy 验证允许根
→ Manifest/根仓库/有界扫描产生候选
→ Git Inspector 返回只读候选 DTO
→ 不持久化 Workbench/Snapshot
```

候选来源优先级：

1. `.agent-web.yml` 的 `workbench.repositories` 明确清单；
2. Workspace Root 自身是 Git 仓库；
3. 固定深度扫描 `.git` 目录或 worktree `.git` 文件。

Manifest 只提供候选和默认选择，不绕过服务端安全校验。

### 4.2 Create

用户确认后提交明确 Repository Keys。Create 必须重新解析所有真实路径和 Git 状态，不能信任 Inspect 结果或
客户端提交的 branch/HEAD/dirty。

```text
RepositorySelection.of(request)
→ WorkspaceScopeGateway.resolve(selection)
→ WorkspaceSnapshotGateway.capture(CREATE)
→ Workbench.create(scope, snapshot.reference())
```

Inspect 与 Create 之间仓库变化时，以 Create 结果为准；拓扑不匹配返回 409/422，不静默换仓库。

## 5. 扫描安全边界

推荐配置：

| 配置 | 默认值 |
| --- | ---: |
| `agent.workbench.workspace.discovery-max-depth` | 2 |
| `agent.workbench.workspace.max-repositories` | 50 |
| `agent.workbench.workspace.inspect-timeout-seconds` | 30 |
| `agent.workbench.workspace.capture-timeout-seconds` | 60 |
| `agent.workbench.workspace.git-command-timeout-seconds` | 10 |
| `agent.workbench.workspace.max-git-output-bytes` | 8388608 |

扫描器：

- 使用有界 `Files.walk` 且不跟随链接；
- 发现仓库根后剪枝；
- 忽略 `target`、`build`、`node_modules`、`data`、`.worktrees` 等非业务候选目录；
- Git 命令通过固定 `ProcessBuilder` token 执行，不接受 shell/客户端命令；
- 以真实 top-level 去重；
- 超时、输出超限和 Git 启动失败使用明确错误，不返回半截候选作为成功。

## 6. Snapshot

Workbench 使用 Snapshot 的目的不是 Gate，而是：

- 记录创建时各仓库 branch/HEAD/dirty，向用户说明已有修改；
- 在单次 Run 开始时证明拓扑仍有效；
- 在写 Run 结束时生成确定性的文件变化事件；
- 高影响操作授权时绑定目标状态 Hash。

建议 purpose：

```text
WORKBENCH_CREATE
WORKBENCH_RUN_START
WORKBENCH_RUN_END
HIGH_IMPACT_PREFLIGHT
```

公共 Snapshot 接受 `SnapshotPurpose` 值对象，不认识消费者枚举。Hash 继续采用 framed canonical hashing，
仓库和文件顺序不影响结果。

### 6.1 采集稳定性

多仓库顺序采集后再次核对每个仓库的 branch/HEAD：

- 首次变化：整体重试一次；
- 再次变化：返回 `WORKSPACE_CAPTURE_UNSTABLE`；
- 不在不稳定 Snapshot 上启动写 Runtime。

## 7. Runtime Workspace Layout

```text
RuntimeWorkspaceLayout
├── workspaceRoot（审计/显示，不是 writable root）
├── primaryRepository
│   └── codex -C <primary-root>
├── additionalRepositories
│   └── --add-dir <selected-root>，按 Repository Key 排序
├── readableRoots
└── writableRoots（由 RunMode 决定）
```

| RunMode | 主仓库 | 其他已选仓库 | 未选 sibling | 父目录 |
| --- | --- | --- | --- | --- |
| `DISCUSS_READ_ONLY` | 只读 | 只读 | 不授权 | 不授权 |
| `MODIFY_WORKSPACE` | 可写 | 可写 | 不授权 | 不授权 |

`--add-dir` 与 sandbox 的实际边界必须进入 Codex Compatibility Matrix。目标版本无法证明隔离时，写 Run
fail-closed；不能退化成把父目录设为 `-C`。

Claude Adapter 必须达到同一 Scope Contract 才能在 Workbench 标记可用，不按 Provider 能力静默放宽。

## 8. 文件与事件身份

统一使用：

```json
{
  "repositoryKey": "agent-web",
  "relativePath": "src/main/java/.../X.java"
}
```

- API/事件不把绝对路径作为文件身份；
- 多仓库同名 `pom.xml` 不冲突；
- Runtime 观察到的绝对路径先通过 `ScopedPathResolver` 反解为结构化身份；
- 无法归属已选仓库的写事件是 `REPOSITORY_SCOPE_VIOLATION`，写 Run 失败；
- 只读工具访问未授权路径同样拒绝并记录安全事件。

## 9. API

### 9.1 Inspect Request

```json
{
  "workspaceRoot": "/home/ubuntu/workspace"
}
```

Response：

```json
{
  "workspaceRootDisplay": "/home/ubuntu/workspace",
  "inspectionToken": "short-lived-opaque-token",
  "source": "MANIFEST_OR_DISCOVERY",
  "repositories": [
    {
      "repositoryKey": "agent-web",
      "relativePath": "agent-web",
      "branch": "master",
      "headShort": "fae8007",
      "clean": false,
      "selectedByDefault": true,
      "primarySuggested": true,
      "warnings": []
    }
  ],
  "warnings": []
}
```

`inspectionToken` 只用于关联 UI 检查结果和诊断，不替代 Create 重验。

### 9.2 Create Repository Scope

```json
{
  "workspaceRoot": "/home/ubuntu/workspace",
  "primaryRepository": "agent-web",
  "repositories": ["agent-web", "agent-workflow"]
}
```

客户端不提交 repositoryRoot、HEAD 或 writable roots。

## 10. 错误契约

| HTTP | code | 含义 |
| ---: | --- | --- |
| 400 | `WORKSPACE_SELECTION_INVALID` | 空、重复、非法 Key、主仓库错误 |
| 403 | `WORKSPACE_PATH_FORBIDDEN` | Workspace/Repository 越过允许根 |
| 409 | `WORKSPACE_TOPOLOGY_CHANGED` | 创建后仓库成员或真实根变化 |
| 409 | `WORKSPACE_CAPTURE_UNSTABLE` | 采集期间持续变化 |
| 422 | `WORKSPACE_SELECTION_REQUIRED` | 非 Git 父目录未明确选择 |
| 422 | `WORKSPACE_REPOSITORY_NOT_FOUND` | 未发现/选择无效仓库 |
| 422 | `WORKSPACE_REPOSITORY_HEAD_MISSING` | 仓库无可解析 HEAD |
| 422 | `WORKSPACE_REPOSITORY_OVERLAP` | 选中仓库互相包含 |
| 503 | `WORKSPACE_GIT_UNAVAILABLE` | Git 启动、超时或安全读取失败 |

错误响应不包含原始 stderr、Secret、未授权绝对路径或文件正文。

## 11. 测试

Domain 无 Mock：

- Repository Selection 的所有路径、集合、主仓库和顺序不变量；
- Scope/Topology/State Hash 稳定性；
- 多仓库同名文件身份；
- Snapshot 拓扑一一对应和 clean 语义。

Infrastructure 真实临时 Git：

- sibling 多仓库发现、manifest 优先级、worktree `.git` 文件；
- dirty/untracked 对 Hash 的影响；
- symlink、nested repo、无 HEAD、输出超限、超时；
- 采集窗口 HEAD 变化重试；
- `-C + --add-dir` 命令 token 顺序和未选目录缺席。

Interface/Playwright：

- Inspect → 选择 → 创建；
- 单仓/多仓同一流程；
- dirty warning；
- 页面只展示 Repository Key/相对路径；
- 第二个未选仓库不能被文档 API 打开或 Runtime 修改。

## 12. 验收标准

- Workspace Root 非 Git 时可选择 sibling 仓库创建 Workbench；
- Scope 创建后不可变；
- 单仓和多仓没有 Application 条件分支；
- Runtime、Document、Event 使用同一 Repository Scope；
- 未选 sibling 和父目录不进入写根；
