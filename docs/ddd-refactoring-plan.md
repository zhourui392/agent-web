# DDD 分层重构计划

> 依据：2026-07-23 架构审查（domain 零污染、Repository 端口位置正确，债务集中在 worktree 子域、CLI 调用旁路、边界 DTO 泄漏）。
> 原则：每步独立可合、行为不变优先、每步完成跑 `mvn test` 全绿再进下一步。**不自动打包/重启服务**。
>
> **完成状态（2026-07-23）：DDD-R1—DDD-R4 已全部完成并提交。** 下文“现状/步骤”保留为重构前基线与实施记录，实际结果以各 Phase 的“完成结果”和文末验证记录为准。为避免与 `docs/harness` 的产品里程碑 M0—M7 混淆，本文件历史里程碑统一使用 `DDD-R*`。

## 目标分层（不变）

```
interfaces/ → app/ → domain/ ← infra/(实现 domain 与 app 端口)
```

- domain：聚合 + 领域服务 + Repository 端口，零框架
- app：编排 + Command/View 类型 + 面向 infra 的端口接口（如查询服务、CLI 调用）
- infra：端口实现 + 进程/FS/HTTP/SQL 副作用
- interfaces：HTTP DTO ⇄ app Command 转换，不直注 Repository

## Phase 1：Worktree 子域重构（P1-1，已完成）

### 重构前基线

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

### 完成结果

- Git/文件系统副作用分别落到 `ProcessGitWorktreeGateway`、`LocalWorktreeFileGateway`，App 只依赖 `GitWorktreeGateway`、`WorktreeFileGateway` 端口。
- 新增 `Worktree` 聚合根与 `UserBranchRef`，私有 ref 回收、工作树可删除条件等不变量回到 Domain。
- Controller 返回类型化 Worktree View，前端 JSON 契约保持不变。
- `ProcessGitWorktreeGateway` 真实 Git add/prune 测试及 `worktree.spec.ts` Playwright E2E 通过。
- 提交：`283800b`、`7c02a84`。

## Phase 2：统一 CLI 调用端口（P1-2，已完成）

### 重构前基线

`AgentCliInvoker` 已是接口，但**定义在 `infra/cli/`**，且与 `adapter.AgentGateway`（流式）并存成两条无关联路径；`WorkflowRunner`、`ConversationRefinery` 直注 infra 接口。另 `ChatAppServiceImpl` 直注 `UploadPicStore`/`UploadFileStore` 具体类，`GitConfigAppService` 直注 `GitCredentialCipher`。

### 步骤

1. `AgentCliInvoker` 接口**从 `infra/cli` 移到 `app/agentrun/`（或 `adapter/`→见 Phase 4）**，实现类 `CliAgentInvoker` 留 `infra/cli` 实现之。包名变更，签名不动
2. `AgentGateway` 与 `AgentCliInvoker` 归并到同一端口包，文档注明分工：流式 = `AgentGateway`，同步一次性 = `AgentCliInvoker`（`invokeSync`）
3. 文件存储：定义 `app` 层 `FileStorage` 端口（save/delete/purgeBySession），`UploadPicStore`/`UploadFileStore` 移 `infra/` 并实现之（若已在 infra 则仅改注入类型为端口）
4. `GitCredentialCipher`：接口抽 `app/git/CredentialCipher`，实现留 `infra/git`

### 验证

- `mvn test`；重点 `ChatFlowTest`（SSE 流）、workflow / refinery 相关用例

### 完成结果

- `AgentGateway`、`AgentCliInvoker`、`AgentStreamResult` 统一进入 `app/agentrun/port`，流式与同步调用职责分开、端口位置一致。
- 上传图片/文件和 Git 凭证加密改为 App 端口，具体存储、加密实现留在 Infra。
- 删除 `AgentTypeResolver`，Agent 类型选择规则由 Domain 承载。
- `RefineryProperties`、`PromptTemplateLoader` 等配置组件归位。
- 提交：`843f959`。

## Phase 3：边界泄漏收敛（P2，已完成）

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

### 完成结果

- Chat App API 使用 `StartSessionCommand`、`SendMessageCommand`、App `TruncateResult`，Interface DTO 不再流入 App。
- Refinery 管理读侧拆为 `RefineryAdminQueryService`，Infra 直接投影视图；Controller 不再注入 Domain Repository，Repository 只保留聚合生命周期方法。
- Refinery 参数校验改为 Bean Validation，`GlobalExceptionHandler` 维持原 422 JSON 契约。
- 新增 `CronExpression` VO；`ScheduledTask` 通过工厂、恢复工厂和行为方法维护构造/变更不变量，删除公开 setter。
- `Workflow` 构造期校验步骤，`requireRunnable()` 收回启用状态规则；Application 只负责流程编排。
- 提交：`a2d6bea`。

## Phase 4：包结构收尾（P3，已完成）

1. **`adapter/` 改名/归并**：该包放的是端口接口而非 adapter。方案 A（推荐）：并入 `app/` 对应子域的 `port` 子包；方案 B：改名 `port/`。`AgentGateway`/`UserDirectory`/`AgentStreamResult` 随迁
2. **`infra/ChatProperties` 反向依赖**：`ChatPromptSettings` 从 `app.chatrun` 下沉为配置语义类型（移 `infra` 或 `config/`），切断 infra→app import
3. **`*Properties` 归位**：`FsProperties`/`EnvProperties`/`RefineryProperties` 被 app 大量引用属配置横切，保留 infra 可接受，但在 CLAUDE.md 架构节注明为例外；或统一移 `config/`

### 完成结果

- 删除空的 `adapter/`：`UserDirectory` 迁入 `app/auth/port`；Agent 端口已在 Phase 2 迁入 `app/agentrun/port`。
- `AgentRunProperties`、`ChatProperties`、`EnvProperties`、`FsProperties`、`ResumableChatStreamProperties` 统一迁入 `config/`；`RefineryProperties` 位于 `config/refinery/`。
- App 通过 `TraceContext` 使用 MDC，`Slf4jMdcTraceContext` 留在 Infra；App 已无 `infra` import。
- `DynamicTaskScheduler` 迁入 `infra/schedule` 并实现 `ScheduledTaskRegistrar`，定时触发按任务 ID 重新加载最新聚合。
- ArchUnit 去掉冻结库，App → Infra 从“冻结存量”收紧为严格零违例；Domain 继续保持零外层依赖。
- PMD 插件覆盖 ASM 9.7.1，使 PMD 6.53.0 能正确解析 Java 21 字节码。
- 提交：`f7c8250`。

## 顺序与里程碑

| 里程碑 | 内容 | 状态 | 提交 | 风险控制结果 |
|---|---|---|---|---|
| DDD-R1 | Phase 1 Worktree | ✅ 完成 | `283800b`、`7c02a84` | 真实 Git 测试 + Worktree E2E 通过 |
| DDD-R2 | Phase 2 CLI 端口 | ✅ 完成 | `843f959` | 编译期调用点与相关单测全量迁移 |
| DDD-R3 | Phase 3 边界泄漏 | ✅ 完成 | `a2d6bea` | Domain/App/Controller 分层测试与 422 契约测试通过 |
| DDD-R4 | Phase 4 包收尾 | ✅ 完成 | `f7c8250` | ArchUnit 严格零违例，冻结库删除 |

四个里程碑均按仓库现有 `refactor:` 风格独立提交；DDD-R1 的聚合边界补强作为同一里程碑的第二个独立提交完成。

Harness M2 合并后新增的 `infra/harness/HarnessProperties` 未纳入当时的 Properties 迁移范围；该配置归位作为 Harness M3.0 的局部收尾，不影响本轮全局 App → Infra 零依赖结论。

## 最终验证记录（2026-07-23）

| 检查项 | 结果 |
|---|---|
| `mvn -q test` | ✅ 默认后端快速测试集通过 |
| `spring-flow` 分组 | ✅ 通过；业务链路测试关闭认证 Filter，认证本身由专门测试覆盖 |
| `process-integration` 分组 | ✅ 通过；使用仓库测试桩，未调用真实外部 Agent |
| `git-integration` 分组 | ✅ 通过 |
| `cd tests && npx vitest run` | ✅ 7 个测试文件、102 个测试通过 |
| Worktree Playwright E2E | ✅ `tests/e2e/worktree.spec.ts` 通过 |
| `ArchitectureTest` | ✅ Domain → 外层、App → Infra、App → Interface 均为 0 |
| 静态依赖扫描 | ✅ App 无 Infra/Interface import；Domain 无外层 import；Controller 无 Repository 注入；`adapter/` 与冻结库均无文件 |
| `mvn pmd:check` | ✅ 完成有效 Java 21 扫描，`target/pmd.xml` 为 0 个 `<error>`；仍有 463 条全库存量 P3C 告警，因既有 `failOnViolation=false` 不阻断，本次未扩张清理范围 |
| `git diff --check` / `git diff --cached --check` | ✅ 通过 |

## 明确不做

- 不引入 `sqlite-vec`、不拆模块（单 Maven module 保持）
- 不给 `JsonFileSessionRepo` 这类孤儿 bean 重新接线（另行评估删除）
- 不改前端契约：所有 View record 序列化结果与现有 Map/DTO 的 JSON 一致
