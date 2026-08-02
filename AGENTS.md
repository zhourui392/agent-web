# AGENTS.md

本文件只规定 Agent 修改代码时的决策、禁令和验收门禁。项目入口见 `README.md`，启动见 `docs/getting-started.md`，目录与测试命令见 `docs/development.md`；完整配置见 `src/main/resources/application.yml`；Workbench 细节见 `docs/workbench/README.md`。

## 角色与输出

- 以 Java 资深开发、OO/DDD 视角工作；封装细节、保持主流程清晰，组合优于继承。
- 思考与检索用英文，回复用中文；简短直接、言之有据，不确定时先核实代码。
- 新 Java 类必须包含 `@author alex` 和 `@since`。

## 架构边界

严格遵守 DDD + 六边形四层架构：

```text
interfaces/   Controller、请求/响应 DTO、边界校验与转换
app/          用例编排、事务；严禁业务规则
domain/       聚合、值对象、领域服务、写侧 Repository；不依赖外层
infra/        DB、文件、CLI、HTTP、缓存等外部适配
config/       Spring 装配与配置 Properties
```

Controller 不处理业务规则；Application 只编排 Domain Repository、Gateway、Domain Service；Infrastructure 不替 Domain 作业务判断。

### 业务逻辑归属判据

Application 出现以下代码，必须先把语义下沉到聚合根：

- 遍历聚合内部集合做条件查找。
- 对聚合 getter 结果做非空、状态、角色等语义判断。
- 替聚合校验路径、必填、格式等构造期不变量；改为 `Aggregate.create(...)`。
- 用多个 getter 重组状态迁移、合法性校验或语义查询。

无不变量时聚合可以是数据载体；一旦出现业务语义，必须由聚合方法表达。聚合根不得注入或调用 Repository；跨聚合以 ID/参数引用，需 Repository 编排时使用领域服务。

### Repository 与读模型

- 写侧 Repository 接口放在 Domain，只做聚合生命周期（通常 3–5 个方法），签名只使用 Domain 类型；Infra 负责实现。
- 纯 SELECT 使用 `XxxQueryService`，接口放 App 或 Domain、实现放 Infra；返回 DTO、View 或 `Map`，不得返回半截聚合。
- ORM/JDBC/MyBatis/Spring Data 类型和注解不得泄漏到 Domain 接口；SQL/Mapper 不承载业务判断。
- Repository 与 QueryService 混在同一接口时必须拆分。

## TDD 门禁（强制）

修改 Java 文件前按顺序判断：

1. 是否涉及 `if`、`switch`、条件分支或业务判断？
2. 否：纯委托、固定生命周期、配置、文档可直接修改。
3. 是：先按上述判据确认归属层；错位的规则先下沉 Domain，不能给错位代码补测试。
4. 归属正确后，先写/改测试见红，再做最小实现见绿，最后重构保绿。

测试使用 JUnit 5 + Mockito，采用 Given-When-Then；非 `live` 测试不得调用共享/外部 DB、Redis、MQ、HTTP 或真实 CLI，任务范围内的临时 SQLite/文件系统集成测试除外。按层选择：

| 层 | 隔离方式 | 重点 |
| --- | --- | --- |
| Domain | 不 Mock Domain，不启动 Spring | 不变量、状态迁移、业务规则 |
| Application | Mock Repository/Gateway/Domain Service | 编排顺序、事务边界 |
| Infrastructure | Mock Client/Mapper，或用 SQLite/`@TempDir` 轻量集成 | 缓存、协议和持久化适配 |
| Interface | Mock Application Service；必要时 `@WebMvcTest` | 参数、DTO、状态码 |
| Mapper/SQL/JPA Entity | 集成测试 | 查询与映射正确性 |

完整 `@SpringBootTest` 仅用于 SSE 时序/续传、事务代理、Scheduler、Filter Chain、会话持久化等跨切边界。项目级 TDD 约定使用 `/java-tdd`，快速测试使用 `/fast-test`。

## 高风险功能边界

- CLI 测试必须使用 `TestCliStub`、Runtime Stub 或 E2E fixture；只有明确标记的 `live` 测试可以调用真实 CLI/登录态。
- Workbench 的 Repository Scope 和已启动 Run Snapshot 不可变；浏览器断开不取消后台 Run。
- Handoff/Review 采用与修改必须由用户明确操作；不能从对话内容或阶段切换推导授权。
- `GIT_COMMIT`、`GIT_PUSH`、`LOCAL_DEPLOY`、`PRODUCTION_WRITE` 只能创建类型化 Proposal，初态为 `PROPOSED`；高影响 Executor 默认关闭。
- Admin Workbench 只允许安全投影查询、Stop、单 Run Reconcile，不得代 Owner 对话、改 Handoff/Override 或批准 Operation。
- NATIVE 只允许普通聊天手动选择，不能成为全局默认或进入 schedule/workflow/refinery；AgentKit 类型仅允许位于 `config.nativeagent`、`infra.nativeagent`。
- NATIVE 只读取 `AGENT_NATIVE_API_KEY`、`AGENT_NATIVE_BASE_URL`，不得回退到 Codex/OpenAI CLI 凭据。

## 开发约定

- 修改不熟悉的子系统前，先读 `README.md` 对应章节及其专题文档。
- 新增 helper/service/adapter/validator/parser/repository/抽象前，先用 `rg` 搜索现有能力。
- 改动保持在用户请求范围内；除非安全完成任务所必需，不做大范围整理。
- 样板代码优先 Lombok/MapStruct；通用能力优先 Guava、Commons、Jackson、Spring Util，禁止重复造轮子。
- 禁止通配符导入；保持项目声明的 Java 21，不得降级 `java.version` 规避环境问题。
- 不主动修改 `agent-paths.yml`、`env.local`、`data/`、本地 DB 或生成产物，除非任务明确要求。
- 前端源码只放 `frontend/`；可复用纯逻辑先抽到 `frontend/js/lib`，再在 `tests/unit` 补 Vitest。禁止在已停用的 `src/main/resources/static` 新增页面。
- Playwright 优先语义化 locator 或稳定 `data-test`，不得依赖 Element Plus 内部 class。

## 敏感信息

- 禁止在源码、配置模板、文档、日志、Fixture 或 Artifact 中新增硬编码凭据；生产优先进程环境或 Secret Store。
- 必须文件化的服务端秘密只放 Git 忽略的 `data/secrets.properties`；写入前执行 `git check-ignore -q data/<path>`。目录权限 `700`，文件权限 `600`。
- 不得读取、复制或改写用户级 `~/.codex`、`~/.claude` 认证目录；除非用户明确改变 CLI 鉴权方式，不把 CLI API Key 写入 `data/secrets.properties`。
- 命令输出和回复不得打印敏感值，只能确认变量名、是否存在或不可逆摘要；禁止 `git add -f data/...`。
- `env.local` 仅保留历史防误提交规则，不作为新增秘密的存储位置。

## 构建与测试

- 完整命令见 `docs/development.md#测试`；优先运行覆盖改动的最小测试。
- Linux 直接运行 Maven 时使用 `JAVA_HOME=/usr/local/jdk-21`。
- 显式运行默认排除的慢分组时必须覆盖 `test.excludedGroups`，具体分组以 `pom.xml` 为准。
- 不主动执行 `mvn package`、`./scripts/service.sh restart` 或部署命令，除非用户明确要求。
- push 到 `master` 前必须完成 `docs/development.md#发布前门禁` 所列的后端、`frontend/`、`tests/` 三组 CI 门禁；后两个工程的 typecheck 都必须运行。

## 完成前检查

- Java 业务逻辑：有实现前能失败的测试，并完成红—绿—重构。
- Controller/API：覆盖参数校验、状态码与安全响应投影。
- SQLite Repository：使用真实 SQLite 验证；文件存储使用 `@TempDir`。
- 前端行为：运行对应 Vitest 或 Playwright spec。
- Workbench 边界：按影响覆盖 Owner/Admin 授权、feature flag、CAS/幂等冲突、Repository Scope、symlink/`NOFOLLOW`、Hash、TTL 和清理。
- Workbench 主流程：按影响选择 Mock Workbench、Admin Workbench、Spring + SQLite + Runtime Stub E2E；Stub 通过不等于真实 CLI 试点通过。
- 无法运行测试时，明确说明原因、未验证项和残余风险。
