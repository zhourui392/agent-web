# Workbench 可选 worktree 隔离开发

## Context

用户要求：创建 workbench 时提供一个**"是否使用 worktree 开发"选项**；开启后整个 workbench 共享一个 worktree（所有 stage 在其中跑），不自动合并回 master；页面可查看 worktree 和分支状态。未开启则与现状一致（直接在 repo root 跑）。

现状：workbench stage run 直接在用户给的 `workspaceRoot`（primary repo root）里跑 CLI agent，stage 间共享同一工作树 + WIP，靠 `.workbench/handoff/<stage>/` 文件传递产物。无 worktree 隔离。

目标：worktree 隔离成为可选能力——开启后 primary repo 在独立 worktree 里跑完所有 stage，主仓库不受 WIP 污染；用户在页面上看 worktree/分支状态，自行决定是否合并或丢弃。不自动合并，不强制 git commit。

## 生命周期（开启 worktree 时）

1. **Workbench 创建**（`WorkbenchCreationAppService.create`）：`useWorktree=true` 时，为 primary repo 建 linked worktree（从 master 当前 HEAD），分支 `wb/{workbenchId}`，路径 `{workspaceRoot}/.worktrees/wb/{workbenchId}/`。worktreePath 持久化到 `Workbench` 聚合。
2. **所有 stage run**：CLI workingDir = worktree 路径（非原 repo root）。stage 间共享同一 worktree，handoff 文件自然持久（与现在一致，`WorkspaceHandoffGuard` 逻辑不变）。
3. **Workbench 释放/删除**：删 worktree + 分支。

## 关键约束与解决

### 1. worktree 创建挂点 = `WorkbenchCreationAppService.create()`
- worktree 在创建时建一次，路径持久化到 `Workbench` 聚合根，所有 stage run 复用。不在 per-stage preparation 里建。
- `WorkbenchExecutionPlanProvider.prepare()`（`:112-118`）从 `workbench` 读 `worktreePath`——有则替换 `WorkspaceLayout` 的 primary root，无则用原 repo root（现状）。

### 2. 按 key 替换 primary，不得按下标（CRITICAL）
- `RepositoryScope.java:87` 仓库按 `repositoryKey` 字典序排序，primary 不保证在列表首位。`WorkbenchStageRunPreparationPlan.repositoryRoots()`（`:108-114`）和 `RuntimeEnforcementSnapshot.normalizeRepositories()`（`:148`）继承该序。
- **必须按 `primaryRepositoryKey` 做 key-based 替换**：构造 `RepositoryRootMapping`（`repositoryKey == primaryRepositoryKey ? worktreeRoot : originalRoot`），对 `WorkspaceLayout` 的 `primaryRepositoryRoot`/`readableRoots`/`writableRoots` 统一按 key 查表替换。不得按 `readableRoots[0]`/`writableRoots[0]`。
- 附件同理：`WorkbenchExecutionPlanProvider.appendRepositoryAttachments()`（`:146-153`）对 primary 仓附件按 key 把 `repositoryRoot` 换成 worktree 路径（`relativePath` 不变），通过 `AgentExecutionPlan.immutableAttachments()`（`:73-79`）校验。

### 3. snapshot 完整性不破
- `RuntimeEnforcementSnapshot` 冻结 `repositoryScopeHash`/`primaryRepositoryKey`/`writableRepositoryKeys`（逻辑值，非路径），`requireRepositoryScope` 校验 hash+key 不校验物理路径；`requireExactPreflight` 校验 root count + sandboxMode 不校验路径内容。worktree 路径替换不破坏校验。

### 4. WorkspaceLayout 边界
- worktree 路径在 `{workspaceRoot}/.worktrees/wb/{workbenchId}/` 下 → 满足 `WorkspaceLayout:58-62`（readableRoots ∈ workspaceRoot）。

### 5. multi-repo 范围 = MVP 只 primary
- 非 primary writable repos 保持原始 root（不隔离）。worktree 只替换 primary，preflight root count 不变。

### 6. handoff 与 .gitignore
- `FileSystemWorkspaceHandoffGuard` 把 `.workbench/handoff/` 加进 `.gitignore`，worktree 从 HEAD 继承。handoff 文件在共享 worktree 内自然持久（所有 stage 同一工作树），`.gitignore` 保留防止 agent 误提交。逻辑不变。

### 7. 终态清理
- 无 merge-back（删除）。worktree 在 workbench 释放/删除时清理（复用 `GitWorktreeGateway.removeWorktree` + `deleteBranch`）。
- 崩溃孤儿 worktree：`Workbench` 聚合持久化 `worktreePath`/`worktreeBranch`，重启时扫 `Workbench` 状态 + 清理孤儿（或从 git `worktree list --porcelain` 扫 `wb/` 前缀兜底）。

## 新增/改动组件

| 层 | 文件 | 改动 |
|---|---|---|
| interfaces | `interfaces/workbench/dto/CreateWorkbenchRequest.java` | 加 `useWorktree` 布尔字段 |
| app | `app/workbench/CreateWorkbenchCommand.java` | 加 `useWorktree` 字段 |
| app | `app/workbench/WorkbenchCreationAppService.java` | `useWorktree=true` 时建 worktree；worktreePath 写入 `Workbench` |
| domain | `domain/workbench/Workbench.java` | 加 `worktreePath`/`worktreeBranch` 字段 + `useWorktree` 标记 |
| app | `app/runtime/WorkbenchExecutionPlanProvider.java` | 按 key 替换：有 `worktreePath` 则 `WorkspaceLayout` primary root + readableRoots/writableRoots 中 primary 仓 + 附件 repositoryRoot 替换；无则现状 |
| app | `app/workbench/WorkbenchLifecycleAppService.java` | workbench 释放/删除时清理 worktree + 分支 |
| app | `app/workbench/port/WorkbenchWorktreeGateway.java`（新） | createWorktree/removeWorktree port（复用 `GitWorktreeGateway`） |
| infra | `infra/workbench/ProcessWorkbenchWorktreeGateway.java`（新） | 实现，委托 `GitWorktreeGateway` |
| infra | `infra/workspace/FileSystemWorkspaceHandoffGuard.java` | 无需改（handoff 在共享 worktree 内持久） |
| schema | `resources/schema.sql` + `infra/SqliteInitializer.java` | `workbench` 表加 `use_worktree`/`worktree_path`/`worktree_branch` 列 + ALTER 迁移 |
| frontend | `frontend/js/pages/Workbench.vue` | 创建表单加"使用 worktree 隔离开发"checkbox；详情页头部 popover 展示 worktree/分支 |
| app | `app/workbench/query/WorkbenchDetailView.java` | `RepositoryScopeView` 或 Workbench 级加 `useWorktree`/`worktreeBranch` 字段 |
| infra | `infra/workbench/query/SqliteWorkbenchQueryService.java` | 读 `worktree_path`/`worktree_branch` 列投影到 detail view |

## 不做
- 自动合并回 master（删除）。
- 每 stage 独立 worktree（改为整个 workbench 一个共享 worktree）。
- `ChatRunTerminalParticipant` 接口改（不需区分成功/失败来决定合并）。
- `GitStageMergeGateway` / commit/merge 逻辑（删除）。
- 多 repo 全建 worktree（MVP 只 primary）。

## 测试
- `WorkbenchCreationAppServiceTest`：`useWorktree=true` 建 worktree；`false` 不建。
- `WorkbenchExecutionPlanProviderTest`：有 worktreePath 时 `WorkspaceLayout` 按 key 替换 primary（含 primary 不在首位用例：primary=`z-service`、secondary=`a-library`）；无 worktreePath 时现状不变；primary 附件 repositoryRoot 替换后通过 `immutableAttachments` 校验。
- `WorkbenchLifecycleAppServiceTest`：释放/删除时清理 worktree + 分支。
- 集成：真实 git repo 建 workbench（开 worktree）→ 跑 stage → 验证 CLI 在 worktree 里跑 + 主仓库干净 + 释放后 worktree 清理。
- `mvn test -Dtest='*WorkbenchCreationAppServiceTest,*WorkbenchExecutionPlanProviderTest,*WorkbenchLifecycleAppServiceTest'`
