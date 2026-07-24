# 研发交付 Harness 首版能力范围

> 状态：M4 功能集成、真实 Codex 在线/离线合同与受控样例验收已完成；真实需求验收待完成
> 最后更新：2026-07-24
> 上位设计：[研发交付 Harness 目标架构](01-target-harness-architecture.md)
> 建设顺序：[建设阶段与里程碑](03-milestones.md)
> 当前实现记录：[M4 四阶段纵向切片](m4/README.md)；执行平面设计：[M3 MCP 与 Runtime](04-m3-detailed-design.md)

## 1. 首版定义

首版不是只完成需求分析，也不是先做一个通用 MCP 平台。首版要完成一个可演示、可恢复、可审计的四阶段纵向切片：

```text
创建需求 Run
  → 需求分析及批准
  → 方案设计及批准
  → TDD 实现及测试证据
  → 本机部署验证
  → 最终报告
```

同时，首版要证明三种动态能力能够按阶段受控装配：

- Prompt Pack：按阶段加载、版本化和计算 Hash；
- Skills：从可信注册目录发现，按阶段选择并固化包快照；
- MCP：从管理员注册表选择，按阶段授权，并由 Codex Runtime Adapter 以 CLI 原生方式挂载。

首版采用“薄 Harness”：`agent-web` 管理流程、权限、快照、产物和证据，具体 Agent 与 MCP 工具调用优先交给 CLI 原生运行时。首版不在 Java 进程内实现完整 MCP Client。

首版建设阶段先使用 Fixture、Fake MCP 和受控样例验证 Harness 自身能力，不要求现在绑定真实业务需求。四阶段功能完成后，再输入真实需求做最终纵向验收；真实需求验收仍属于 MVP Definition of Done。

## 2. 成功判据

首版成功不是“页面上出现四个步骤”，而是用一个真实但风险可控的需求完成以下验收：

1. 创建 Run 后，四阶段只能按合同顺序执行。
2. Agent 执行前形成包含 Prompt、Skill、MCP 和 Runtime Enforcement 的不可变 Capability Snapshot。
3. 每阶段 Prompt、Skill 和 MCP 的实际版本与 Hash 可查询。
4. 需求和设计必须人工批准，批准绑定 Artifact Hash。
5. 实现阶段能在白名单工作空间修改代码并保存测试命令证据。
6. 部署阶段仅支持本机环境，并在显式批准后执行预配置命令。
7. 应用重启后可恢复到最近的安全状态；不会自动重放部署副作用。
8. 最终报告能追踪至少一条需求到设计、代码、测试和部署验收结果。
9. Feature Flag 关闭时，现有 Chat、Workflow、Diagnose 行为不变。

## 3. 首版用户与入口

### 3.1 用户角色

- `ADMIN`：创建 Harness Definition/Run、注册可信能力、批准阶段和部署动作。
- 普通登录用户：首版可只读查看被授权的 Run；是否允许创建 Run 作为后续权限扩展。
- Agent Runtime：系统执行主体，不是审批主体，不能给自己授权。

### 3.2 首版入口

首版至少提供管理 API；管理页面取决于纵向切片进度，但最终 MVP 发布应提供一个最小页面，避免只能靠手工改数据库操作。

最小页面包括：

- Run 列表与创建表单；
- 四阶段状态条；
- 当前阶段输入、产物和阻断原因；
- 能力快照查看；
- 开始、批准、拒绝修改、重试和取消按钮；
- 实时/轮询执行日志；
- 最终报告入口。

## 4. 首版功能范围

### 4.1 Run 创建与基线

创建 Run 时输入：

- 需求标题与正文；
- 受白名单约束的 `workingDir`；
- Agent 类型；
- 环境，首版固定为 `local`；
- Harness Definition 版本；
- 可选的显式 Skill/MCP 请求；
- 幂等键。

创建时记录：

- 当前 Git 分支、HEAD、工作区是否干净；
- 当前项目识别信息；
- Definition 和 Policy 版本；
- 创建人和时间；
- 原始需求 Artifact 及 Hash。

首版允许在脏工作区运行，但必须把原有变更作为基线记录，并在实现阶段区分用户原有变更与本次新增变更。无法可靠区分时阻断进入部署阶段。

### 4.2 固定四阶段状态机

首版只支持固定顺序：

```text
ANALYSIS → DESIGN → IMPLEMENTATION → DEPLOYMENT
```

Stage 状态：

```text
PENDING / RUNNING / WAITING_INPUT / WAITING_APPROVAL /
CANCELLING / PASSED / FAILED / INVALIDATED / CANCELLED
```

首版需要支持：

- 开始阶段；
- Agent 执行完成后进入 Gate；
- 等待用户补充信息；
- 人工批准或拒绝并要求修改；
- 失败后创建新 Attempt 重试；
- 取消当前 Run；
- 修改上游 Artifact 后使下游结果失效；
- 服务启动时把未确认的外部副作用执行置为“需要人工对账”，不自动重放。

首版不支持自定义阶段、并行阶段和任意 DAG。

有活动 Runtime 时，取消必须经过 `CANCELLING`：先持久化取消意图，再终止进程，确认终止后进入 `CANCELLED`。没有活动 Runtime 的 DRAFT/PENDING Run 可以直接取消。`CANCELLING` 仍占用唯一活动 Attempt，但不允许继续登记 Artifact、运行 Gate 或请求 Approval。

### 4.3 阶段 Prompt Pack

首版提供四个内置版本化 Prompt Pack：

```text
harness-prompt-packs/
├── common/1.0.0/
├── analysis/1.0.0/
├── design/1.0.0/
├── implementation/1.0.0/
└── deployment/1.0.0/
```

每个 Stage Pack 至少包含：

- `manifest.yml`；
- `system.md`：阶段职责、允许和禁止行为；
- `task.md`：阶段任务模板；
- `output-contract.md`：产物 Schema 和文件要求；
- `gate-hints.md`：Agent 可见的完成检查表。

首版装配顺序固定：

```text
平台安全规则
→ 环境 Guardrail
→ Stage Contract
→ Stage Prompt Pack
→ 已选择 Skill 调用说明
→ 已批准上游 Artifact
→ 当前输入
→ 输出契约
```

首版要求：

- 启动阶段时读取，不要求应用重启；
- 同一 Attempt 读取后生成快照，文件后续变化不影响该 Attempt；
- 保存每个资源 Hash、最终 Prompt Hash 和装配清单；
- 必需 Prompt 资源缺失或解析失败时阻断执行；
- 不自动读取或重复注入工作区 `AGENTS.md`/`CLAUDE.md`，继续由 CLI 原生规则负责。

### 4.4 Skill 动态发现与选择

首版支持“预注册、动态选择”，不支持“任意下载、自动信任”。

可信来源仅包括：

- 管理员配置的平台 Skill 根目录；
- 管理员批准的用户 Skill 根目录；
- 当前工作区内经过 Run 级显式批准的 Skill 目录。

首版 Skill Manifest 支持：

- ID、版本、描述；
- 适用阶段；
- 技术标签和显式触发条件；
- `SKILL.md` 入口；
- 包含的 references/templates/scripts 路径；
- 所需文件、命令和 MCP 能力声明；
- Runtime 兼容性；
- 信任来源。

首版选择规则：

1. Stage Contract 的默认 Skill；
2. 用户显式选择且与阶段兼容的 Skill；
3. 基于项目技术标签的确定性匹配；
4. 冲突或版本不兼容时阻断并给出原因，不由模型自行决定。

首版只把已选 Skill 以 CLI 原生方式或明确指令提供给 Runtime；是否读取 Skill 引用资源由具体 Runtime Adapter 处理。已选 Harness Skill 包计算完整 Package Hash，至少包含 Manifest、`SKILL.md` 及 Manifest 声明的资源。Codex M3 采用“已选 Skill 内容固化进 Prompt、所有自动发现 Repo Skill 通过本次 `skills.config` 禁用”的确定性路径；Preflight 对 Repo Skill 保存 `id + relativeEntryPath + SKILL.md entryHash`，Domain `WorkspaceSkillTrustPolicy` 必须把该入口 Hash 与可信 Catalog 的同一入口文件匹配，不能以运行时禁用代替信任校验。未来若允许 CLI 原生加载 Repo Skill，必须升级为完整 Package Hash 校验并新增兼容性决策，不能沿用当前只禁用自动加载的合同。

首版不包含：

- 从 GitHub/市场自动安装 Skill；
- 复杂语义检索选 Skill；
- Skill 自动升级；
- 选择 Skill 即自动执行其中脚本；
- 跨 Skill 的复杂依赖求解。首版只支持无依赖或一层必需依赖，并拒绝循环。

### 4.5 MCP 注册与阶段授权

首版提供管理员维护的 MCP Registry。注册项至少包含：

- ID、版本、传输类型；
- 启动/连接配置的安全引用；
- 适用 Runtime；
- 能力列表及 READ/WRITE 风险分类；
- 允许阶段；
- 启动和调用超时；
- 凭据引用名，不含凭据值；
- 启用状态和配置 Hash。

首版只承诺：

- 支持一类只读 MCP Server 完成纵向演示，例如需求/文档查询；
- 根据 Stage Contract 挂载或不挂载 Server；
- 执行前做配置和兼容性 Preflight；
- 通过首个 CLI Runtime 的原生 MCP 能力执行；
- 同时下发 required、`enabled_tools`、`disabled_tools`、`startup_timeout_sec` 和 `tool_timeout_sec`；
- Snapshot 能力只通过本次 CLI `-c` 覆盖传入，不依赖被 `--ignore-user-config` 忽略的配置文件；
- 生成隔离的临时配置并在结束后清理；
- 日志、Prompt、API 和 Snapshot 不出现 Secret 明文；
- Runtime 无法可靠限制敏感 Tool 时不挂载该 Server。

首版不包含：

- Java 进程内通用 MCP Client；
- MCP Marketplace；
- 任意用户提交 stdio 启动命令；
- 外部写操作；
- OAuth 交互式授权；
- 同一 Server 的多租户凭据委派；
- 生产部署 MCP。

Claude Runtime 的 MCP 适配放到 MVP 后续里程碑；统一端口和兼容性测试必须在首版预留。

MCP 授权使用专门的 Server/Capability/Selection/Runtime Enforcement 领域模型，不把 Server、Tool、required、风险、阶段和超时编码为通用 `resource String` 分支。Domain 直接产出 Tool allow/deny 与两个 timeout；Infrastructure 只翻译为 CLI 配置，不能重新推导授权。

### 4.6 Capability Snapshot

Agent 执行前按以下顺序处理：

```text
发现可信 Catalog → Runtime Preflight 采集实际版本/Enforcement/工作区清单 →
Domain 校验 Repo Skill 信任 → 选择 Prompt/Skill/MCP → 解析最小依赖 →
Stage/Grant/Environment/Runtime 权限求交 → Domain 可执行性判断 → 固化 Snapshot →
保存 PREPARED RuntimeExecution → 提交事务 → 开始 Agent 执行
```

首版 Snapshot 至少记录：

- Snapshot Schema Version、Stage、Attempt；
- Prompt Pack ID/Version/Hash；
- Skill ID/Version/Package Hash 及选择原因；
- `schemaVersion=M3.1` 的 MCP ID/Version/required/精确 Tool allow/deny/启动与 Tool timeout/配置 Hash；
- 工作区 Repo Skill 的 ID、规范相对入口路径、`SKILL.md` Entry Hash、项目配置扫描结论与稳定 Inventory Hash；运行时禁用路径由该清单确定性派生；
- 文件读写根；
- 逻辑命令白名单；
- 环境、Runtime 和 Policy 版本；
- Runtime Enforcement Profile、实际 CLI 版本、兼容基线及求交结果；
- Snapshot Hash。

一个 Attempt 最多绑定一个完整 Snapshot 和一个 RuntimeExecution。一旦 Snapshot 固化，不允许原地修改；能力或输入基线变化必须创建新 Attempt。旧 M2 Snapshot 以 MCP 空列表读取并保持原 Hash；完成性审计前缺少 required/allow/deny/双 timeout/Repo Skill 指纹/实际版本的临时 M3 Snapshot 也只读。两者需要执行时都必须创建新 Attempt。

### 4.7 Artifact 与 Evidence

首版采用混合存储：

- SQLite：状态、元数据、Hash、事件、Approval、GateResult、追踪链接；
- `agent.harness.artifact-root`：Markdown、JSON、命令输出和日志等正文；
- Artifact Root 默认位于源码工作区之外，并受路径策略保护。

首版阶段产物：

```text
ANALYSIS
  requirement.md
  acceptance-criteria.json
  impact-analysis.md
  open-questions.md

DESIGN
  solution.md
  change-plan.json
  test-strategy.json
  deployment-plan.md
  rollback-plan.md

IMPLEMENTATION
  changed-files.json
  traceability.json
  test-evidence.json
  implementation-summary.md

DEPLOYMENT
  preflight.json
  build-evidence.json
  deployment-record.json
  acceptance-result.json
  final-report.md
```

首版 Artifact 规则：

- 产物更新创建新版本；
- 保存 SHA-256；
- 下游 Stage 绑定上游批准版本；
- 修改已批准产物会使 Approval 和下游结果失效；
- 单个 Artifact 和单次命令输出配置大小上限；
- 输出在落盘前做基础 Secret Redaction。

### 4.8 确定性 Gate 与人工 Gate

首版不做模型打分 Gate。每个阶段至少有：

| 阶段 | 确定性 Gate | 人工 Gate |
| --- | --- | --- |
| ANALYSIS | 必需 Artifact、JSON Schema、Requirement/AC 编号完整 | 批准需求基线 |
| DESIGN | 必需 Artifact、需求到设计/测试映射完整 | 批准设计基线 |
| IMPLEMENTATION | Diff 存在、测试命令退出码、追踪覆盖、敏感文件检查 | 批准待部署版本 |
| DEPLOYMENT | 版本一致、Preflight、健康检查、AC 结果 | 确认交付完成；部署动作本身另有 Approval |

拒绝必须包含理由，并创建修订 Attempt，不能覆盖旧结果。

### 4.9 Runtime Execution 与 Agent Runtime

首版新增 `RuntimeExecution` 聚合，并在 `app/harness/port` 定义统一的 `AgentRuntimeGateway` 与 `AgentExecutionSpec`。Codex CLI Infrastructure Adapter 完成端到端纵向切片；Claude CLI 保持现有业务可用，并通过端口边界保证未来适配不会污染 Domain/Application。

RuntimeExecution 首版状态：

```text
PREPARED / STARTING / RUNNING / CANCEL_REQUESTED /
SUCCEEDED / FAILED / TIMED_OUT / CANCELLED / LOST
```

首版 Runtime 能力：

- 启动阶段 Agent；
- 流式或轮询获取归一化事件；
- 保存 Runtime Execution ID；
- 空闲超时、绝对超时和输出上限；
- 显式取消；
- 通过单次覆盖注入隔离的 Prompt/Skill/MCP 配置；
- 有界探测实际 Runtime 版本，阻断祖先 `.codex/config.toml` 和 Snapshot 外 Repo Skill，并保存实际 PID/进程组 Handle；
- 返回结构化最终状态和 Artifact 输出；
- 使用 Stub 完成默认测试，真实 CLI 只进入显式 `live`/手工验证。

首版执行规则：

- `startStage` 只创建/打开 Attempt，执行 Agent 使用独立命令；
- Snapshot、Run 绑定和 `PREPARED` RuntimeExecution 在同一事务保存；
- `AgentRuntimeGateway.start()` 只能在该事务提交后调用；
- Runtime 成功不等于 Stage 通过，Artifact、Gate 和 Approval 仍由 `HarnessRun` 管理；
- Runtime 回调使用 `executionId + sequence` 幂等；
- 相同幂等键与相同 Stage 返回原 Execution 的真实状态/Location，不同 Stage 复用同一键返回冲突；
- 已启动但终态不明的 Execution 只允许对账，不允许自动重放；
- 取消意图提交后才能调用 Gateway 停止进程，退出码 0 不能覆盖取消语义。

现有聊天 `AgentGateway` 和同步 `AgentCliInvoker` 不作为 Harness Runtime 端口。M3 只复用拆分后的进程树、Watchdog、输出上限和原始 Codex JSONL 解码等 Infrastructure 技术组件。

M0 Spike 不再选择 Runtime，而是验证当前 Codex CLI 是否满足以下条件：

- 能否按单次 Run 使用隔离 MCP 配置；
- 能否明确请求 Skill；
- 能否限制工具或在限制不足时安全不挂载；
- 是否有稳定结构化事件；
- 取消和超时是否可控。

### 4.10 各阶段首版行为

#### ANALYSIS

- 只读检索代码与经授权 MCP；
- 生成需求、验收条件、影响分析和问题清单；
- 遇到阻断性问题进入 `WAITING_INPUT`；
- 确定性 Gate 通过后等待 ADMIN 批准。

#### DESIGN

- 仅消费批准的 Requirement Artifact；
- 强制输出分层落点、领域规则、变更清单和测试策略；
- 需求编号必须映射到设计和测试；
- 确定性 Gate 通过后等待 ADMIN 批准。

#### IMPLEMENTATION

- 修改前记录 Git 基线；
- Prompt 明确当前项目 DDD/TDD 规则，但不替代 CLI 自身加载的仓库规则；
- 业务分支遵循先红、最小实现、重构保绿；
- 命令执行仅允许逻辑命令白名单及参数策略；
- 保存测试红/绿退出码、输出 Artifact 和最终 Diff；
- 仅运行匹配风险的最小测试，不默认执行打包和部署。

#### DEPLOYMENT

- 只允许 `local` 环境；
- 开始前确认批准的 Git 基线没有变化；
- 构建、启停和部署命令来自管理员注册模板，不接受 Agent 任意 Shell；
- 部署动作需要独立 Approval；
- 验证技术健康与业务 AC；
- 首版自动回滚可以不实现，但必须保存回滚命令模板，并在失败时停止且提示人工回滚；
- 不允许生产部署。

## 5. 首版最小数据模型

建议首版只引入必要表，避免提前实现目标态全部投影：

```text
harness_definition
harness_run
harness_stage_execution
harness_stage_attempt
harness_capability_snapshot
harness_artifact
harness_gate_result
harness_approval
harness_runtime_execution
harness_runtime_event
harness_event
harness_trace_link
```

Prompt Pack、Skill 和 MCP 注册第一版可使用只读配置文件 Catalog；运行和审计数据必须落 SQLite。后续需要管理台在线编辑时再迁移到注册表表结构。

首版写侧 Repository 接口放 Domain，签名只暴露领域类型。Run 列表、详情、时间线和报告使用 QueryService 投影。

HarnessRun 聚合采用增量持久化：已有 Stage/Attempt 按主键更新，新历史追加插入，禁止通过删除并重插 Attempt 保存聚合。Snapshot、RuntimeExecution 和 RuntimeEvent 可以外键引用 Attempt，但只有删除整个 Run 时才允许级联清理。

Capability Snapshot 新增字段和 Runtime 表必须提供幂等老库迁移；不能只依赖 `CREATE TABLE IF NOT EXISTS`。迁移不得重算或覆盖旧 Snapshot Hash。

## 6. 首版配置草案

以下仅表达配置意图，最终字段名由实现设计和 `@ConfigurationProperties` 测试确定：

```yaml
agent:
  harness:
    enabled: false
    artifact-root: /var/lib/agent-web/harness
    prompt-pack-roots:
      - classpath:/harness/prompt-packs
      - /etc/agent-web/harness/prompt-packs
    skill-roots:
      - /etc/agent-web/harness/skills
    mcp-registry: /etc/agent-web/harness/mcp-registry.yml
    policy: /etc/agent-web/harness/policy.yml
    runtime:
      codex-command: codex
      auth-mode: local-login
      provider-credential-reference: ""
      supported-codex-versions: ["0.145.0"]
      version-probe-timeout-seconds: 5
      version-probe-max-bytes: 4096
    allowed-environments:
      - local
```

配置要求：

- 默认关闭；
- 路径必须规范化并通过白名单；
- Secret 只通过环境变量、Secret Store 或现有安全引用提供；
- `auth-mode` 默认 `local-login`，由系统 `codex` 使用服务账户的本机登录态；Adapter 不直接读取、复制或修改认证文件；
- `isolated-key` 使用临时 `HOME/CODEX_HOME/XDG_CONFIG_HOME`，并要求 `provider-credential-reference` 保存合法环境变量逻辑名；Runtime 启动期解析值并只注入单次隔离进程；
- 两种模式不静默回退；本机受控试点可使用 `local-login`，生产多用户部署优先评估 `isolated-key`；
- 不在 `application.yml` 新增硬编码凭据；
- `supported-codex-versions` 只能列入已更新兼容矩阵并通过契约测试的版本，不能作为绕过未验证版本门禁的临时开关；
- 配置解析失败时 Harness 不可用，但不影响现有非 Harness 入口启动，除非显式配置为 fail-fast。
- `@ConfigurationProperties` 位于 `config/harness`，按 Catalog、Runtime、Security 责任拆分；Application 不依赖具体 Properties。

## 7. 首版 API 草案

最小用例：

```text
POST /api/harness/runs
GET  /api/harness/runs
GET  /api/harness/runs/{runId}
POST /api/harness/runs/{runId}/stages/{stage}/start
POST /api/harness/runs/{runId}/stages/{stage}/capability-snapshot
POST /api/harness/runs/{runId}/stages/{stage}/executions
GET  /api/harness/runs/{runId}/stages/{stage}/attempts/{attempt}/execution
POST /api/harness/runs/{runId}/questions/{questionId}/answer
POST /api/harness/runs/{runId}/approvals
POST /api/harness/runs/{runId}/retry
POST /api/harness/runs/{runId}/cancel
GET  /api/harness/runs/{runId}/events
GET  /api/harness/runs/{runId}/artifacts/{artifactId}
GET  /api/harness/runs/{runId}/report
```

要求：

- 所有写操作校验 ADMIN；
- 创建 Run、开始阶段、Approval 和部署动作使用幂等键；
- Controller 只做参数和 DTO 转换；
- 非法状态转换由 Domain 拒绝，并映射明确 HTTP 状态；
- Artifact 下载执行内容类型、大小和路径校验；
- SSE 断线不影响后台 Stage 执行。
- RuntimeExecution 查询不返回 Secret 值、临时配置物理路径、完整进程环境或未脱敏原始日志。

## 8. 首版安全边界

首版必须具备：

- 工作空间真实路径白名单；
- Prompt/Skill/MCP 注册根白名单；
- 能力默认拒绝和阶段权限求交；
- Skill Package Hash；
- MCP 配置 Hash 和 Secret 引用；
- Harness 专用临时 `HOME/CODEX_HOME/XDG_CONFIG_HOME`，不读取或复制用户级 CLI 认证目录；
- Codex 工作目录祖先 `.codex/config.toml` 默认阻断，Repo Skill 先做可信 Catalog/Hash 校验再通过 `skills.config` 禁用自动加载；
- 只接受兼容清单内的实际 CLI 版本；探测失败、超时、输出异常或版本未知均 fail-closed；
- Secret 明文只在 Infrastructure Runtime Adapter 启动期解析，不返回 Domain/Application；
- 命令模板白名单，禁止直接执行 Agent 返回的任意 Shell；
- 本机环境白名单；
- 部署动作独立 Approval；
- 日志、Artifact、API 的 Secret Redaction；
- 最大并发、超时和输出大小限制；
- 每个状态改变、授权和命令执行的审计事件；
- 成功、启动失败、运行失败、超时和取消后的临时配置幂等清理。

首版明确不宣称具备：

- 对所有 CLI 工具调用的细粒度强制控制；
- 多租户强隔离容器；
- 生产级 Secret Broker；
- 生产自动发布安全认证。

这些限制必须在 UI 和部署说明中可见。

## 9. 首版测试范围

### 9.1 Domain 单测

- Run/Stage 合法和非法流转；
- 上游 Artifact 修订导致下游失效；
- Approval 绑定 Hash；
- Retry 创建新 Attempt；
- 终态不能继续；
- Capability Snapshot 不可变；
- MCP 信任、Stage、READ-only、Grant 和 Runtime Enforcement 求交；
- RuntimeExecution 合法/非法状态转换、事件幂等和取消中间态；
- Attempt、Snapshot、Execution 绑定不变量。

### 9.2 Application 单测

- 创建 Run 和阶段编排；
- 能力解析后在同一事务持久化 Snapshot 和 PREPARED Execution；
- 外部 Runtime 只在事务提交后启动；
- Artifact → Gate → Approval 的顺序；
- Runtime 失败、超时、取消和重启对账；
- 部署动作没有 Approval 时不调用 Gateway。

### 9.3 Infrastructure 测试

- 真实 SQLite Repository、老库幂等迁移；
- Run 更新 Artifact/Gate/Approval/取消后 Snapshot、RuntimeExecution 和 Attempt 两个引用不被级联删除；
- `@TempDir` Prompt Pack/Skill Catalog 和 Artifact Store；
- 路径逃逸、符号链接、重复 ID 和 Hash 变化；
- CLI Stub 直接断言 Prompt/Skill/MCP 的单次 `-c` 转换、版本探测、Repo Skill 禁用和实际 PID Handle；
- MCP Stub 或固定 Fixture 的 Preflight；
- Secret Redaction 和命令模板参数校验。

### 9.4 Interface 与前端

- 管理 API 鉴权、参数、状态码和幂等；
- Vitest 覆盖前端状态展示和纯转换逻辑；
- Playwright 覆盖一次完整四阶段 Happy Path 和一次 Gate 失败重试；
- 默认测试不调用真实 CLI、MCP、数据库服务或部署环境。

### 9.5 手工/Live 验证

- 选择的首个真实 CLI 能读取阶段 Prompt 和指定 Skill；
- 只读 MCP 可被当前 Stage 使用，禁止阶段不挂载；
- 服务重启后 Run 状态与 Artifact 保留；
- 本机部署命令执行后技术和业务验证通过；
- Feature Flag 关闭后现有业务回归正常；
- 取消意图先于进程终止落库，成功/失败/超时/取消后临时配置均清理。

## 10. 首版明确不做

- 自定义 Harness Definition 编辑器和任意 DAG；
- 多 Agent 并行分析、实现和审查；
- 自动 Commit、Push、PR 和 Merge；
- 生产部署、自动回滚和外部发布系统对接；
- MCP 在线安装、OAuth、多租户凭据代理和 Java 进程内 MCP Runtime；
- Skill Marketplace、在线安装、自动升级和复杂依赖求解；
- 基于大模型评分决定 Gate；
- 自动解决需求歧义；
- 全量 CI/CD 替代；
- 对现有 Workflow 数据和页面做无关重构。

## 11. MVP Definition of Done

只有以下条件全部满足，首版才算完成：

- [x] Harness Feature Flag 默认关闭，开启后可创建 Run。
- [x] 四阶段领域状态机和 Repository 通过测试。
- [x] 四个 Prompt Pack 可热发现、版本化、快照和计算 Hash。
- [x] 至少两个阶段能动态选择不同 Skill，并记录选择原因和 Package Hash。
- [x] 至少一个只读 MCP 在允许阶段可用，在禁止阶段不挂载。（Catalog/Stub/协议 Fixture 与真实 `codex-cli 0.145.0` + 本地确定性 Provider 均已通过）
- [ ] 首个 CLI Runtime 完成真实纵向验证，默认测试使用 Stub。（Stub 与 `local-login` 在线 Prompt/Skill 已通过；一个真实需求的四阶段纵向交付尚未完成）
- [x] Snapshot 和 PREPARED RuntimeExecution 提交后才启动外部进程。
- [x] Artifact、Gate、Approval、取消等 Run 更新不会删除已固化 Snapshot/Execution。
- [x] 活动 Runtime 取消经过持久化意图和 `CANCELLING`，退出码不能覆盖取消语义。
- [x] 四阶段必需 Artifact、Gate、Approval 和失效传播可工作。
- [x] 实现阶段保存 Git 基线、Diff、聚焦测试红/绿证据和追踪矩阵。
- [ ] 本机环境完成一次经批准的部署与业务验收。（受控 E2E local 已通过，真实需求尚未执行）
- [x] 应用重启后可继续未完成 Run，且不会重复执行部署动作。
- [x] 管理 API 和最小管理页面可完成完整流程。
- [x] 安全检查证明不能通过需求/Skill 文本扩大文件、命令、MCP 或环境权限。
- [x] 相关 Domain、Application、Infrastructure、Interface 和 Playwright 测试通过。
- [x] README/运维文档说明开启、配置、限制、恢复和关闭方式。
- [ ] 四阶段功能完成后，一个真实需求的最终报告可以追踪需求到部署结果。

## 12. 首版后续演进接口

首版必须预留但不实现：

- 第二个 CLI Runtime Adapter；
- MCP In-process Adapter；
- 外部需求/代码托管/部署平台对接；
- Definition 在线编辑和版本发布；
- 多角色/多 Agent 协作；
- 自动回滚；
- 生产 Approval Policy；
- Artifact 导出、保留和合规清理；
- 更细粒度 Tool 调用审计。

预留的含义是稳定端口和领域概念不阻塞演进，不是提前创建空接口和无实现抽象。只有首版纵向切片出现第二个真实变化点时才抽象。
