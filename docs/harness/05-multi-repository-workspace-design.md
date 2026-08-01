# Harness 多仓库交付工作区改进设计

> 状态：Proposed，待评审，尚未实现
> 设计日期：2026-07-31
> 作者：alex
> 影响范围：Harness Run 创建、Git 基线、Runtime Capability、Implementation Evidence、Deployment、SQLite、管理页面
> 相关文档：[目标架构](01-target-harness-architecture.md)、[M4 实现与验收记录](m4/README.md)

## 1. 摘要与设计结论

当前 Harness 把 `workingDir`、交付工作区和 Git 仓库视为同一个概念，创建 Run 时只采集一组
`repositoryRoot / branch / HEAD / diffHash`。该模型适用于单仓库，却不能表达一个需求同时修改多个
微服务仓库的交付事实。

本方案作出以下核心决策：

1. `workingDir` 重新定义为 **Delivery Workspace Root（交付工作区根）**，允许自身不是 Git 仓库。
2. 一个 Run 创建时必须显式冻结一个非空、不可变的 **Repository Selection（仓库集合）**。
3. 每次 Git 观察生成不可变的 **Workspace Snapshot（工作区快照）**；快照包含多份
   **Repository Baseline（仓库基线）**。
4. `HarnessRun` 只持有创建快照的不可变引用和聚合 Hash，不直接承载可能很大的文件证据集合。
5. Runtime 使用一个主仓库作为 `codex -C`，其余已选仓库通过独立参数加入可写范围；父目录和未选仓库
   不进入写范围。
6. Implementation、Gate、Approval 和 Deployment 都绑定工作区级 Hash，不再绑定单仓库 Hash。
7. 单仓库是“仓库集合大小为 1”的正常情况，不在 Application 层维护两套流程。

目标结构如下：

```text
HarnessRun
├── workingDir = /home/ubuntu/workspace
├── primaryRepository = agent-web
└── creationSnapshotRef
    ├── snapshotId
    ├── topologyHash
    ├── stateHash
    └── repositoryCount = 3

WorkspaceSnapshot
├── workspaceRoot = /home/ubuntu/workspace
├── topologyHash
├── stateHash
└── repositories
    ├── agent-web
    │   └── branch / HEAD / clean / diffHash / changedFiles
    ├── agent-workflow
    │   └── branch / HEAD / clean / diffHash / changedFiles
    └── agent-langchain4j
        └── branch / HEAD / clean / diffHash / changedFiles
```

## 2. 背景和问题

### 2.1 触发问题

当前默认工作空间通常配置为多个项目的共同父目录，例如：

```text
/home/ubuntu/workspace
├── agent-web/.git
├── agent-workflow/.git
├── agent-langchain4j/.git
└── agent-android/.git
```

在父目录执行 `git rev-parse --show-toplevel` 会失败，因为 Git 只向父级寻找仓库，不会向子目录发现
多个仓库。当前创建流程把这个失败作为未处理异常抛出，最初表现为 HTTP 500。把异常转换成 422 只能
改善报错，仍然错误地拒绝了合法的微服务交付工作区。

### 2.2 当前单仓库假设

现有实现的单仓库假设分布在多个边界：

| 位置 | 当前假设 | 多仓库影响 |
| --- | --- | --- |
| `WorkspaceBaseline` | 只有一个仓库根、分支、HEAD 和 diff Hash | 无法表达仓库集合 |
| `ProcessWorkspaceBaselineGateway` | 在 `workingDir` 执行一次 Git 检查 | 父目录不是 Git 仓库时失败 |
| `HarnessRun` | 持有一份 `WorkspaceBaseline` | Implementation 只能比较一个仓库 |
| `WorkspaceChangeEvidence` | 文件路径只在一个仓库内唯一 | 多个仓库的 `pom.xml` 会冲突 |
| `harness_run` | 一组 `repository_root / git_*` 列 | 无法持久化 N 个仓库 |
| `harness_deployment_execution` | 一组部署前 Git 基线 | 无法执行多仓库 Preflight |
| `HarnessRunView` | 返回一个 `workspaceBaseline` | 页面不能展示各服务状态 |
| Runtime | `codex -C workingDir` | 非 Git 父目录不适合作为 Codex 主仓库 |
| Deployment Template | 所有步骤固定在一个 `workingDir` 执行 | 无法声明步骤属于哪个服务 |

### 2.3 不能采用的局部修补

以下方案不满足确定性和安全要求：

- 递归后选择发现的第一个 `.git`：扫描顺序会影响 Run 绑定对象，且会遗漏其他服务。
- 递归后自动纳入所有仓库：可能把归档、示例、fixture、临时 worktree 一并纳入。
- 仅让 Codex 在父目录运行，仍只采集一个仓库：Agent 可以修改未被 Gate 和 Approval 覆盖的文件。
- 只在 Artifact 中记录多仓库信息：聚合、Preflight 和持久化仍无法守护拓扑不变量。
- 在 Application 层遍历 `getRepositories()` 比较 branch/HEAD：会把领域规则泄漏到应用服务。

## 3. 目标与非目标

### 3.1 目标

- 一个 Harness Run 可以覆盖同一交付工作区下的多个 sibling Git 仓库。
- Run 创建时仓库集合明确、可审计、不可静默变化。
- 每个仓库独立记录分支、HEAD、脏状态、diff Hash 和文件证据。
- 工作区整体 Hash 与仓库枚举顺序、文件系统遍历顺序无关。
- Runtime 只能写入 Run 已选择的仓库。
- Git 证据、TDD Gate、Approval 和 Deployment 使用同一个工作区边界。
- 旧单仓库 Run 可以读取、展示和继续完成，不要求破坏性迁移。
- 保持 DDD 四层边界，Application 只做事务和外部 I/O 编排。

### 3.2 非目标

- 不在本次设计中支持跨机器、远程 Git URL 或运行时自动 clone。
- 不把多个仓库合并为一个虚拟 Git 历史。
- 不实现跨仓库原子 commit、原子 push 或分布式事务。
- 不自动选择业务上“相关”的服务；相关仓库由管理员清单和用户确认决定。
- 不在运行中自动把新发现的仓库加入已有 Run。
- 不展开 Git submodule 为独立仓库；首版把 submodule commit pointer 视为父仓库状态的一部分。
- 不改变固定四阶段 Harness 流程。

## 4. 统一语言与领域概念

| 概念 | 类型 | 定义与责任 |
| --- | --- | --- |
| `DeliveryWorkspace` | 业务概念 | 一次交付可访问的本地目录边界，自身不要求是 Git 仓库 |
| `RepositoryCandidate` | 查询模型 | 工作区检查阶段发现、尚未冻结的 Git 仓库候选 |
| `RepositorySelection` | 值对象 | 用户确认的仓库相对路径集合及主仓库；作为创建 Run 的业务输入 |
| `RepositoryBaseline` | 值对象 | 单仓库在观察时刻的 branch、HEAD、clean、diff Hash 和文件状态 |
| `WorkspaceTopology` | 值对象 | 工作区根、主仓库和有序仓库引用集合；只描述成员身份，不描述代码状态 |
| `WorkspaceSnapshot` | 不可变聚合根 | 一次真实 Git 观察事实，包含拓扑、仓库基线、工作区 Hash 和采集时间窗口 |
| `WorkspaceSnapshotReference` | 值对象 | `snapshotId / topologyHash / stateHash / repositoryCount`，供其他聚合安全引用 |
| `RepositoryChangeEvidence` | 值对象 | 一个仓库在两次快照间的文件变化 |
| `WorkspaceChangeEvidence` | 值对象 | 同一拓扑下两个快照之间的多仓库变化证据 |
| `WorkspaceSnapshotGateway` | Application 出站端口 | 发现候选、解析选择并通过本机 Git 采集快照 |
| `WorkspaceSnapshotRepository` | Domain Repository | 保存和读取不可变快照生命周期，不暴露 JDBC/JSON 类型 |
| `WorkspaceInspectionQueryService` | Application/CQRS 接口 | 为管理页面返回候选仓库 DTO，不返回领域聚合 |

### 4.1 为什么 `WorkspaceSnapshot` 单独建模

多仓库快照可能包含大量文件证据。如果把完整快照直接塞进 `HarnessRun`：

- 每次加载 Run 都要恢复全部仓库和文件状态；
- Run 的乐观锁更新会携带无关的大对象；
- Deployment 和 Runtime 只能复制完整对象或依赖另一个聚合根；
- SQLite 行模型会继续膨胀。

因此 `WorkspaceSnapshot` 作为不可变事实独立持久化，`HarnessRun`、`RuntimeExecution` 和
`DeploymentExecution` 只引用 `WorkspaceSnapshotReference`。聚合之间不直接持有彼此，Application 在
同一事务内编排保存顺序。

## 5. 聚合边界与引用方向

### 5.1 聚合关系

```text
HarnessRun ── WorkspaceSnapshotReference ──> WorkspaceSnapshot
RuntimeExecution ── WorkspaceSnapshotReference ──> WorkspaceSnapshot
DeploymentExecution ── WorkspaceSnapshotReference ──> WorkspaceSnapshot
```

- `HarnessRun` 守护 Stage、Attempt、Artifact、Gate、Approval 和创建快照引用。
- `WorkspaceSnapshot` 守护仓库集合唯一性、路径边界、Hash 和文件证据一致性。
- `RuntimeExecution` 只保存执行前后 Snapshot Reference 和终态，不接收 Snapshot 聚合根。
- `DeploymentExecution` 只保存批准输入 Hash、Preflight Snapshot Reference 和部署终态。
- 跨聚合比较由领域值对象或领域策略完成，Repository 不注入聚合根。

### 5.2 Application 编排边界

创建 Run 的 Application 流程保持纯编排：

```text
校验工作区白名单
→ 按 RepositorySelection 调用 WorkspaceSnapshotGateway.capture(...)
→ 调用 HarnessRun.create(..., snapshot.reference())
→ 保存 WorkspaceSnapshot
→ 保存 HarnessRun
→ 提交后发布 Run Event
```

Application 不允许：

- 遍历仓库 getter 自行判断重复、越界或主仓库；
- 比较每个仓库 branch/HEAD 后决定拓扑是否一致；
- 拼装工作区聚合 Hash；
- 按仓库数量写 `if (single) ... else ...` 两套业务流程。

这些规则分别由 `RepositorySelection`、`WorkspaceTopology`、`WorkspaceSnapshot` 和领域策略守护。

## 6. 核心不变量

### 6.1 仓库选择不变量

1. Repository Selection 至少包含一个仓库。
2. 必须且只能有一个主仓库。
3. 仓库标识使用规范化 POSIX 相对路径，例如 `agent-web` 或 `platform/order-service`。
4. 不允许空路径、绝对路径、`.`、`..`、路径回退或重复路径。
5. 每个仓库的真实路径必须位于工作区真实根目录内。
6. 不跟随符号链接发现仓库；显式选择的符号链接也必须拒绝。
7. 首版禁止两个选中仓库根互相包含，避免独立嵌套仓库和 submodule 双重计数。
8. 每个仓库必须可由服务账户读取，并且至少存在一个可解析的 HEAD。
9. Run 创建后 Repository Selection 不可修改；需要变更仓库集合时创建新 Run。

### 6.2 快照不变量

1. `WorkspaceSnapshot.repositories` 与 `WorkspaceTopology.repositories` 必须一一对应。
2. 仓库基线按 `repositoryKey` 排序后保存和计算 Hash。
3. `topologyHash` 只包含工作区根、主仓库和仓库相对路径集合。
4. `stateHash` 包含 `topologyHash` 以及每个仓库的 branch、HEAD、clean、diff Hash。
5. `clean=true` 当且仅当所有仓库都 clean 且没有工作区级异常证据。
6. Changed File 的 path 只要求在所属仓库内唯一；跨仓库身份由 `repositoryKey + path` 共同确定。
7. Snapshot 的 `captureStartedAt <= capturedAt`。
8. 多仓库顺序采集结束后必须二次核验每个仓库的 branch 和 HEAD；采集窗口内发生变化时重试一次，
   仍不稳定则 fail-closed。
9. Snapshot 内容不可修改；新的观察必须创建新的 `snapshotId`。

### 6.3 阶段与审批不变量

1. Run 创建快照、Implementation 开始快照和 Deployment Preflight 必须属于同一 Workspace Topology。
2. 任一选中仓库丢失、路径变化或被替换，都属于 `WORKSPACE_TOPOLOGY_CHANGED`。
3. Implementation Evidence 必须覆盖所有真实发生变化的选中仓库。
4. Runtime 不得修改未选仓库；发现越界写入时 Attempt 必须失败，不能只记录 warning。
5. Approval 绑定当前 Artifact Baseline Hash；Git 相关 Artifact 必须引用对应 Workspace Snapshot Hash。
6. Deployment Preflight 必须匹配被批准的 Workspace State Hash，否则在执行第一个命令前失败。
7. 某仓库没有变化是合法事实，不要求每个选中仓库都产生代码修改。

## 7. Hash 设计

禁止直接对普通 JSON 字符串做 Hash，因为字段顺序、空值和序列化器升级会破坏稳定性。继续使用现有
framed canonical hashing 思路。

### 7.1 Topology Hash

伪代码：

```text
frame("schema", "workspace-topology@1")
frame("workspaceRoot", canonicalWorkspaceRoot)
frame("primaryRepository", primaryRepositoryKey)
for repository in repositories.sortedBy(repositoryKey):
    frame("repositoryKey", repository.repositoryKey)
    frame("relativePath", repository.relativePath)
```

### 7.2 Repository State Hash

单仓库继续保留现有 `diffHash`，但其规范输入必须只来自该仓库：

```text
status --porcelain=v1 -z
diff --binary HEAD --
untracked relative path + content hash
```

不把文件正文存入 Snapshot、数据库、日志或 API。

### 7.3 Workspace State Hash

```text
frame("schema", "workspace-state@1")
frame("topologyHash", topologyHash)
for baseline in baselines.sortedBy(repositoryKey):
    frame("repositoryKey", baseline.repositoryKey)
    frame("branch", baseline.branch)
    frame("head", baseline.head)
    frame("clean", baseline.clean)
    frame("diffHash", baseline.diffHash)
```

`stateHash` 是 Run、Gate、Approval 和 Deployment 共同使用的工作区级 Git 事实标识。

## 8. 仓库发现与选择策略

### 8.1 两阶段交互

创建 Run 改为两阶段：

```text
Inspect Workspace（只读候选发现）
→ 管理员确认仓库集合和主仓库
→ Create Run（重新解析并冻结真实快照）
```

Inspect 只是候选查询，不产生领域事件、不持久化 Run，也不保证后续状态不变。Create 时必须重新解析
真实路径并捕获快照，以创建结果为准。

### 8.2 候选来源优先级

推荐优先级：

1. 工作区根 `.agent-web.yml` 中的 `harness.repositories` 显式清单；
2. 工作区根自身是 Git 仓库时返回单仓库候选；
3. 在服务端受控深度内发现 `.git` 目录或 Git worktree 的 `.git` 文件，返回候选但不自动创建 Run。

建议清单：

```yaml
harness:
  primary_repository: agent-web
  repositories:
    - agent-web
    - agent-workflow
    - agent-langchain4j
```

清单只提供默认选择，不绕过服务端路径白名单、真实路径和 Git 校验。

### 8.3 安全扫描边界

建议配置默认值：

| 配置 | 默认值 | 作用 |
| --- | ---: | --- |
| `agent.harness.workspace.discovery-max-depth` | `2` | 候选发现最大相对深度 |
| `agent.harness.workspace.max-repositories` | `50` | 一个 Run 最多仓库数 |
| `agent.harness.workspace.capture-timeout-seconds` | `60` | 整体采集上限 |
| `agent.harness.workspace.git-command-timeout-seconds` | `10` | 单仓库单命令上限 |
| `agent.harness.workspace.max-output-bytes` | `8388608` | 单命令输出上限 |

扫描必须满足：

- 使用 `Files.walk` 的固定深度且不启用 `FOLLOW_LINKS`；
- 发现 `.git` 后剪枝，不继续把内部目录当成独立仓库扫描；
- 忽略 `.worktrees`、`target`、`build`、`node_modules`、`data` 等明确的非业务候选目录；
- 最终仍用固定参数的 `git -C <candidate> rev-parse --show-toplevel` 验证；
- 根据真实仓库根去重，不根据目录名猜测；
- 不执行 shell，不接受客户端提供 Git 命令或排除表达式。

### 8.4 单仓库兼容规则

- 新请求未传 `repositories` 且 `workingDir` 本身位于一个 Git 仓库时，规范化为仓库集合大小 1。
- 新请求未传 `repositories` 且 `workingDir` 是非 Git 父目录时，返回
  `HARNESS_REPOSITORY_SELECTION_REQUIRED`，同时引导页面先 Inspect。
- Inspect 没发现任何可用仓库时返回 `HARNESS_REPOSITORY_NOT_FOUND`，不再把父目录本身统称为无效。
- 旧 Run 读取时映射为只有一个 Repository Baseline 的 Workspace Snapshot。

## 9. Runtime 多仓库边界

### 9.1 主仓库与附加仓库

当前 Runtime 已区分 `GIT_ROOT` 与 `APPROVED_ROOT`，但实际命令仍把 `workingDir` 作为 `codex -C`。
多仓库模式不能直接在非 Git 父目录使用 `-C`，也不能让 `workspace-write` 获得整个父目录写权限。

推荐命令布局：

```text
codex exec
  --sandbox workspace-write
  -C /home/ubuntu/workspace/agent-web
  --add-dir /home/ubuntu/workspace/agent-workflow
  --add-dir /home/ubuntu/workspace/agent-langchain4j
  ...
```

- `-C` 使用显式主仓库。
- `--add-dir` 只加入其余已选仓库，按 repositoryKey 排序。
- 不把交付父目录和未选仓库加入可写目录。
- 参数必须作为 `ProcessBuilder` token 传递，禁止拼 shell 字符串。
- Prompt 明确给出逻辑工作区根、主仓库和全部仓库相对路径映射。

当前目标机 `codex exec --help` 已声明 `--add-dir` 为“Additional directories that should be writable
alongside the primary workspace”。实现前仍需把该参数加入现有 Codex 版本兼容矩阵，并用真实 CLI
验证 read-only、workspace-write、符号链接逃逸和未选 sibling repo 不可写。

### 9.2 分阶段权限

| Stage | Sandbox | 主仓库 | 其他选中仓库 | 未选仓库/父目录 |
| --- | --- | --- | --- | --- |
| ANALYSIS | read-only | 只读 | 只读 | 不作为授权根 |
| DESIGN | read-only | 只读 | 只读 | 不作为授权根 |
| IMPLEMENTATION | workspace-write | 可写 | 通过 `--add-dir` 可写 | 不可写 |
| DEPLOYMENT | read-only Runtime | 只读 | 只读 | 不作为授权根 |

Deployment 的本地命令由独立受控 Gateway 执行，不复用 Agent 的 workspace-write 权限。

### 9.3 Capability Snapshot

`WorkspaceRuntimeInventory` 需要新增：

- `WorkspaceBoundaryKind.MULTI_REPOSITORY_ROOT`；
- 主仓库 key；
- 选中仓库的 `repositoryKey / relativePath / rootHash`；
- 每仓库 Repo Skill inventory；
- `repositoryWriteScopeEnforced`；
- Repository Selection Hash。

`RuntimeEnforcementProfile.supportsMcpToolIsolation()` 不能代表 Git 写边界已经生效。应新增独立语义查询，
例如 `supportsRepositoryWriteIsolation()`，Implementation 启动前必须为 true。

## 10. 领域行为与事件流

### 10.1 创建 Run

```text
InspectWorkspace
→ WorkspaceCandidatesReturned
→ SelectRepositories
→ CaptureWorkspaceSnapshot(CREATION)
→ CreateRun
→ WORKSPACE_SNAPSHOT_FROZEN
→ RUN_CREATED
```

`WORKSPACE_SNAPSHOT_FROZEN` 事件只记录 snapshot ID、topology Hash、state Hash 和仓库数量；日志不输出
文件正文、Git diff 或 Secret。

### 10.2 启动 Implementation

```text
CaptureWorkspaceSnapshot(IMPLEMENTATION_BASELINE)
→ creationSnapshot.requireSameTopology(baselineSnapshot)
→ HarnessRun.captureImplementationBaseline(snapshotRef)
→ 注册结构化 GIT_BASELINE Artifact
→ 启动受控 Runtime
```

如果拓扑已变化，Start 命令返回 409，Stage 不进入 RUNNING，不创建半截 Attempt。

### 10.3 Runtime 完成

```text
CaptureWorkspaceSnapshot(IMPLEMENTATION_RESULT)
→ baselineSnapshot.compare(resultSnapshot)
→ 生成 WorkspaceChangeEvidence
→ 平台覆盖 Agent 自报 CHANGED_FILES
→ Gate 校验
```

如果 Runtime 期间出现仓库集合变化、越界写入或采集不稳定，RuntimeExecution 和 Stage 应进入明确 FAILED
终态，不能仅把异常抛到 HTTP 层。

### 10.4 Deployment

```text
ApproveDeployment(approvedArtifactHash, approvedWorkspaceStateHash)
→ PrepareDeploymentExecution
→ CaptureWorkspaceSnapshot(DEPLOYMENT_PREFLIGHT)
→ DeploymentPermit.requireMatchingWorkspace(preflightRef)
→ 执行模板步骤
```

任何仓库的 branch、HEAD 或 diff Hash 与批准事实不一致，都必须在第一个部署命令前失败。

## 11. Artifact 合同演进

现有 `CHANGED_FILES` 只有文件列表，需要升级为多仓库结构。建议新增
`harness-changed-files@2`：

```json
{
  "schemaVersion": "harness-changed-files@2",
  "baselineSnapshotId": "snapshot-1",
  "baselineStateHash": "...",
  "currentSnapshotId": "snapshot-2",
  "currentStateHash": "...",
  "repositories": [
    {
      "repositoryKey": "agent-web",
      "relativePath": "agent-web",
      "baselineHead": "...",
      "currentHead": "...",
      "baselineDiffHash": "...",
      "currentDiffHash": "...",
      "files": [
        {
          "path": "pom.xml",
          "status": "M",
          "stateFingerprint": "...",
          "sensitive": false
        }
      ]
    }
  ]
}
```

规则：

- 文件身份使用结构化的 `repositoryKey + path`，不使用易解析错误的 `repo::path` 拼接字符串。
- `TEST_EVIDENCE` 的每条命令新增 `repositoryKey` 或明确的工作目录引用。
- `TRACEABILITY` 可以关联仓库级实现引用。
- `PREFLIGHT`、`BUILD_EVIDENCE`、`DEPLOYMENT_RECORD` 和 `FINAL_REPORT` 引用 Workspace State Hash。
- 旧 Artifact 继续按 v1 解析；新 Run 只生成 v2，不在 Application 中按仓库数量选择 schema。

## 12. Deployment Template v2

部署步骤不能继续隐式使用统一 `workingDir`。每个步骤必须声明受控工作目录引用：

```yaml
schema: harness-deployment-template@2
id: local-microservices
version: "1"
steps:
  - name: build-agent-web
    working_directory:
      kind: REPOSITORY
      repository: agent-web
    command: ["mvn", "-q", "-DskipTests", "package"]
  - name: test-agent-workflow
    working_directory:
      kind: REPOSITORY
      repository: agent-workflow
    command: ["mvn", "-q", "test"]
```

首版只允许 `kind=REPOSITORY`。如果以后需要父目录下的编排脚本，再单独引入
`kind=WORKSPACE_ROOT`，并要求管理员模板、文件 Hash 和额外 Approval；不能让用户输入任意执行目录。

Domain 中用 `DeploymentWorkingDirectory` 值对象解析 repository key，Infrastructure 只接收已经解析且
位于 Workspace Snapshot 内的真实目录。

## 13. API 设计

### 13.1 Inspect Workspace

```http
POST /api/harness/workspaces/inspect
Content-Type: application/json

{
  "workingDir": "/home/ubuntu/workspace"
}
```

响应：

```json
{
  "workspaceRoot": "/home/ubuntu/workspace",
  "source": "MANIFEST_OR_DISCOVERY",
  "repositories": [
    {
      "repositoryKey": "agent-web",
      "relativePath": "agent-web",
      "branch": "master",
      "head": "...",
      "clean": false,
      "selectedByDefault": true,
      "primarySuggested": true
    },
    {
      "repositoryKey": "agent-workflow",
      "relativePath": "agent-workflow",
      "branch": "master",
      "head": "...",
      "clean": true,
      "selectedByDefault": true,
      "primarySuggested": false
    }
  ],
  "warnings": []
}
```

该接口是 CQRS 查询，不返回 `WorkspaceSnapshot` 聚合，也不创建 snapshot ID。

### 13.2 Create Run v2

```http
POST /api/harness/runs
Idempotency-Key: ...
Content-Type: application/json

{
  "title": "跨服务订单链路改造",
  "workingDir": "/home/ubuntu/workspace",
  "primaryRepository": "agent-web",
  "repositories": [
    "agent-web",
    "agent-workflow",
    "agent-langchain4j"
  ],
  "agentType": "CODEX",
  "environment": "local",
  "definitionVersion": "harness@2.0.0",
  "originalRequirement": "..."
}
```

幂等匹配必须增加规范化后的 Repository Selection 和主仓库。首次创建成功后，同一幂等键重试应返回
已有 Run，不因仓库当前 Git 状态发生变化而变成冲突；只有创建输入中的仓库选择不同才是幂等冲突。

### 13.3 Run Detail v2

新增：

```json
{
  "workspaceSnapshot": {
    "snapshotId": "snapshot-1",
    "workspaceRoot": "/home/ubuntu/workspace",
    "primaryRepository": "agent-web",
    "topologyHash": "...",
    "stateHash": "...",
    "clean": false,
    "capturedAt": 0,
    "repositories": []
  }
}
```

现有 `workspaceBaseline` 保留一个兼容周期并标记 deprecated：

- 旧 Run 正常返回单仓库投影；
- 新多仓库 Run 不伪造单仓库数据，可以返回 `null`；
- 前端切换到 `workspaceSnapshot` 后再删除旧字段。

### 13.4 错误契约

| HTTP | code | 含义 |
| ---: | --- | --- |
| 400 | `HARNESS_REPOSITORY_SELECTION_INVALID` | 空集合、重复、非法相对路径或主仓库不在集合中 |
| 403 | `HARNESS_REPOSITORY_OUTSIDE_WORKSPACE` | 真实路径逃逸授权工作区 |
| 409 | `HARNESS_WORKSPACE_TOPOLOGY_CHANGED` | 已冻结仓库集合与当前观察不一致 |
| 409 | `HARNESS_WORKSPACE_CAPTURE_UNSTABLE` | 采集窗口内仓库状态持续变化 |
| 422 | `HARNESS_REPOSITORY_SELECTION_REQUIRED` | 非 Git 父目录创建时未明确仓库集合 |
| 422 | `HARNESS_REPOSITORY_NOT_FOUND` | 工作区内没有可用 Git 仓库 |
| 422 | `HARNESS_REPOSITORY_HEAD_MISSING` | 选中仓库没有可解析 HEAD |
| 503 | `HARNESS_GIT_INSPECTION_UNAVAILABLE` | Git 命令无法启动、超时或输出不可安全读取 |

当前临时新增的 `InvalidHarnessWorkspaceException` 应被上述细分错误替代；合法的多仓库父目录不能继续
映射为 `HARNESS_WORKSPACE_INVALID`。

## 14. SQLite 模型

采用新增规范化表而不是继续扩展单仓库列：

```sql
CREATE TABLE harness_workspace_snapshot (
    snapshot_id       TEXT PRIMARY KEY,
    run_id            TEXT NOT NULL,
    purpose           TEXT NOT NULL,
    stage             TEXT,
    attempt_number    INTEGER,
    workspace_root    TEXT NOT NULL,
    primary_repository TEXT NOT NULL,
    topology_hash     TEXT NOT NULL,
    state_hash        TEXT NOT NULL,
    repository_count  INTEGER NOT NULL,
    clean             INTEGER NOT NULL,
    capture_started_at INTEGER NOT NULL,
    captured_at       INTEGER NOT NULL,
    UNIQUE(run_id, purpose, stage, attempt_number, snapshot_id)
);

CREATE TABLE harness_repository_baseline (
    snapshot_id       TEXT NOT NULL,
    repository_key    TEXT NOT NULL,
    relative_path     TEXT NOT NULL,
    repository_root   TEXT NOT NULL,
    git_branch        TEXT NOT NULL,
    git_head          TEXT NOT NULL,
    git_clean         INTEGER NOT NULL,
    git_diff_hash     TEXT NOT NULL,
    captured_at       INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, repository_key),
    FOREIGN KEY(snapshot_id)
        REFERENCES harness_workspace_snapshot(snapshot_id) ON DELETE CASCADE
);

CREATE TABLE harness_repository_changed_file (
    snapshot_id       TEXT NOT NULL,
    repository_key    TEXT NOT NULL,
    path               TEXT NOT NULL,
    status             TEXT NOT NULL,
    state_fingerprint  TEXT NOT NULL,
    sensitive          INTEGER NOT NULL,
    PRIMARY KEY(snapshot_id, repository_key, path),
    FOREIGN KEY(snapshot_id, repository_key)
        REFERENCES harness_repository_baseline(snapshot_id, repository_key) ON DELETE CASCADE
);
```

现有表采用加列迁移：

```text
harness_run
  + creation_snapshot_id
  + workspace_topology_hash
  + workspace_state_hash
  + repository_count

harness_stage_attempt
  + implementation_baseline_snapshot_id
  + implementation_result_snapshot_id

harness_deployment_execution
  + preflight_snapshot_id
  + workspace_state_hash
```

旧 `repository_root / git_*` 列首阶段保留，不再作为新 Run 的事实来源。

### 14.1 Repository 与 QueryService

- `WorkspaceSnapshotRepository` 位于 Domain，只提供 `add`、`findById`、按 Run 删除等聚合生命周期方法。
- `HarnessRunRepository` 只保存 `WorkspaceSnapshotReference`，不返回 JDBC Row 或 JSON。
- `WorkspaceSnapshotQueryService` 位于 Application，Infrastructure 返回管理页 DTO。
- 列表页仍返回轻量 Run Summary，不联表加载全部仓库和文件证据。
- Run Detail 可以加载仓库基线概要；Changed Files 正文继续通过 Artifact/专门查询按需读取。

## 15. 数据迁移与兼容

### 15.1 Additive Migration

1. 新增 Snapshot、Repository Baseline、Changed File 表和 nullable 引用列。
2. 对每个旧 Run 创建一个 `LEGACY_CREATION` Snapshot。
3. 从旧 `repository_root / git_*` 列生成一个 Repository Baseline。
4. 若旧 `working_dir` 位于 `repository_root` 内，迁移后的 workspace root 规范化为 repository root。
5. 更新 Run 的 snapshot reference、topology Hash、state Hash 和 repository count=1。
6. 迁移过程幂等；已经存在 Snapshot 的 Run 不重复创建。

### 15.2 读写兼容

- Read：优先读取 `creation_snapshot_id`；为空时走旧列适配。
- Write：新创建 Run 只写 Snapshot 模型，同时在兼容窗口可投影单仓库旧列。
- Query：API 同时提供新 `workspaceSnapshot` 和旧 `workspaceBaseline`。
- Artifact：按 `schemaVersion` 选择 v1/v2 Parser；业务 Gate 调用统一的领域证据对象。
- 清理：完成生产回填、API 切换和至少一个发布周期后，再单独删除旧列和兼容构造器。

不使用一次性破坏性 SQL，也不在 RepositoryImpl 中散落“新表存在则新逻辑，否则旧逻辑”的分支。迁移状态
由明确的 codec/mapper 策略收敛。

## 16. 前端交互

新建 Run 弹窗调整为：

1. 输入或选择交付工作区根。
2. 点击“扫描仓库”。
3. 展示候选仓库表格：仓库、分支、HEAD、clean/dirty、来源、warning。
4. 用户勾选本次 Run 仓库集合。
5. 从已选仓库中指定一个主仓库。
6. 页面显示“本次 Run 将冻结 N 个仓库基线”。
7. 创建成功后在 Run Context 中按仓库展示基线。

交互要求：

- 清单仓库默认勾选；纯扫描候选只作为建议，用户必须确认。
- 提供“全选”和“仅选择 dirty 仓库”，但最终请求始终传明确路径集合。
- dirty 不阻止创建，使用 warning Tag 提示其会被记录为用户原有变更。
- 不在浏览器递归文件系统；发现和真实路径校验必须在服务端。
- API 返回 409 topology changed 时保留表单并提示重新扫描。
- Run 详情显示工作区 Hash，并允许展开每个仓库基线；文件证据按需加载。

## 17. 安全设计

### 17.1 文件边界

- 所有根目录先经过 `WorkspacePathPolicy.requireExistingDirectory`。
- 使用 `toRealPath()` 后再次验证每个仓库位于工作区内。
- 不跟随符号链接发现或选择仓库。
- Git worktree 的 `.git` 文件可支持，但真实 worktree 根必须在授权范围内。
- 不允许重叠仓库根；submodule 首版不展开。

### 17.2 Runtime 写边界

- 主仓库是唯一 `-C`。
- 只把其他选中仓库作为独立 `--add-dir` token。
- 未选 sibling repo 和父目录不能进入 Runtime writable roots。
- Capability Snapshot 固化实际命令目录 Hash 与 selection Hash。
- Preflight 必须证明目标 Codex 版本能强制多目录写隔离，否则 Implementation fail-closed。
- Runtime 结束后除比较 Git Snapshot 外，还应审计实际工具事件中的文件路径；二者任一发现越界即失败。

### 17.3 敏感信息

- Changed File 继续使用敏感路径分类，并增加 repositoryKey 上下文。
- 不读取 Git ignored Secret 文件；不持久化 diff 正文和 untracked 文件正文。
- Hash 计算可读取允许大小内的 untracked 内容，但只保存不可逆 Hash。
- API、日志和 Event 不输出命令原始 stderr，避免泄漏路径或配置内容。
- 继续执行单文件、单命令输出、仓库数量和整体超时上限。

## 18. 变化点收敛

| 变化来源 | 当前分散位置 | 推荐收敛点 |
| --- | --- | --- |
| 单仓库/多仓库拓扑 | Gateway、Run、SQL、页面 | `WorkspaceTopology` + `RepositorySelection` |
| 清单/扫描发现 | 尚无 | `RepositoryDiscoveryPolicy` + Infra Scanner |
| Git 采集实现 | `ProcessWorkspaceBaselineGateway` | `WorkspaceSnapshotGateway` Adapter |
| 工作区 Hash | 单仓库 `WorkspaceBaseline` | `WorkspaceSnapshot` 领域方法 |
| Runtime 主/附加目录 | Codex command 组装 | `RuntimeWorkspaceLayout` 策略 |
| 不同 Codex 版本的多目录支持 | Runtime Adapter 条件 | Compatibility Matrix + Preflight Profile |
| 部署步骤目录 | 固定 `workingDir` | `DeploymentWorkingDirectory` 值对象 |
| v1/v2 持久化 | Repository 读取代码 | Snapshot Codec/Migration Adapter |
| v1/v2 Artifact | Gate 内 JSON 判断 | Artifact Parser/Schema Registry |

严禁在 `HarnessAppServiceImpl`、`HarnessExecutionService` 或 Controller 中按仓库数量堆积条件链。

## 19. 重构信号

建议按语义改名和拆分：

| 当前名称 | 建议名称 | 原因 |
| --- | --- | --- |
| `WorkspaceBaseline` | `RepositoryBaseline` | 当前字段实际描述单仓库 |
| `WorkspaceBaselineGateway` | `WorkspaceSnapshotGateway` | 新端口返回整个工作区观察事实 |
| `WorkspaceBaselineCaptureException` | 细分 capture/selection/topology 异常 | 区分用户输入、并发变化和系统不可用 |
| `belongsToSameRepository` | `WorkspaceTopology.requireSameTopology` | 规则从单仓库升级为集合不变量 |
| `WorkspaceChangeEvidence` | 保留名称，内部持有 `RepositoryChangeEvidence` | 避免平铺路径冲突 |
| `DeploymentExecutionSpec.workingDir` | `DeploymentWorkingDirectory` | 防止任意路径和隐式单目录 |

当前新增的 `InvalidHarnessWorkspaceException` 是临时错误映射，不应进入最终多仓库模型。

## 20. TDD 与验证方案

所有包含业务判断的 Java 变更按“先红、最小绿、重构保绿”推进。

### 20.1 Domain 测试，无 Mock

- Repository Selection 拒绝空、重复、越界、重叠和非法主仓库。
- 仓库输入顺序不同但内容相同，Topology Hash 和 State Hash 相同。
- 两个仓库都存在 `pom.xml` 时文件证据不冲突。
- 任一仓库 branch、HEAD、relative path 改变时拓扑/状态语义正确。
- 单仓库集合走同一模型，无单独业务分支。
- Workspace Change Evidence 正确识别新增、修改、删除和敏感文件。
- Deployment Permit 拒绝不匹配的 Workspace State Hash。

### 20.2 Application 测试，Mock Port/Repository

- Create 先解析幂等已有 Run，再决定是否采集新 Snapshot。
- 新建时保存 Snapshot 和 Run，并只发布提交后的 Event。
- 幂等匹配包含规范化 selection，但不比较重试时的当前 Git 状态。
- Implementation/Deployment 只编排 Gateway、Domain 和 Repository，不遍历仓库集合做判断。
- Snapshot 保存失败时 Run 不落库；Run 保存失败时不发布事件。

### 20.3 Infrastructure 测试，真实临时 Git/SQLite

- 父目录包含两个 sibling repo 时正确发现、采集和排序。
- 两个仓库 dirty 状态和 untracked 内容分别影响对应 Hash 与整体 State Hash。
- 符号链接逃逸、嵌套 repo、缺失 HEAD、命令超时和输出超限 fail-closed。
- 采集过程中 HEAD 改变触发 retry/unstable 结果。
- SQLite 新表约束、级联删除和 Snapshot restore 完整。
- 旧单仓库行可回填为 repositoryCount=1，迁移重复执行不产生重复数据。

### 20.4 Interface 测试

- Inspect 的候选、warning 和状态码。
- Create v2 参数校验、主仓库校验、幂等冲突和 409 topology changed。
- Run Detail 同时覆盖新投影和旧 Run 兼容投影。
- 错误响应不包含 Git stderr、文件正文或未授权绝对路径。

### 20.5 Runtime 合同测试

- 命令必须包含一个确定的 `-C primary`。
- 只对选中附加仓库生成 `--add-dir`，顺序确定。
- 未选仓库和父目录不出现在 writable arguments。
- read-only Stage 不获得写权限。
- 真实受支持 Codex 版本验证主仓库、附加仓库可写，未选 sibling 不可写。
- Repo Skill inventory 覆盖所有选中仓库且仍受批准策略控制。

### 20.6 E2E

建立两个临时 Git 仓库的 Playwright fixture：

```text
workspace/
├── service-a/.git
└── service-b/.git
```

覆盖 Inspect → 选择两个仓库 → 创建 Run → Implementation 跨仓库变更 → Gate → Approval →
Repository-targeted Deployment → Final Report，并断言未选 `service-c` 不可修改。

## 21. 分阶段落地计划

### Phase 0：合同与安全验证

- 冻结本文的统一语言、Hash schema、API v2 和 SQLite schema。
- 把 `--add-dir` 多写根加入 Codex Compatibility Matrix。
- 用真实 Codex 验证写边界；未通过前不开放多仓库 Implementation。

### Phase 1：Domain Kernel

- 先写 `RepositorySelection`、`WorkspaceTopology`、`RepositoryBaseline`、`WorkspaceSnapshot`、
  `WorkspaceSnapshotReference`、`WorkspaceChangeEvidence` 失败测试。
- 实现不可变模型和 Hash。
- `HarnessRun` 改持 Snapshot Reference；单仓库迁入统一集合模型。

### Phase 2：Git Adapter 与 SQLite

- 实现 Workspace Inspect/Discover 和多仓库 Capture Adapter。
- 新增 Snapshot Repository 与 QueryService。
- 执行 additive migration 和旧 Run backfill。

### Phase 3：Create API 与管理页面

- 增加 Inspect API、Create v2 DTO 和仓库选择 UI。
- Run Detail 展示工作区与仓库基线。
- 替换临时的父目录无效错误语义。

### Phase 4：Runtime 与 Evidence

- 引入主仓库/附加仓库 Runtime Layout。
- Capability Snapshot 固化 Repository Selection 和写隔离证据。
- CHANGED_FILES/TEST_EVIDENCE/TRACEABILITY 升级为 v2。
- Implementation Gate 使用 Workspace Snapshot，而非单仓库 getter 判断。

### Phase 5：Deployment 与上线

- Deployment Template v2 支持 repository-targeted step。
- DeploymentExecution 绑定 Preflight Snapshot Reference。
- 完成真实双仓库试点、全量验证、指标观察和回滚演练。

各 Phase 均可独立合并，但多仓库开关只有在 Phase 4 的写隔离合同关闭后才能对真实 Implementation 开放。

## 22. 发布、回滚与观测

### 22.1 发布控制

引入临时发布开关：

```yaml
agent:
  harness:
    multi-repository-enabled: false
```

该开关只控制 Create/Inspect 新入口是否开放，不在 Application 业务流程中制造两套状态机。配置层注入
统一 Workspace Policy；已创建 Run 根据冻结的 Snapshot schema 运行。

### 22.2 指标

至少记录：

- `harness_workspace_inspection_total{result}`
- `harness_workspace_repository_count`
- `harness_workspace_capture_duration_seconds`
- `harness_workspace_capture_retry_total`
- `harness_workspace_topology_mismatch_total`
- `harness_runtime_repository_scope_violation_total`
- `harness_snapshot_restore_failure_total`
- `harness_legacy_snapshot_fallback_total`

日志只记录 run ID、snapshot ID、Hash 前缀、仓库数量和错误 code。

### 22.3 回滚

- 新表和新列均为 additive，关闭开关即可停止创建多仓库 Run。
- 已创建的多仓库 Run 在旧代码不能安全恢复，因此应用回滚前必须先阻止多仓库 Run 进入运行态，或保留
  具备新 Snapshot 读取能力的兼容版本。
- 不删除新表、不反向改写为伪单仓库。
- 旧 Run 始终可通过 legacy adapter 继续读取。

## 23. 领域建模审计评分

### 23.1 当前实现

| 维度 | 评分 | 依据 |
| --- | ---: | --- |
| 聚合边界是否清晰 | 1/3 | Run 边界清楚，但工作区被错误压缩为单仓库 |
| 变化是否被收敛 | 0/3 | monorepo/multi-repo 没有成为显式变化点 |
| 不变量是否可被模型守护 | 1/3 | 单仓库规则严格，但无法守护仓库集合和跨仓库写范围 |
| 行为是否与模型一致 | 1/3 | 行为与单仓库模型一致，与微服务交付语义不一致 |
| 是否支持下一轮变化 | 0/3 | Domain、SQL、Runtime、Artifact 和 Deployment 均写死单仓库 |

### 23.2 目标设计

| 维度 | 目标 | 依据 |
| --- | ---: | --- |
| 聚合边界是否清晰 | 3/3 | Run、Snapshot、Runtime、Deployment 通过不可变 Reference 协作 |
| 变化是否被收敛 | 3/3 | 拓扑、发现、Runtime Layout、Artifact schema、Deployment target 各有收敛点 |
| 不变量是否可被模型守护 | 3/3 | 选择、路径、拓扑、Hash、写范围、Preflight 均有领域校验点 |
| 行为是否与模型一致 | 3/3 | 单仓库和多仓库统一为 Repository Selection |
| 是否支持下一轮变化 | 2/3 | 支持 sibling repo；远程 repo、submodule 展开和跨仓库原子提交仍明确留后 |

## 24. 评审决策与推荐默认值

核心方案已给出明确默认值，评审时只需确认以下产品边界：

| 决策项 | 推荐默认 |
| --- | --- |
| Run 纳入全部候选还是子集 | 用户显式确认子集，manifest 提供默认选择 |
| 主仓库 | 必选一个，由 manifest 建议，UI 可调整 |
| 无 manifest 的发现深度 | 最大 2 层，只返回候选，不自动创建 |
| Submodule | 首版不展开，按父仓库 commit pointer 处理 |
| 嵌套独立仓库 | 首版禁止同时选择互相包含的仓库 |
| Runtime 写隔离 | `-C primary` + 对其他选中仓库逐个 `--add-dir` |
| Deployment 工作目录 | Template v2 首版只允许 `REPOSITORY` |
| 旧 Run | 回填为单元素 Workspace Snapshot，保留一个兼容周期 |

## 25. 验收标准

方案落地完成至少满足：

1. `/home/ubuntu/workspace` 自身不是 Git 仓库时，可以发现并选择多个子仓库创建 Run。
2. Run 创建响应和详情包含确定性的 topology Hash、state Hash、主仓库和仓库数量。
3. 两个仓库的同名文件不会在 Evidence 中冲突。
4. 仓库输入顺序不影响 Snapshot Hash。
5. Implementation 可以修改所有已选仓库，不能修改未选 sibling repo 或父目录。
6. 任一选中仓库拓扑或基线在 Deployment 前变化，部署命令不会启动。
7. 单仓库 Run 不需要特殊 UI 或 Application 流程，行为与现状兼容。
8. 旧 SQLite Run 经回填后可以读取、展示并继续完成。
9. 所有新增领域规则都有先失败后通过的 Domain 单测；Git/SQLite/Runtime 边界有真实轻量集成测试。
10. 多仓库真实试点完成四阶段、人工 Approval、本地部署和最终追踪报告后，才能默认开放能力。
