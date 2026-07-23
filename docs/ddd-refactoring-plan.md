# DDD 分层重构计划

> 依据：2026-07-23 架构审查（domain 零污染、Repository 端口位置正确，债务集中在 worktree 子域、CLI 调用旁路、边界 DTO 泄漏）。
> 原则：每步独立可合、行为不变优先、每步完成跑 `mvn test` 全绿再进下一步。**不自动打包/重启服务**。

## 目标分层（不变）

```
interfaces/ → app/ → domain/ ← infra/(实现 domain 与 app 端口)
```

- domain：聚合 + 领域服务 + Repository 端口，零框架
- app：编排 + Command/View 类型 + 面向 infra 的端口接口（如查询服务、CLI 调用）
- infra：端口实现 + 进程/FS/HTTP/SQL 副作用
- interfaces：HTTP DTO ⇄ app Command 转换，不直注 Repository

## Phase 1：Worktree 子域重构（P1-1，收益最大）

### 现状

`app/WorktreeService`（818 行）混合三类职责：

| 职责 | 现状位置 | 目标位置 |
|---|---|---|
| git 子进程执行（fetch/pull/worktree add/prune、`execWithTimeout`） | app | `infra/git/GitProcessExecutor` |
| 文件系统遍历（`walkFileTree`、符号链接、Windows 判定） | app | `infra/git/`（或复用 `UploadFileStore` 同级 FS 组件） |
| 编排 + 规则（用户 bucket、分支校验、回收私有 ref） | app | 保留 app，规则下沉 domain |

且 `domain/worktree/` 只有 VO/Policy、无聚合根；公开方法全部返回 `Map<String, Object>`。

### 步骤

1. **抽 infra 端口**（不改行为）：
   - 在 `app/worktree/` 定义 `GitWorktreeExecutor` 端口：`fetch(repoDir)` / `pull(repoDir)` / `addWorktree(repoDir, path, ref)` / `prune(repoDir)` / `currentRef(path)` / `defaultBranch(repoDir)` 等，全部返回类型化结果 record（`GitExecResult(exitCode, stdout, stderr)`）
   - `infra/git/ProcessGitWorktreeExecutor` 实现，代码从 `WorktreeService` 平移（`execWithTimeout`、`isStaleWorktreeError`、`isWindows`、`createDirectoryLink` 等）
2. **建聚合根 `Worktree`**（`domain/worktree/`）：
   - 字段：`id`（userSlug + repoName + branch）、`path`、`checkedOutRef`、`repoLeafCount`
   - 行为：`requireRemovable()`（回收私有 ref 前的不变量校验，现散在 `removeWorktree`/`recyclePrivateRef`）
   - `BranchNameValidator`、`WorkspacePathPolicy` 保持不变，继续由 app 注入
3. **View 类型替换裸 Map**：`app/worktree/WorktreeView` / `BranchSwitchResult` / `BranchUpdateResult` record；`WorktreeController` 返回值随之类型化（前端 JSON 结构保持不变，仅序列化来源从 Map 换 record）
4. **服务瘦身**：`WorktreeService` 迁入 `app/worktree/WorktreeAppService`，只留编排：校验 → 调端口 → 组装 View

### 验证

- 每步后 `mvn test`
- 新增 Infra 轻量集成测试：`ProcessGitWorktreeExecutor` 对 `@TempDir` 真实 git repo 跑 add/prune（不起 Spring）
- E2E：`cd tests && npx playwright test worktree.spec.ts`

## Phase 2：统一 CLI 调用端口（P1-2）

### 现状

`AgentCliInvoker` 已是接口，但**定义在 `infra/cli/`**，且与 `adapter.AgentGateway`（流式）并存成两条无关联路径；`WorkflowRunner`、`ConversationRefinery` 直注 infra 接口。另 `ChatAppServiceImpl` 直注 `UploadPicStore`/`UploadFileStore` 具体类，`GitConfigAppService` 直注 `GitCredentialCipher`。

### 步骤

1. `AgentCliInvoker` 接口**从 `infra/cli` 移到 `app/agentrun/`（或 `adapter/`→见 Phase 4）**，实现类 `CliAgentInvoker` 留 `infra/cli` 实现之。包名变更，签名不动
2. `AgentGateway` 与 `AgentCliInvoker` 归并到同一端口包，文档注明分工：流式 = `AgentGateway`，同步一次性 = `AgentCliInvoker`（`invokeSync`）
3. 文件存储：定义 `app` 层 `FileStorage` 端口（save/delete/purgeBySession），`UploadPicStore`/`UploadFileStore` 移 `infra/` 并实现之（若已在 infra 则仅改注入类型为端口）
4. `GitCredentialCipher`：接口抽 `app/git/CredentialCipher`，实现留 `infra/git`

### 验证

- `mvn test`；重点 `ChatFlowTest`（SSE 流）、workflow / refinery 相关用例

## Phase 3：边界泄漏收敛（P2）

### 3.1 app 层去 interfaces DTO（P2-1）

- `ChatAppService` 引入 app 层 Command：`StartSessionCommand` / `SendMessageCommand`；`TruncateResult` 移 `app/`（它本就不是 HTTP 专属）
- `ChatController` 负责 DTO→Command 转换；`ChatAppServiceImpl` 签名同步替换
- 影响面：`ChatController` + 调 `ChatAppService` 的测试

### 3.2 Controller 不直注 Repository（P2-2）

- `RefineryAdminController` 的 `RagChunkRepository` / `DiscardedRefineRepository` 直注，改为经 app 层 `RefineryAdminQueryService`（CQRS 读侧，参照 `ChatSessionQueryService` 模式：接口在 `app/refinery`，实现可委托 Repository）
- 手写 422 err-map 改为 `@Validated` + `@Min/@Max` 参数注解，错误体统一走 `GlobalExceptionHandler`
- 排查同类：`WorktreeController` 直用 `BranchNameValidator`（domain 服务，可接受但建议收进 app 编排内）

### 3.3 贫血聚合富化（P2-3，低风险渐进）

- `domain/schedule/CronExpression` 值对象：收口"合法 cron"判定（现 `ScheduledTaskServiceImpl:68-95` 借 Spring `CronTrigger`，框架类退出 domain 语义）
- `ScheduledTask` 增行为：`toggle()` / `rename(name)` / `reschedule(cron)` / `recordRun(sessionId, at)`，setter 收窄为包私有
- `Workflow` 同上：构造期校验步骤非空/有序，去裸 setter

### 验证

- Domain 单测覆盖新 VO/行为（零容器，<10ms）
- `@WebMvcTest(RefineryAdminController.class)` 验 422 契约不变
- `mvn test` 全绿

## Phase 4：包结构收尾（P3）

1. **`adapter/` 改名/归并**：该包放的是端口接口而非 adapter。方案 A（推荐）：并入 `app/` 对应子域的 `port` 子包；方案 B：改名 `port/`。`AgentGateway`/`UserDirectory`/`AgentStreamResult` 随迁
2. **`infra/ChatProperties` 反向依赖**：`ChatPromptSettings` 从 `app.chatrun` 下沉为配置语义类型（移 `infra` 或 `config/`），切断 infra→app import
3. **`*Properties` 归位**：`FsProperties`/`EnvProperties`/`RefineryProperties` 被 app 大量引用属配置横切，保留 infra 可接受，但在 CLAUDE.md 架构节注明为例外；或统一移 `config/`

## 顺序与里程碑

| 里程碑 | 内容 | 风险 |
|---|---|---|
| M1 | Phase 1 Worktree | 高（FS/进程行为）——平移不改逻辑，靠 E2E 兜底 |
| M2 | Phase 2 CLI 端口 | 中（接口搬包，编译期全暴露） |
| M3 | Phase 3 边界泄漏 | 低-中（Controller 契约靠切片测试锁定） |
| M4 | Phase 4 包收尾 | 低 |

每个里程碑单独提交，commit message 遵循仓库现有 `refactor:` 风格。M1 完成前不动 Phase 2/3，避免多线并发重构。

## 明确不做

- 不引入 `sqlite-vec`、不拆模块（单 Maven module 保持）
- 不给 `JsonFileSessionRepo` 这类孤儿 bean 重新接线（另行评估删除）
- 不改前端契约：所有 View record 序列化结果与现有 Map/DTO 的 JSON 一致
