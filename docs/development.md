# 开发指南

修改代码前先阅读根目录 [`AGENTS.md`](../AGENTS.md)。该文件规定 DDD + 六边形分层、Java TDD 门禁、敏感信息禁令和完成前验收要求。

## 项目结构

```text
src/main/java/com/example/agentweb/
├── interfaces/   REST Controller + DTO（Chat / Fs / Auth / Share / Worktree / ScheduledTask /
│                 AdminWorkflow / GitConfig / Metrics / RecallMetrics / Refinery* / Workbench / Admin*）
├── app/          应用编排（无业务逻辑）：agentrun、chat、scheduled-task、workflow、git、metrics、
│                 refinery、workbench、runtime；外部能力端口位于相应子域的 port 或边界包
├── domain/       聚合根、值对象、领域服务和写侧仓储端口：chat、workflow、git、refinery、
│                 issuelog、auth、schedule、slashcommand、worktree、workbench、workspace、
│                 capability、runtime、shared
├── infra/        CLI 方言与进程、SQLite、auth、workflow、git、schedule、log、metrics、refinery、
│                 issuelog、workbench、workspace、capability、runtime、setting 等外部适配
└── config/       Web MVC、Spring 装配和配置 Properties（含 capability、runtime、workbench）

src/main/resources/
├── application.yml              主配置，各 agent.* 节点带内联注释
├── agent-paths.yml              数据库尚未配置时的机器路径种子
├── schema.sql                   SQLite 建表脚本
├── *-prompt.md                  Refinery 评分与 issue-log prompt
└── capability/                  可信 Capability Catalog（rules / skills / mcp-servers）

frontend/                        Vue 3 + Element Plus + Vite MPA 源码
frontend/dist/                   前端构建产物
tests/                           独立前端测试工程：Vitest + Playwright
docs/                            使用、架构、设计、发布和运维文档
```

issue-log 写侧（诊断预留）位于 `domain/issuelog` 与 `infra/issuelog`；其配置、模板和测试保留。
Workspace Context 对既有 `docs/issue-log/` 的读取仍是受 Scope 约束的只读入口。

分层职责：

- `interfaces` 只做 Controller、请求/响应 DTO、边界校验与转换。
- `app` 只编排用例和事务，不承载业务规则。
- `domain` 承载聚合、不变量、状态迁移、领域服务和写侧 Repository 接口，不依赖外层。
- `infra` 实现数据库、文件、CLI、HTTP、缓存等外部适配，不替 Domain 做业务判断。
- `config` 只负责 Spring 装配和配置 Properties。

## 测试

测试分为三层：后端 JUnit 5 + Mockito、前端 Vitest 纯逻辑单测、Playwright E2E 主链路。优先运行覆盖改动的最小测试。

Linux 直接运行 Maven 时使用 JDK 21：

```bash
JAVA_HOME=/usr/local/jdk-21 mvn -B test
```

常用命令：

```bash
mvn -B test                                                   # 默认后端快速测试集
mvn test -Dtest=ChatFlowTest                                  # 单个测试类
mvn test -Dtest='*RepoTest'                                   # Infra 轻量集成测试
mvn pmd:check                                                  # Alibaba P3C
cd frontend && npm run typecheck && npm run lint && npm run build
cd tests && npm run typecheck && npm test                      # Vitest
cd tests && npm run e2e                                       # 通用 Playwright E2E
cd tests && npx playwright test -c playwright.workbench.config.ts
cd tests && npx playwright test -c playwright.admin-workbench.config.ts
cd tests && npx playwright test -c playwright.workbench-real.config.ts
```

Playwright 必须在 `tests/` 目录运行，或者从仓库根显式传入 `-c tests/<config>.ts`，否则可能把 Vitest 单测误当作 E2E 加载。

`playwright.workbench-real.config.ts` 中的“real”只表示使用真实 Spring、SQLite、进程编排和 Runtime Stub 边界，不会调用真实 Codex/Claude 模型或本机登录态，不能替代真实 CLI 试点。

### Maven 测试分组

`mvn test` 默认排除以下慢分组，因此默认测试集不等于包含真实外部依赖的全量测试：

- `live`：真实外部 CLI 或登录态。
- `git-integration`：真实 Git / worktree。
- `spring-flow`：跨切面的完整 Spring 流程。
- `process-integration`：真实子进程编排。

显式运行慢分组时必须覆盖 `test.excludedGroups`，否则目标 tag 仍会被默认排除；具体分组以 [`pom.xml`](../pom.xml) 为准。

关键测试范围：

- `ChatFlowTest` / `FeedbackFlowTest`：聊天流程与会话反馈。
- `ResumeSessionTest`：会话恢复。
- `ScheduledTaskTest`：定时任务、`@Scheduled` 装配和 SQLite 锁退避。
- `FsControllerTest` / `UploadPicStoreTest`：文件系统与图片上传。
- `SlashCommandScannerTest` / `SlashCommandExpanderTest`：自定义命令。
- `WorktreeControllerTest` / `BranchNameValidatorTest`：Worktree 与分支名校验。
- `cli/Claude|CodexCliDialectTest` / `cli/*EventNormalizer*Test`：CLI 方言与事件归一化。
- `infra/AgentCliGatewayTest` / `infra/AgentTypeResolverTest`：网关与类型兜底。
- `app/workflow/*` / `domain/workflow/*` / `infra/workflow/*`：多步工作流编排与持久化。
- `domain/refinery/*` / `app/refinery/*` / `infra/refinery/*`：知识精炼评分、召回重排、embedding 和向量库。
- `domain/workbench/*` / `app/workbench/*` / `infra/workbench/*` / `interfaces/workbench/*`：Workbench 不变量、编排、SQLite/文件适配和 API。
- `ArchitectureTest`：分层约束和 Spring AOP 代理类不得 `final` 等架构守卫。

## 发布前门禁

Push 到 `master` 前必须以当前工作树重新运行三组 CI 门禁；`frontend/` 和 `tests/` 是两个独立工程，两边的 typecheck 都不能省略：

```bash
mvn -B test
cd frontend && npm run typecheck && npm run lint && npm run build
cd tests && npm run typecheck && npm test
```

2026-08-01 的 Workbench 发布候选曾记录默认后端集 2442 项、`ArchitectureTest` 18/18、Vitest 50 files / 523 tests、Workbench Mock 14/14、Admin Workbench 2/2、真实边界 Stub 4/4。它们只是历史证据，不能替代当前工作树验证；详细口径、慢分组和未完成门禁见[发布就绪快照](workbench/release-readiness-2026-08-01.md)。

除非任务明确要求，不主动执行 `mvn package`、服务重启或部署命令。
