# 本地开发工作台 MVP 设计方案

> 状态：Proposed v1.0
> 日期：2026-08-01
> 适用范围：`agent-web` B/S 版本、本机 Agent Runtime、`workspace` 开发能力接入
> @author alex

## 0. 设计结论

MVP 新建一个轻量的“本地开发工作台”，不继续在现有 Harness 页面上叠加或删减功能。新工作台稳定并
完成必要能力迁移后，现有 Harness 模块将按本方案的退役计划整体移除。工作台以阶段化 Agent 对话为
中心，固定提供四个全尺寸阶段页面：

1. 需求分析；
2. 技术方案设计；
3. 开发、部署与自动化测试；
4. 重构与测试。

每个阶段只提供三类可人工调整的 Agent 能力配置：

- **Rules**：本阶段注入 Agent 的规则和约束；
- **Skills**：本阶段允许使用的 Skill；
- **MCP**：本阶段允许连接的 MCP Server。

主页面由全尺寸对话区、可伸缩的右侧只读文档查看区和独立的能力配置抽屉组成。阶段推进、回退、
交接摘要和能力选择均由用户手动控制。MVP 不对 Agent 生成的需求、方案、代码总结或测试结论执行
Artifact Schema、确定性 Gate 或内容质量校验。

“不校验生成内容”不取消本地执行安全校验。路径白名单、Capability Catalog、MCP 授权、命令来源、
进程超时、输出上限、停止恢复和敏感信息脱敏仍然必须保留。

## 1. 背景与现状

现有 Harness 已覆盖 Stage、Attempt、Artifact、Gate、Approval、Capability Snapshot、Runtime、Deployment
和审计时间线，适合受控交付场景，但作为日常本地开发界面信息密度过高。用户主要活动是与 Agent
持续对话、观察运行过程、查看文件和调整阶段能力，不需要在主界面操作完整的交付审计模型。

`agent-web` 已有以下可复用能力：

- `frontend/js/components/chat-panel.vue`：消息区、输入区和会话交互；
- `frontend/js/composables/useResumableRun.ts`：后台 Run、SSE 重连、刷新恢复和停止；
- `MessageItem.vue` / `ToolBlock.vue`：Agent 文本和工具调用展示；
- ChatSession / ChatRun：会话持久化和与浏览器连接解耦的运行模型；
- 文件系统白名单、目录列表和文件下载；
- 当前位于 Harness 内的 Prompt、Skill、MCP Catalog 与 Runtime Adapter。

本方案复用上述基础设施，但不复用 `HarnessRun` 的四阶段状态机作为新工作台聚合。可复用的 Runtime、
Skill Catalog 和 MCP Catalog 必须先迁移到中性的公共边界，新工作台禁止直接依赖 `domain.harness`、
`app.harness` 或 `infra.harness`。现有 Harness 页面和数据只在迁移窗口内保留，工作台完成替代验收后
停止新建 Harness Run，并按 §20 的计划退役和删除。

## 2. 目标与非目标

### 2.1 目标

- 对话区成为页面主视觉，体验与现有普通对话页面一致；
- Agent 运行期间持续展示可观察的执行过程；
- 每个阶段独立配置 Rules、Skills、MCP，并允许用户随时调整；
- 四个阶段拥有独立会话，避免能力变化和历史上下文互相污染；
- 用户通过可编辑交接摘要，把必要上下文传递到下一阶段；
- 点击 Agent 输出中的文件路径即可在右侧查看文件内容；
- 文档查看区支持拖动伸缩、收起、恢复和最大化；
- 复用 `workspace` 的规则、Skills、工具和端内开发流程，不在 Web 层复制具体 Java、前端或部署逻辑；
- B/S 架构稳定后，可由本地客户端外壳直接承载，不重写后端能力。

### 2.2 非目标

MVP 明确不实现：

- Agent 生成内容的 Schema 校验、质量评分或确定性 Gate；
- Artifact、Approval、Attempt 失效传播和审计工作台；
- 自动判断阶段是否完成或自动进入下一阶段；
- 文档在线编辑、保存冲突和多人协同；
- 多文件并排比较、完整 IDE、终端模拟器或调试器；
- 自动 commit、push、部署或生产环境写入；
- 桌面客户端封装；
- 将新工作台状态写入或伪装成正式 `requirement-flow` 交付状态。

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| Workbench | 围绕一个本地研发目标建立的工作台 |
| Phase | 四个固定开发阶段之一 |
| Phase Session | 某阶段独立的 Agent 会话 |
| Rules | 注入本阶段 Agent Prompt 的人工可编辑规则，不是生成内容校验器 |
| Capability Draft | 当前阶段下一轮运行将采用的 Rules、Skills、MCP 配置 |
| Capability Snapshot | 单次 Agent Run 启动时冻结的不可变能力配置 |
| Handoff Summary | 用户人工编辑并传递给下一阶段的阶段结论 |
| Document Viewer | 页面右侧的只读文件内容查看区 |
| Observable Process | Agent 文本、工具调用、命令输出、文件变化和运行状态，不包含模型私有思维链 |

## 4. 产品信息架构

### 4.1 页面结构

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ 工作空间 / 工作台标题   [需求分析][技术方案][开发部署测试][重构测试]  [⚙] │
├───────────────────────────────────────────────┬─┬────────────────────────┤
│                                               │ │ 文档路径      刷新 收起 │
│                                               │ │                        │
│              全尺寸阶段对话区                  │拖│      文档内容查看区      │
│                                               │动│                        │
│  用户消息                                      │条│ Markdown / 代码 / 文本  │
│  Agent 流式回复                                │ │                        │
│  工具调用、命令输出、文件变化、测试进度          │ │                        │
│                                               │ │                        │
├───────────────────────────────────────────────┤ │                        │
│ 输入框                              [停止][发送]│ │                        │
└───────────────────────────────────────────────┴─┴────────────────────────┘

点击右上角 [⚙]：打开 Capability Drawer
┌──────────────────────────────┐
│ Rules                        │
│ Skills                       │
│ MCP                          │
│                    [保存配置] │
└──────────────────────────────┘
```

### 4.2 页面区域职责

| 区域 | 职责 | 是否常驻 |
| --- | --- | --- |
| 顶部上下文栏 | 工作空间、标题、阶段切换、当前运行状态、能力配置入口 | 是 |
| 阶段对话区 | 消息、Agent 输出、工具过程、输入和停止 | 是 |
| 文档查看区 | 只读查看 Agent 引用或用户选择的工作区文件 | 可收起 |
| 能力配置抽屉 | 编辑当前阶段 Rules，选择 Skills 和 MCP | 默认关闭 |
| 交接摘要 | 编辑当前阶段传给下游的摘要并人工标记完成 | 按需打开 |

### 4.3 响应式布局

- 桌面宽度不小于 `1200px` 时使用左右 Split Pane；
- 文档查看区默认宽度为可用内容区的 `35%`；
- 文档查看区最小宽度 `320px`，最大宽度为内容区的 `60%`；
- 对话区最小宽度 `560px`，不足时优先收起文档查看区；
- 分隔条支持拖动，双击恢复默认宽度；
- 文档区支持收起、恢复和最大化；
- 页面记住用户上次宽度与展开状态；
- 窄屏下文档查看器改为全屏 Drawer，不与对话区并排。

能力配置使用独立 Drawer。Drawer 打开时覆盖右侧区域，关闭后恢复原文档和滚动位置，避免配置表单
长期挤占对话空间。

## 5. 四阶段设计

### 5.1 阶段定义

| 阶段 | 目标 | 典型对话 | 典型 Workspace 能力 |
| --- | --- | --- | --- |
| 需求分析 | 澄清目标、范围、验收口径和影响面 | 需求澄清、代码事实核实、风险识别 | 只读规则、需求分析 Skill、代码检索 MCP |
| 技术方案设计 | 形成业务流程、代码方案、测试与部署思路 | 方案比较、接口契约、任务拆分 | 设计规则、架构/服务导航 Skill、只读 MCP |
| 开发部署测试 | 实现代码、运行测试、执行受控部署与自动化验证 | TDD、编译、测试、部署、失败修复 | 开发规则、TDD/测试/部署 Skill、授权 MCP |
| 重构测试 | 改善结构、减少复杂度并完成回归 | 重构建议、Diff Review、回归测试 | 重构规则、重构/测试 Skill、代码与测试 MCP |

表中的能力只是阶段默认建议，不是硬编码。用户可以保存阶段默认值，也可以针对当前 Workbench 覆盖。

### 5.2 阶段状态

MVP 只保留轻量状态：

```text
NOT_STARTED → ACTIVE → MANUALLY_COMPLETED
                    ↘ ACTIVE（用户重新打开）
```

- 第一次发送消息时，阶段从 `NOT_STARTED` 进入 `ACTIVE`；
- 用户点击“完成当前阶段”后进入 `MANUALLY_COMPLETED`；
- 用户可以任意切换阶段，也可以重新打开已完成阶段；
- 系统不检查前置阶段是否完成，不阻止跳转；
- 上游阶段重新打开后，不自动使下游失效，仅提示用户确认是否更新交接摘要。

### 5.3 阶段会话隔离

每个阶段绑定一个独立 ChatSession：

```text
Workbench
├─ REQUIREMENT_ANALYSIS    → ChatSession A
├─ SOLUTION_DESIGN         → ChatSession B
├─ DEVELOPMENT_DELIVERY    → ChatSession C
└─ REFACTORING_TEST        → ChatSession D
```

独立会话解决以下问题：

- 阶段 Rules、Skills、MCP 可以独立变化；
- 开发阶段的大量命令输出不会污染需求分析上下文；
- 用户可以单独清空或重新开始某阶段；
- 每个阶段的历史和运行恢复边界清晰。

## 6. Rules、Skills 与 MCP

### 6.1 Rules

Rules 是当前阶段的 Prompt 约束文本。MVP 提供：

- 多行文本编辑；
- 阶段默认模板；
- 保存当前覆盖；
- 恢复阶段默认；
- 从其他阶段复制；
- 显示字符数和最后更新时间。

Rules 不执行语法或内容质量校验，只做空值、长度上限和存储编码等技术校验。平台级安全规则不展示为
可删除文本，仍由 Runtime 在用户 Rules 之外强制执行。

### 6.2 Skills

Skills 从平台与 workspace Catalog 读取，页面使用可搜索多选列表展示：

- Skill 名称、说明、版本和来源；
- 当前是否可用；
- 选择或取消选择；
- 查看 Skill 摘要；
- 恢复阶段默认选择。

页面不允许输入任意 Skill 路径。工作区 Skill 是否可信和是否可加载，继续由 Catalog 与路径边界决定。

### 6.3 MCP

MCP 从管理员或本地可信 Catalog 读取，页面展示：

- Server ID、说明和能力摘要；
- 连接可用状态；
- 只读/写能力标识；
- 是否需要凭据，但不展示凭据值；
- 当前阶段是否允许选择。

MVP 只允许选择已登记 MCP，不允许用户或 Agent 在页面提交 command、环境变量、Secret 或文件根来创建
新 Server。后续如需本地自由配置，应进入独立的管理员设置页面。

### 6.4 配置生效时机

能力配置采用 Draft + Snapshot：

```text
用户编辑 Capability Draft
→ 点击保存
→ 下一次发送消息
→ 服务端冻结 Capability Snapshot
→ 本轮 Agent Run 始终使用该 Snapshot
```

Agent 正在运行时仍可编辑 Draft，但页面必须明确提示“修改将在下一轮生效”。运行中的 Snapshot 不可变，
避免一次执行中途更换 Skill 或 MCP。

## 7. 对话与 Agent 运行

### 7.1 对话体验

阶段对话区复用普通对话页面的视觉和操作模型：

- 用户消息右侧显示，Agent 消息左侧显示；
- Markdown、代码块、图片和附件按现有 Chat 方式渲染；
- 输入框常驻底部，支持多行输入；
- 运行中发送按钮切换为停止按钮；
- 刷新页面后恢复正在运行的 Run；
- SSE 中断时自动重连并从最后事件继续；
- 用户主动向上滚动阅读时，不强制跳回底部；
- 新事件到达时显示“有新输出”提示。

### 7.2 可观察运行过程

页面展示实际发生的运行事件，不展示模型私有思维链。建议统一以下事件类型：

| 事件 | 页面表现 |
| --- | --- |
| `TEXT_DELTA` | 追加 Agent 流式文本 |
| `TOOL_STARTED` | 创建可折叠工具卡片，显示工具名称和脱敏参数摘要 |
| `TOOL_OUTPUT` | 追加受输出上限约束的 stdout/stderr 或工具结果 |
| `TOOL_FINISHED` | 显示成功、失败、耗时和退出码 |
| `FILE_CHANGED` | 显示新增、修改、删除文件，并允许点击路径打开文档查看器 |
| `TEST_PROGRESS` | 显示测试套件、进度和当前结果 |
| `RUN_STATUS` | 显示 PREPARED、RUNNING、SUCCEEDED、FAILED、CANCELLED 等状态 |
| `ERROR` | 显示可理解的失败原因和重试建议 |

工具输出默认折叠，用户可以展开查看。命令行参数、输出和文件路径必须先经过现有脱敏与工作目录边界处理。

### 7.3 运行控制

- 同一阶段同一时刻只允许一个活动 Run；
- 不同阶段默认也不并行运行，MVP 以 Workbench 级单运行约束降低工作区并发修改风险；
- 停止操作只发送取消请求，页面持续等待进程进入终态；
- 浏览器关闭不取消后台 Run；
- 服务重启后的未知进程沿用现有恢复语义，标记为明确的丢失或失败状态，不自动重放写操作。

## 8. 阶段交接上下文

### 8.1 交接内容

阶段之间不复制完整聊天记录。新阶段首次运行时组装：

1. Workbench 原始目标；
2. 上一阶段人工维护的 Handoff Summary；
3. 用户显式加入上下文的文件引用；
4. 当前阶段 Rules；
5. 当前阶段 Skill 与 MCP Snapshot；
6. 受控 workspace 上下文。

### 8.2 人工调整

每个阶段提供“交接摘要”编辑入口，支持：

- 根据当前对话生成一份候选摘要；
- 用户自由编辑候选摘要；
- 保存摘要并手动完成阶段；
- 进入下一阶段前预览最终注入内容。

MVP 不校验摘要是否完整、是否满足固定格式，也不自动从 Agent 回复中提取“正式结论”。自动生成只是一种
编辑辅助，最终内容以用户保存版本为准。

## 9. 右侧文档查看器

### 9.1 打开入口

用户可以通过以下方式打开文档：

- 点击 Agent 回复中的工作区文件路径；
- 点击工具卡片或 `FILE_CHANGED` 事件中的文件；
- 点击测试、构建或部署结果引用的报告文件；
- 点击文档区顶部“选择文件”，使用受控目录浏览器选择；
- 从当前 Workbench 的最近文档列表重新打开。

所有路径由服务端在工作区允许根内解析。前端不能仅凭字符串判断路径合法性。

### 9.2 MVP 展示能力

| 类型 | 展示方式 |
| --- | --- |
| Markdown | 预览/源码切换，预览内容经过 DOMPurify |
| Java、Vue、JS、TS、Python、SQL | 只读代码视图、行号、语法高亮 |
| JSON、YAML、XML | 只读代码视图，可选格式化展示 |
| 普通文本、日志、测试报告 | 等宽文本，保留换行 |
| 图片 | 复用现有受控图片接口 |
| 其他二进制 | 不解析，显示元信息和下载入口 |

MVP 同一时刻只显示一个活动文档，顶部保留最近文档下拉列表，不实现多 Tab 和并排 Diff。

### 9.3 文件变化处理

文档响应返回 `lastModified`、`size` 和内容摘要标识。当前打开文件被 Agent 修改时：

1. `FILE_CHANGED` 事件把文档标记为“已有更新”；
2. 页面不立即替换正文，避免滚动位置跳变；
3. 用户点击“刷新”后读取新版本；
4. Agent Run 终态时再次检查并提示；
5. 文件删除时保留已加载内容并显示“源文件已删除”，不伪装为仍存在。

### 9.4 伸缩与状态保存

- 拖动 Splitter 实时改变宽度；
- 收起只隐藏视图，不丢失当前文档；
- 恢复后保持原路径和滚动位置；
- 最大化文档区时隐藏对话正文，但保留顶部阶段栏；
- 布局状态保存到浏览器本地存储；
- 最近文档与当前文档按 `workbenchId + phase` 隔离；
- 服务端不持久化纯视觉布局状态。

### 9.5 文件读取限制

- 新增只读文本内容接口，不复用带下载语义的响应作为主读取入口；
- 路径必须经过真实路径解析、允许根校验和符号链接逃逸校验；
- 默认文本文件大小上限建议为 `2 MiB`，配置化且服务端强制；
- 超限文件只返回元信息和下载入口，不把完整正文送入浏览器；
- 使用 UTF-8 优先检测，无法安全解码时按二进制处理；
- 响应不包含未授权绝对根、凭据或额外目录信息；
- Markdown 渲染继续使用 `marked + DOMPurify`，禁止原始 HTML 绕过净化。

## 10. 与 workspace 开发流程的关系

### 10.1 MVP 接入边界

MVP 复用 workspace 的开发能力，不复制 workspace 的具体工程流程：

- 阶段默认 Rules 可以引用 workspace 的端入口与开发规范；
- Skills 从 workspace/平台 Catalog 发现并选择；
- MCP 通过受控 Catalog 挂载；
- 开发阶段由选择的 Skill 执行 Java TDD、前端开发、测试、部署或验证；
- 具体命令、环境 Provider 和工程规则仍由 `workspace/toolkits/<target>/` 负责；
- agent-web 只负责选择、启动、展示和停止，不在 Controller 或前端硬编码端内命令。

### 10.2 与正式 requirement-flow 的区别

新工作台 MVP 是本地交互工作区，不等同于正式交付控制面：

| 本地工作台 MVP | 正式 requirement-flow |
| --- | --- |
| 人工切换阶段 | 控制器按受控事实投影进度 |
| 不校验生成内容 | Gate、receipt 和 delivery evidence 受控校验 |
| 阶段会话保存在 agent-web | 交付状态保存在 `requirement-flow-runs/<id>` |
| 适合探索、设计、本地开发 | 适合需要 push、部署、验证和正式收口的需求 |

MVP 不写 `flow.json`，也不把人工完成状态映射为正式 PASS。后续可以增加“绑定正式交付 Run”能力，
但正式状态必须继续以 workspace 控制器和机器证据为准。

### 10.3 高影响操作授权

代码修改、commit、push、部署和生产写入是不同权限层级：

- Agent 获得 workspace-write 后可以在允许仓库内修改代码；
- commit 需要用户明确触发；
- push 需要单独授权并使用 workspace 受控入口；
- 部署需要用户选择环境并单独授权；
- 生产写操作不在 MVP 自动化范围内。

阶段名称包含“部署”不代表 Agent 自动获得部署权限。

## 11. 技术架构

```text
Vue Dev Workbench
├─ Phase Chat
├─ Resizable Document Viewer
└─ Capability Drawer
             │ REST + resumable SSE
             ▼
agent-web Interface
├─ DevWorkbenchController
├─ DevWorkbenchRunController
├─ DevCapabilityController
└─ FsContentController
             ▼
Application
├─ WorkbenchAppService
├─ PhaseConversationService
├─ CapabilitySnapshotService
└─ DocumentContentQueryService
             ▼
Domain / Ports
├─ DevWorkbench
├─ WorkbenchPhase
├─ PhaseCapabilityDraft
├─ PhaseRunSnapshot
├─ ChatSession / ChatRun port
├─ Skill/MCP Catalog port
└─ WorkspaceContent port
             ▼
Infrastructure
├─ SQLite Workbench Repository
├─ existing ChatRun + SSE
├─ Codex/Claude Runtime Adapter
├─ workspace Skill/MCP Catalog Adapter
└─ allowlisted File Content Adapter
```

Application 只编排 Repository、Chat、Catalog、Runtime 和文件查询。阶段状态、能力版本和同一 Workbench
单活动 Run 等业务规则由 Domain 守护。

## 12. 领域模型与存储

### 12.1 聚合模型

```text
DevWorkbench
├─ workbenchId
├─ title
├─ originalGoal
├─ workingDir
├─ agentType
├─ createdBy / createdAt / updatedAt
└─ phases
   └─ WorkbenchPhase
      ├─ phase
      ├─ status
      ├─ chatSessionId
      ├─ capabilityDraft
      ├─ capabilityVersion
      ├─ handoffSummary
      └─ lastRunId
```

`DevWorkbench` 守护：

- 四个阶段必须且只能各存在一个；
- Phase 枚举固定；
- 工作目录创建后不在同一个 Workbench 内静默变更；
- 同一个 Workbench 同一时刻只有一个活动 Run；
- 人工完成的阶段可以重新打开；
- Capability Draft 每次保存递增版本；
- Run Snapshot 必须绑定启动时的 Capability Version。

### 12.2 SQLite 建议

MVP 可新增两张主表，消息继续复用现有 Chat 表：

```text
dev_workbench
├─ id
├─ title
├─ original_goal
├─ working_dir
├─ agent_type
├─ active_run_id
├─ created_by
├─ created_at
├─ updated_at
└─ version

dev_workbench_phase
├─ workbench_id
├─ phase
├─ status
├─ chat_session_id
├─ rules_text
├─ skill_ids_json
├─ mcp_server_ids_json
├─ capability_version
├─ handoff_summary
├─ last_run_id
├─ updated_at
└─ PRIMARY KEY(workbench_id, phase)
```

单次运行采用的不可变 Snapshot 可以写入现有 Runtime Snapshot 存储；如果无法直接复用，再增加
`dev_phase_run_snapshot`，只保存逻辑 ID、版本、Hash 和 Catalog 引用，不复制 Secret。

## 13. API 草案

### 13.1 Workbench

```text
POST   /api/dev-workbenches
GET    /api/dev-workbenches
GET    /api/dev-workbenches/{workbenchId}
PATCH  /api/dev-workbenches/{workbenchId}
```

创建请求至少包含 `title`、`originalGoal`、`workingDir` 和 `agentType`。服务端解析真实目录并校验允许根。

### 13.2 Phase

```text
GET    /api/dev-workbenches/{workbenchId}/phases/{phase}
PUT    /api/dev-workbenches/{workbenchId}/phases/{phase}/capabilities
PUT    /api/dev-workbenches/{workbenchId}/phases/{phase}/handoff
POST   /api/dev-workbenches/{workbenchId}/phases/{phase}/complete
POST   /api/dev-workbenches/{workbenchId}/phases/{phase}/reopen
```

保存 Capability Draft 时携带 `expectedVersion`，并发覆盖返回 `409`，避免多个浏览器标签静默覆盖配置。

### 13.3 Conversation Run

```text
POST   /api/dev-workbenches/{workbenchId}/phases/{phase}/runs
GET    /api/dev-workbench-runs/{runId}/events
POST   /api/dev-workbench-runs/{runId}/stop
```

启动请求包含用户消息和 `expectedCapabilityVersion`。服务端冻结 Snapshot、创建或复用该阶段 ChatSession，
再提交后台 Run。事件流复用 ChatRun 的 Idempotency-Key、Last-Event-ID、回放和停止语义。

### 13.4 Capability Catalog

```text
GET    /api/dev-workbench-capabilities?workingDir={path}&phase={phase}
```

返回当前用户、工作目录和阶段允许看到的 Skill/MCP 列表及阶段默认值，不返回 Secret 或原始 MCP command。

### 13.5 文件内容

```text
GET    /api/fs/content?path={absoluteOrAllowedPath}
```

文本响应建议使用结构化 JSON：

```json
{
  "path": "D:/workspace/service/README.md",
  "name": "README.md",
  "contentType": "text/markdown",
  "size": 4096,
  "lastModified": "2026-08-01T10:00:00Z",
  "etag": "sha256-or-stable-metadata-hash",
  "content": "# Service"
}
```

二进制或超限文件不返回 `content`，只返回元信息、原因和既有下载地址。

## 14. 前端组件设计

```text
frontend/js/workbench/
├─ pages/DevWorkbench.vue
├─ components/WorkbenchHeader.vue
├─ components/PhaseTabs.vue
├─ components/PhaseChatPanel.vue
├─ components/ResizableSplitPane.vue
├─ components/DocumentViewer.vue
├─ components/DocumentToolbar.vue
├─ components/CapabilityDrawer.vue
├─ components/RulesEditor.vue
├─ components/SkillSelector.vue
├─ components/McpSelector.vue
├─ components/HandoffEditor.vue
├─ composables/useDevWorkbench.ts
├─ composables/usePhaseRun.ts
├─ composables/useCapabilityDraft.ts
├─ composables/useDocumentViewer.ts
└─ lib/workbench-state.ts
```

关键复用边界：

- 消息渲染、输入、工具卡片复用现有 Chat 组件；
- SSE 连接与恢复逻辑从 `useResumableRun` 提取通用能力，不复制第二套重连状态机；
- Workbench 只增加阶段、能力 Snapshot 和文档查看上下文；
- 不把现有 800 行以上 Harness 页面继续拆成新的工作台入口；
- 新工作台前端不 import Harness 页面、Composable 或 Harness 专用 API；
- Split Pane 的宽度计算、边界和持久化抽成纯函数，便于 Vitest 覆盖。

## 15. 安全与运行边界

### 15.1 文件安全

- 工作目录和文件内容统一受 `agent.fs.roots` 约束；
- 使用真实路径校验，拒绝 `..`、符号链接和 Junction 逃逸；
- 文件接口只返回已授权路径内容；
- Markdown 和 Agent 输出统一净化；
- 大文件、未知编码和二进制内容 fail-closed。

### 15.2 Runtime 安全

- Rules、Skill 或 Agent 输出不能新增命令、环境变量、Secret、MCP Server 或文件根；
- Runtime 命令由受控 Adapter 和 Catalog 组装；
- 子进程继承最小环境变量集合；
- 设置 idle timeout、绝对运行上限和输出上限；
- 停止操作终止进程组；
- 日志和 SSE 事件不输出 Token、密码或本机认证文件内容。

### 15.3 能力安全

- 用户只选择当前 Catalog 可见项；
- Capability Snapshot 记录 ID、版本和 Hash，不记录 Secret；
- MCP 写能力必须显式标识；
- 后续若支持非管理员用户，应按用户与工作目录过滤 Capability Catalog；
- 用户 Rules 不能覆盖平台强制安全规则。

## 16. MVP 范围

### 16.1 必须完成

- Workbench 创建、列表、恢复；
- 四个固定阶段和人工切换；
- 每阶段独立 ChatSession；
- 每阶段 Rules、Skills、MCP 配置；
- Capability Draft 保存和单次 Run Snapshot；
- Agent 流式文本、工具调用和运行状态展示；
- 停止、断线重连、刷新恢复；
- 人工交接摘要和阶段完成/重新打开；
- 右侧只读文档查看；
- 文件路径点击打开；
- 文档区拖动伸缩、收起、恢复、最大化和布局记忆；
- 文件更新提示和手动刷新；
- 路径、Catalog、进程与敏感信息安全边界。

### 16.2 延后实现

- 生成内容校验与自动 Gate；
- 正式 requirement-flow 绑定；
- 文档编辑和保存；
- 多文档 Tab、Diff 和搜索替换；
- 自动生成 commit、自动 push、自动部署；
- 多 Workbench 并发写同一仓库的冲突协调；
- 桌面封装和系统托盘能力；
- 把历史 Harness 的完整 Artifact、Gate、Approval 和审计模型迁入新 Workbench 数据模型。

## 17. 测试方案

### 17.1 Domain

- Workbench 固定包含四个唯一阶段；
- Capability Version 单调递增；
- Run Snapshot 绑定正确版本；
- 同一 Workbench 拒绝并发活动 Run；
- 阶段完成后可以人工重新打开；
- 用户可以跨阶段切换，系统不执行内容 Gate。

### 17.2 Application

- 创建 Workbench 时先校验工作目录，再保存聚合；
- 启动 Run 时冻结 Snapshot、创建/复用阶段会话并提交后台任务；
- Capability Draft 在运行中修改只影响下一轮；
- stop、恢复和事件发布只做编排，不在 Application 重组领域判断；
- 文件内容查询通过 Workspace Content Port，不直接读取任意路径。

### 17.3 Infrastructure

- SQLite 保存和恢复四阶段配置、摘要与 ChatSession 引用；
- 文件内容接口覆盖允许根、越界、符号链接、超限、二进制和编码异常；
- Capability Catalog 只返回允许项；
- Runtime Snapshot 不包含 Secret；
- ChatRun 重连和停止复用现有测试。

### 17.4 Frontend Vitest

- Split Pane 宽度边界、默认值和本地持久化；
- 文档收起/恢复/最大化状态；
- 阶段切换保留各自会话和当前文档；
- Capability Draft 脏状态与版本冲突提示；
- 文件变更事件把当前文档标记为 stale，但不自动替换正文；
- SSE 事件到消息和 Tool Card 的稳定映射。

### 17.5 Playwright

建立使用 Stub Runtime 和临时工作区的主链：

```text
创建 Workbench
→ 进入需求分析并调整 Rules/Skills/MCP
→ 发送消息并观察流式文本与工具事件
→ 编辑交接摘要并人工完成阶段
→ 切换技术方案，确认会话隔离
→ 点击 Agent 输出文件路径打开右侧文档
→ 拖动、收起、恢复和最大化文档区
→ 模拟文件变化并确认刷新提示
→ 刷新浏览器，恢复阶段、会话、文档和运行状态
```

E2E 不调用真实 Codex、外部 MCP、部署或网络服务。

## 18. 验收标准

- [ ] 用户进入任一阶段后，对话区是页面最大且最主要区域；
- [ ] 文档区收起时，对话区自动占满可用宽度；
- [ ] 文档区可拖动到边界，刷新页面后恢复宽度；
- [ ] 点击 Agent 输出中的授权文件路径可以在右侧打开；
- [ ] 当前文档被 Agent 修改时只提示更新，不打断阅读位置；
- [ ] 四个阶段的消息、Rules、Skills、MCP 和最近文档互相隔离；
- [ ] 能力配置在下一轮 Run 生效，运行中的 Snapshot 不变化；
- [ ] 用户可以手动完成、重新打开和任意切换阶段；
- [ ] 系统不因生成内容缺少格式或字段而阻止阶段切换；
- [ ] Agent 流式文本、工具调用、命令输出和终态可恢复查看；
- [ ] 页面不声称展示模型私有思维链；
- [ ] 未授权路径、Skill、MCP 和命令均被服务端拒绝；
- [ ] commit、push 和部署没有因进入某个阶段而自动获得授权。

## 19. 分步实施建议

### Phase 1：轻量工作台骨架

- 新建 DevWorkbench Domain、Repository 和基础 API；
- 新建四阶段页面与阶段切换；
- 复用 ChatPanel 完成独立阶段会话；
- 迁移窗口内保留现有 Harness，但不再向其领域状态机增加新产品能力。

### Phase 2：能力配置

- 接入 Rules、Skill、MCP Catalog；
- 实现 Capability Draft、版本和 Run Snapshot；
- 完成配置抽屉与下一轮生效提示。

### Phase 3：文档查看器

- 增加受控文件内容查询；
- 完成 Split Pane、Markdown/代码/文本展示；
- 接入文件路径点击、最近文档、stale 提示和布局记忆。

### Phase 4：workspace 能力纵向切片

- 为四阶段提供默认 Rules/Skills/MCP；
- 使用 Stub 先验证完整运行事件；
- 选择一个风险可控的本地真实需求完成四阶段试点；
- 根据试点决定是否进入正式 requirement-flow 绑定设计。

### Phase 5：Harness 退役

- 停止创建新的 Harness Run，并从主导航移除 Harness 入口；
- 为需要保留的历史 Run 提供一次性只读导出；
- 确认 Workbench 不依赖任何 Harness 专用包、表、配置或 API；
- 删除 Harness 前端、后端、配置、数据库建表和专用测试；
- 执行全量回归并确认普通 Chat、Workbench 和 workspace 能力不受影响。

## 20. Harness 模块退役与移除计划

### 20.1 退役原则

Harness 不是新工作台的长期兼容层。退役遵循以下原则：

1. **先替代能力，再删除模块**：Workbench 主链未通过验收前不删除仍被复用的 Runtime 或 Catalog；
2. **先解除依赖，再删除代码**：Workbench 不允许通过 Adapter 名义继续依赖 Harness Domain 类型；
3. **先停止新增，再处理历史**：先禁止创建新 Run，再给历史数据留出只读导出窗口；
4. **数据删除必须显式**：不在普通应用升级中静默删除 Harness 表和 Artifact；
5. **正式交付回归 workspace**：需要 Gate、证据和 finalize 时接入 `requirement-flow`，不重建第二代 Harness。

### 20.2 需要迁移的公共能力

当前 Harness 包内同时包含交付领域逻辑和可复用技术能力。删除前应按职责拆分：

| 当前能力 | 目标边界 | 处理方式 |
| --- | --- | --- |
| Codex/Claude Runtime 启停 | 通用 Agent Runtime 端口与 Adapter | 提取并由 Chat、Workbench 共同复用 |
| Runtime 事件与取消 | 通用后台 Run/SSE 能力 | 优先并入现有 ChatRun，不复制状态机 |
| Skill Catalog | 中性 Capability Catalog | 去除 HarnessStage、Attempt 等专用类型 |
| MCP Catalog 与授权 | 中性 MCP Capability Catalog | 保留 Catalog/allowlist，去除 Harness Snapshot 依赖 |
| Prompt/Rules 组装 | Workbench Phase Prompt 组装 | 使用 Phase、Rules 和 Handoff，不沿用 Artifact Contract |
| Artifact Store | 不迁入 Workbench MVP | 历史数据仅导出，正式交付产物归 workspace 管理 |
| Gate、Approval、DeploymentExecution | 不迁移 | 随 Harness 删除；部署由 workspace 受控能力负责 |

公共能力提取后的包名必须表达通用职责，例如 `app.runtime`、`domain.capability`、`infra.runtime`，而不是
让 Workbench import `harness.*`。具体包名在实现设计阶段结合现有 ChatRun 结构确认，避免提前建立重复
Runtime 抽象。

### 20.3 分阶段退役

#### R0：依赖盘点与冻结

- 使用代码检索和架构测试列出所有 `harness` 包、Bean、配置、API、导航、表和资源目录；
- 标记 Harness 为 Deprecated，仅允许安全修复，不再增加产品功能；
- 建立禁止 `workbench` 依赖 `harness` 的架构测试；
- 确认现有 Harness Run 数量、Artifact 大小和是否存在必须保留的历史记录。

#### R1：公共能力抽离

- 先以测试固定 Runtime、Skill、MCP 的现有行为；
- 把可复用能力迁移到中性端口和 Adapter；
- Chat、Workbench 改依赖中性接口；
- Harness 在过渡期通过中性接口继续工作，证明抽离没有改变行为；
- 中性模块不得接收 `HarnessRun`、`HarnessStage`、`StageAttempt` 或 Artifact Contract。

#### R2：停止新建与只读窗口

- 关闭 Harness 新建 Run、启动 Stage、Runtime、Approval 和 Deployment 等写入口；
- 从主导航移除 Harness，只保留管理员可见的历史只读入口；
- 页面展示明确的退役提示和最后可用版本；
- 提供按 Run 导出元数据、对话、Artifact 清单和正文的工具；
- 不把历史 Harness Run 自动转换为 Workbench，避免伪造新模型中的阶段语义。

#### R3：前后端代码删除

删除范围至少包括：

- `frontend/admin/harness.html`；
- `frontend/js/admin/pages/Harness.vue`、Harness Composable、API 和工具文件；
- Harness 导航、样式和前端测试；
- `/api/harness/**` Controller 与 DTO；
- `app/harness`、`domain/harness`、`infra/harness` 中未被提取的专用代码；
- Harness Spring 配置、Feature Flag、启动恢复任务和指标；
- Prompt Pack、Artifact Contract、部署模板等 Harness 专用运行资源；
- Harness 专用单测、集成测试、E2E Fixture 和脚本。

删除必须按依赖方向小步进行，不能通过保留空壳 Bean、兼容 Controller 或复制旧类型绕过编译错误。

#### R4：数据与配置清理

- 先备份 SQLite 和 `data/harness/`；
- 确认历史导出完成或用户明确不保留；
- 在独立、显式执行的维护动作中删除 `harness_*` 表、索引和 Artifact 文件；
- 从 `schema.sql` 删除 Harness 建表语句；
- 删除 `agent.harness.*`、`AGENT_HARNESS_*` 和相关配置文档；
- 清理不再使用的依赖、资源、日志类别和监控指标；
- 数据清理失败时停止并保留备份，不影响 Workbench 正常启动。

### 20.4 回滚策略

- R0/R1 阶段通过 Feature Flag 保持旧 Harness 可用，可回退到提取前版本；
- R2 只关闭写入口，不删除数据，发现 Workbench 阻断时可短期恢复只读前的版本；
- R3 前保留可构建的版本标签或提交点以及 SQLite/Artifact 备份；
- R4 是不可逆数据动作，只能在备份可验证且用户显式确认后执行；
- 不为回滚长期保留两套 Runtime、Catalog 或重复状态机。

### 20.5 Harness 移除验收标准

- [ ] Workbench 主链已完成真实本地需求试点并通过 MVP 验收；
- [ ] Workbench 和 Chat 不依赖 `harness.*` 包或 Harness 数据表；
- [ ] 应用主导航不再显示 Harness；
- [ ] `/api/harness/**` 不再注册；
- [ ] 源码中不存在 Harness Controller、App、Domain、Infra 和专用配置；
- [ ] `application.yml`、环境变量清单和启动脚本不再包含 `agent.harness.*`；
- [ ] `schema.sql` 不再创建 Harness 表；
- [ ] 历史数据已导出，或用户已明确批准删除；
- [ ] Chat、Workbench、文件查看、Skill/MCP 选择和 Runtime 回归通过；
- [ ] frontend build、backend test、Vitest 和相关 Playwright 主链通过；
- [ ] 正式交付能力明确由 workspace `requirement-flow` 承担，而不是残留 Harness 空壳。

## 21. 后续演进

### 21.1 正式交付模式

后续可以在 Workbench 上增加可选的正式交付绑定：

- 绑定已有或新建的 `requirement-flow` Run；
- 页面仍以对话为主；
- Gate、证据和交付状态放入折叠面板；
- 正式状态只读取 workspace 控制器投影；
- 不把 Workbench 的 `MANUALLY_COMPLETED` 当作交付 PASS。

### 21.2 文档编辑

只有真实使用证明只读查看不足时，再设计编辑器。届时必须同时处理：

- 文件脏状态；
- 用户与 Agent 同时修改冲突；
- 保存前版本比对；
- 编码和换行；
- 撤销、恢复和失败重试；
- 文件写权限与审计。

### 21.3 本地客户端

B/S MVP 稳定后，本地客户端只负责：

- 启停本机 agent-web 服务；
- 打开系统目录选择器；
- 承载 WebView 和窗口状态；
- 接入系统通知和可选托盘。

WorkBench、ChatRun、Capability Snapshot、workspace 接入和文件安全继续留在本地后端，避免形成 Web 与
客户端两套业务实现。

## 22. 最终用户心智模型

```text
选择本地工作空间
→ 在四个阶段中与 Agent 全尺寸对话
→ 随时调整当前阶段 Rules / Skills / MCP
→ 在右侧查看 Agent 正在读取或修改的文档
→ 人工整理阶段摘要并决定何时进入下一阶段
→ 需要正式交付时，再进入受控的 push / 部署 / 验证流程
```

MVP 的核心不是“把 Harness 隐藏起来”，而是用以对话、能力选择和文件阅读为中心的独立本地开发体验
完成替代，并在替代验收后删除 Harness。受控交付能力后续直接接入 workspace，而不是继续维护 Harness
兼容层。
