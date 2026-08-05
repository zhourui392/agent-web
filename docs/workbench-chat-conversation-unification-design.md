# Workbench / Chat 对话功能对齐与组件统一技术方案

> **作者**: alex
> **日期**: 2026-08-04
> **状态**: Draft v0.3

## 1. 背景、结论与目标

### 1.1 现状

Workbench 对话面板和 Chat 对话面板在消息渲染、输入交互和样式上存在大量重复，但两者并非只有展示差异：

1. Chat 使用单会话运行状态、单图片/文件上传、RAG、清除上下文、回退、反馈和分享。
2. Workbench 使用 Phase 会话、可恢复 Run、多文件附件、Repository Scope、Handoff 和 Review 精确授权。
3. 两边已经复用 ToolBlock、CommandPopup、Markdown formatter 等局部能力，但消息和 Composer 仍各自维护。
4. chat-panel.css 中的消息、工具、图片样式还被 Chat 首页、Share 和管理后台共同消费，不能只按两个页面处理。

如果只把现有 Chat 的 MessageItem 和 InputArea 直接嵌入 Workbench，会把 Chat 的 sending、workingDir、上传接口和按钮条件错误地当成通用语义，破坏 Workbench 的停止、附件和提交约束。

### 1.2 架构结论

本方案以 Chat 当前能力作为对话功能基线，统一用户可见的消息、Composer、命令和附件交互；Chat 与 Workbench 分别通过适配器提供数据、授权状态和领域动作。

统一的是：

- 消息、Markdown、复制、图片、Tool Block、Recall Card 等展示组件。
- Composer 骨架、键盘行为、发送/停止反馈和斜杠命令交互。
- 附件的展示合同和通用状态，不统一附件上传端点。
- 完整对话样式，不只提取当前重复的局部规则。
- CLI 事件解码管线：`CodexEventNormalizer` 作为唯一归一化器，Chat 与 Workbench 共用；SSE 事件格式统一为 chunk 协议。

不统一的是：

- Chat Session 与 Workbench Phase Conversation 的领域模型。
- useResumableRun 与 useWorkbenchConversation 的运行编排。
- Chat workingDir 与 Workbench Repository Scope 的授权模型。
- Chat 和 Workbench 的上传、命令查询、上下文、回退、反馈及分享 API。
- Workbench 专有的命令分类 enrichment（`RuntimeCommandPolicy`），作为归一化之后的可选后处理层，Chat 不跑。

核心依赖方向：

~~~text
Chat composable
    -> Chat presentation adapter
        -> shared conversation components
Workbench composable
    -> Workbench presentation adapter
        -> shared conversation components
~~~

共享组件不得反向解释 Chat 或 Workbench 的业务状态。

### 1.3 目标

- 以 Chat 为功能基线，让 Workbench 获得一致的消息、输入、命令、附件、发送、停止、加载、重连和错误反馈体验。
- 建立单一共享组件源，Chat 和 Workbench 不再复制消息和 Composer 模板。
- 建立单一共享样式源，并覆盖 Chat、Workbench、Share 和管理后台消费者。
- Workbench 保留 Owner、Phase、Handoff、Review、Repository Scope、Run Snapshot 和审计不变量。
- 对语义不同的功能统一用户意图和交互位置，由各自领域用例实现。
- 对暂需后端建设的 RAG、反馈和分享给出明确目标，不以隐藏控件代替功能设计。

### 1.4 非目标

- 不合并 Chat 与 Workbench 限界上下文。
- 不让共享前端组件承担 Owner、Handoff、Review 或 RunMode 判断。
- 不让浏览器提供的绝对 workingDir 成为 Workbench 授权依据。
- 不为了视觉一致而降低 Workbench 的多附件、拖拽、重试、时间戳和文档引用能力。

### 1.5 功能对齐定义

“功能对齐”表示用户在 Chat 和 Workbench 中能完成同一种对话意图，并得到一致的交互反馈；不要求两端调用同一 API 或执行完全相同的领域命令。

功能分为三类：

1. 完全一致：共享组件和交互逻辑。
2. 用户意图一致、领域语义不同：共享入口，由各自适配器执行不同用例。
3. 需要新增后端能力：进入后续实施阶段；未完成前不得宣称全部功能已对齐。

## 2. 功能基线与语义映射

| Chat 功能 | Workbench 目标 | 统一方式 | Workbench 约束 |
| --- | --- | --- | --- |
| 用户/Agent 消息 | 完全一致 | 共享 ConversationMessage | 保留时间戳和授权文档引用 |
| Markdown 与复制 | 完全一致 | 共享 formatter 和复制动作 | Markdown 继续经过 DOMPurify |
| Tool Block | 完全一致 | 共享 ToolBlock | Workbench 保留仓库、命令分类、耗时和终态 |
| 图片消息 | 完全一致 | 统一消息视图 | 图片 URL 仍由各端安全投影产生 |
| 系统/错误消息 | 完全一致 | 统一消息角色 | Workbench 可由适配器产生系统时间线项 |
| 文件变更 / MCP 工具调用 | 完全一致 | 统一 segment type | `CodexEventNormalizer` 扩展认 `file_change` / `mcp_tool_call`，Chat 与 Workbench 共用同一解码管线 |
| 测试进度 | 仅 Workbench | Workbench 专属 segment type | `RuntimeCommandPolicy` 作为归一化后可选 enrichment 层合成，Chat 不跑 |
| Loading/重连 | 视觉一致 | 共享展示，状态分别适配 | 当前活动 Run 表现为最后一条流式 ASSISTANT 消息 |
| Enter/Ctrl+Enter | 完全一致 | 共享 Composer 键盘逻辑 | 提交必须经过各端 canSubmit |
| 斜杠命令 | 完全一致 | 共享过滤、导航和选择 | Workbench 命令查询必须经过 Workbench 授权 |
| 图片/文件上传 | 体验一致，Workbench 为增强版 | 统一附件视图，上传动作分别适配 | Workbench 保留多文件、拖拽、重试和 8 个上限 |
| 发送 | 完全一致 | 共享按钮和反馈 | Workbench 保留 Owner/Handoff/Review/Run 约束 |
| 停止 | 完全一致 | 共享按钮和反馈 | runActive 与 submitting 必须分离 |
| 清除上下文 | 统一为”开始新对话上下文” | 共享入口，各端不同用例 | Workbench 创建新 generation，旧历史只读保留 |
| 从此处重开 | 不实现 | — | Workbench 仅保留 Restart（整段重开），不实现从消息分叉 |
| RAG/上下文召回 | 目标对齐 | 共享开关与 Recall Card | Workbench 按 Owner/Workbench/Phase 限定范围 |
| Feedback | 目标对齐 | 共享反馈组件 | Workbench 评价绑定 Run/消息，不复用 Chat Session API |
| Share | 目标对齐但需安全投影 | 共享入口和只读展示 | 不泄漏绝对路径、文件正文和原始命令环境 |

Workbench 的时间戳、多文件、仓内文档、Handoff、Review 和测试进度属于增强能力，不要求反向添加到 Chat。

## 3. 领域命令、不变量与一致性边界

### 3.1 用户意图与领域命令

共享 UI 只表达以下用户意图：

- SubmitConversationMessage
- StopConversationRun
- StartNewConversationContext
- ToggleConversationRecall
- AttachConversationFile
- RemoveConversationAttachment
- SubmitConversationFeedback
- CreateConversationShare

适配器负责将意图映射到各自用例：

| 用户意图 | Chat | Workbench |
| --- | --- | --- |
| SubmitConversationMessage | Chat Run 提交 | SubmitPhaseMessage |
| StopConversationRun | 停止 ChatRun | StopPhaseRun |
| StartNewConversationContext | 清除 resume 上下文 | RestartPhaseConversation |
| ToggleConversationRecall | Chat RAG 开关 | Workbench Phase Recall Policy |
| SubmitConversationFeedback | Chat Session Feedback | Workbench Run/Message Feedback |
| CreateConversationShare | Chat Share Projection | Workbench Safe Share Projection |

共享组件只 emit 用户意图，不直接调用任一端 API。

### 3.2 Workbench 必须保持的不变量

1. 只有 Owner 可以提交消息、操作附件、停止 Run、重启会话、评价或分享。
2. Workbench 归档后只能查看，不能触发任何对话写操作。
3. 当前 Phase 有活动 Run 时不能重复提交，但停止入口必须持续可用。
4. submitting、runActive 和 stopping 是三个独立状态，不能压缩成 sending。
5. Handoff 未按要求接受时不能提交下游 Phase。
6. Review 写运行必须绑定当前 Review Opinion 的精确 confirmationId、version 和 hash。
7. 附件上传或移除期间不能提交；只有 AVAILABLE 且仍属于当前 Workbench/Phase/generation 的附件才能进入请求。
8. 文档引用必须使用 repositoryKey + relativePath，并经过 Repository Scope 授权。
9. Restart 不删除旧 generation、Run、Snapshot、Handoff 或审计引用。
10. 正在运行的 WorkbenchRunSnapshot 不因 UI 开关、Capability 或 Recall 配置变化而改变。
11. 浏览器输入的绝对路径和 Chat workingDir 不能作为 Workbench 授权凭据。
12. 前端 canSubmit 是交互守卫，服务端领域策略仍是最终授权来源。

### 3.3 一致性与引用方向

- Chat 聚合和 Workbench 聚合不互相引用。
- 共享前端只接收不可变展示模型和动作回调。
- Workbench adapter 可以读取 Workbench composable 的状态，但不能在共享组件中重建 Phase 规则。
- Restart、Feedback、Share 等新增 Workbench 用例使用 Workbench 嵌套授权 API。
- Workbench Run 提交和停止继续由现有应用服务负责；组件统一不改变事务和 SSE 恢复边界。

## 4. 目标前端架构

### 4.1 目录结构

~~~text
frontend/js/
  components/
    conversation/
      ConversationTimeline.vue
      ConversationMessage.vue
      ConversationComposer.vue
      ConversationAttachmentList.vue
      ConversationCommandPopup.vue
      ToolBlock.vue
      RecallCard.vue
    chat-panel.vue
    WorkbenchConversationPanel.vue
  composables/
    useSlashCommandInteraction.ts
    useResumableRun.ts
    useWorkbenchConversation.ts
  lib/
    conversation-message-view.ts
    conversation-attachment-view.ts
    formatters.ts
    clipboard.ts

frontend/public/css/
  conversation.css
  chat-panel.css
  workbench.css
~~~

迁移期间可以保留现有组件路径并逐步移动；最终共享组件不能使用 Chat 或 Workbench 前缀。

### 4.2 组件职责

ConversationTimeline：

- 提供消息和时间线项的统一布局。
- 支持空状态、加载更早消息和新输出提示的 slot。
- 当前活动 Run 表现为 Timeline 末尾的一条流式 ASSISTANT 消息，不单独维护 Run blocks 渲染区。
- 不读取 SSE，不判断 Phase，不操作滚动以外的业务状态。

ConversationMessage：

- 渲染用户、Agent、系统和错误消息。
- 渲染 Markdown、复制按钮、图片、Tool Block、Recall Card、文件变更和 MCP 工具调用 segment。
- 支持 header 和 actions slot。
- 通过 messageKey 管理展示身份，不依赖数组下标。

ConversationComposer：

- 渲染 textarea、命令弹窗、发送和停止按钮。
- 统一 Enter、Ctrl+Enter、上下键、Tab、Escape 和焦点行为。
- 只消费 inputDisabled、canSubmit、submitting、runActive 和 stopping。
- 通过 attachments、leftActions、rightActions 和 status slot 扩展。
- 不内置 Chat 图片上传、Chat 单文件上传、RAG 或清除上下文按钮。

ConversationAttachmentList：

- 只展示标准化附件状态。
- 上传、重试、取消、移除由各端 adapter 提供。

useSlashCommandInteraction：

- 只负责命令过滤、弹窗、键盘导航、选择和 textarea 光标。
- 通过 loadCommands 回调加载命令。
- 不硬编码 /api/chat/commands、workingDir 或 DOM 选择器。

### 4.3 双适配器

chat-panel.vue 保持 Chat 页面编排职责：

- 将 ChatMessage 映射为 ConversationMessageView。
- 从 useResumableRun 生成 Composer 状态。
- 注入 Chat 图片/文件、RAG、上下文、回退、反馈和分享动作。
- 通过 Chat 命令查询加载命令。

WorkbenchConversationPanel.vue 保持 Workbench 页面编排职责：

- 将 WorkbenchPhaseConversationMessage 映射为 ConversationMessageView。
- 当前活动 Run 映射为最后一条流式 ASSISTANT 消息，text/tool/file_change/mcp_tool_call segment 走共享渲染；test_progress segment 仅 Workbench 出现，由 enrichment 层合成。
- 计算 inputDisabled 和 canSubmit，并传给共享 Composer。
- 注入多附件、拖拽、仓内文档、Handoff、Review 和阶段状态。
- 通过 Workbench 命令查询加载命令。

Workbench 不需要改成字符串 provide/inject。共享组件使用显式 props/emits；现有 Chat provide/inject 可以在 Chat adapter 内暂时保留，并在迁移完成后清理。

## 5. 共享展示合同

### 5.1 消息视图

~~~typescript
type ConversationMessageRole =
  | 'USER'
  | 'ASSISTANT'
  | 'SYSTEM'
  | 'ERROR';

type ConversationSegmentType =
  | 'text'
  | 'tool'
  | 'file_change'
  | 'mcp_tool_call'
  | 'test_progress';

interface ConversationSegmentView {
  type: ConversationSegmentType;
  content: string;
  // tool / mcp_tool_call
  toolName?: string;
  status?: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  // tool
  commandClass?: 'SHELL' | 'TEST' | 'BUILD' | 'OTHER';
  repositoryKey?: string;
  exitCode?: number;
  durationMs?: number;
  // file_change
  relativePath?: string;
  changeType?: 'CREATE' | 'MODIFY' | 'DELETE';
  // test_progress
  suiteName?: string;
  summary?: string;
}

interface ConversationMessageView {
  messageKey: string;
  persistedMessageId: number | null;
  role: ConversationMessageRole;
  bodyText: string;
  images: ReadonlyArray<string>;
  segments: ReadonlyArray<ConversationSegmentView>;
  createdAt: string | null;
  recall: ConversationRecallView | null;
  documentReferences: ReadonlyArray<AuthorizedDocumentReference>;
  streaming: boolean;
}
~~~

禁止使用任意索引签名保存原始消息。共享组件需要的字段必须进入明确合同；只被 Workbench 使用的数据通过已命名字段或 slot 传递。

Chat adapter：

- user 保持 USER。
- agent 映射为 ASSISTANT。
- system/error 映射为对应角色。
- 已解析的 bodyText、images、segments 和 recall 直接进入视图。
- streaming 为 false（Chat 不使用 enrichment 层）。

Workbench adapter：

- user 映射为 USER，并通过 parseUserMessage 解析文本与图片。
- assistant 映射为 ASSISTANT。
- chunk SSE 经 parseStreamJson 解析为 text / tool / file_change / mcp_tool_call segment。
- test_progress segment 仅在 `RuntimeCommandPolicy` enrichment 层合成后出现。
- 当前活动 Run 映射为一条 streaming=true 的 ASSISTANT 消息；持久化后切换为 persistedMessageId + streaming=false。
- documentReferences 只能来自 extractAuthorizedAgentDocumentReferences。
- createdAt 使用持久化消息 timestamp。

messageKey 使用稳定字符串：

~~~typescript
// 持久化消息
const messageKey = 'persisted-message-' + persistedMessageId;
// 流式运行中的 assistant 消息
const messageKey = 'run-' + runId + '-streaming';
// 持久化后切换为 persisted-message- 前缀
~~~

工具展开键使用 messageKey + segmentIndex，不再让 Chat 传数组下标、Workbench 传 messageId。

### 5.2 Composer 合同

~~~typescript
interface ConversationComposerProps {
  modelValue: string;
  placeholder: string;
  maximumLength: number;
  textareaRows: number;
  inputDisabled: boolean;
  canSubmit: boolean;
  submitting: boolean;
  runActive: boolean;
  stopping: boolean;
  commands: ReadonlyArray<ConversationCommand>;
  commandPopupVisible: boolean;
  selectedCommandIndex: number;
}
~~~

共享事件：

~~~typescript
interface ConversationComposerEmits {
  (event: 'update:modelValue', value: string): void;
  (event: 'submit'): void;
  (event: 'stop'): void;
  (event: 'paste-files', files: ReadonlyArray<File>): void;
  (event: 'select-command', command: ConversationCommand): void;
}
~~~

Chat 状态适配：

- submitting 和 runActive 可以都来源于现有 sending，但以两个明确字段传入。
- stopping 需要保留停止请求自身状态。
- canSubmit 由 Chat adapter 根据工作目录、Runtime、输入和发送状态计算。

Workbench 状态适配：

~~~typescript
const inputDisabled = computed(() =>
  props.readOnly || props.submitting
);

const canSubmit = computed(() =>
  !props.readOnly
  && props.identityReady
  && !props.submitting
  && !uploadsBusy.value
  && !runActive.value
  && props.handoffReady
  && (props.phase !== 'REVIEW_REFACTOR' || props.modifyReady)
  && Boolean(props.modelValue.trim())
);
~~~

Workbench 的 submit 回调必须调用带 canSubmit 守卫的 emitSubmit，不能直接 emit 原始 submit 事件。

### 5.3 附件视图

~~~typescript
type ConversationAttachmentStatus =
  | 'UPLOADING'
  | 'AVAILABLE'
  | 'FAILED'
  | 'REMOVING';

interface ConversationAttachmentView {
  attachmentKey: string;
  displayName: string;
  mediaType: string;
  size: number;
  previewUrl: string | null;
  status: ConversationAttachmentStatus;
  errorMessage: string | null;
  removable: boolean;
  retryable: boolean;
}
~~~

共享合同不包含 storageKey、绝对路径或上传端点。

Chat adapter 将 pendingImages 和 pendingFile 映射为附件视图；Workbench adapter 将 repository attachments 和 uploaded attachments 映射为附件视图，并保留逻辑引用。

### 5.4 斜杠命令

~~~typescript
interface SlashCommandInteractionOptions {
  userInput: Ref<string>;
  loadCommands: () => Promise<ReadonlyArray<ConversationCommand>>;
  onSubmit: () => void;
  textareaElement: Ref<HTMLTextAreaElement | null>;
}
~~~

Chat 注入 Chat 命令查询；Workbench 目标接口为：

~~~http
GET /api/workbenches/{workbenchId}/phases/{phase}/commands
~~~

该接口由 Workbench Owner 边界授权，服务端从 Repository Scope 和当前 Capability Profile 解析命令，不接受浏览器提交 workingDir。

## 6. Chat 特有能力的 Workbench 对齐方案

### 6.1 开始新对话上下文

共享 UI 使用“开始新对话上下文”语义：

- Chat 清除 resume 上下文，保留当前消息展示。
- Workbench 调用 RestartPhaseConversation，创建新 generation。
- Workbench 旧 generation、消息和 Run 保持只读。
- Workbench 必须在无活动 Run、未归档且 Owner 有权时才允许执行。
- Workbench 继续要求显式确认，不把重启和人工 reopen 合并成一个动作。

### 6.2 RAG/上下文召回

Workbench 新增 ConversationRecallPolicy，至少包含：

- ownerId
- workbenchId
- phase
- conversationGeneration
- acceptedHandoffVersion
- enabled

规则：

1. 默认不召回其他用户、其他 Workbench 或未接受的上游内容。
2. 是否跨 Phase 召回由明确策略决定，不能由前端开关隐式扩大。
3. 开关只影响下一轮 Run。
4. 召回结果及来源摘要进入 WorkbenchRunSnapshot。
5. 可展示结果通过统一 Recall Card 输出，不返回敏感正文。

### 6.3 Feedback

Workbench Feedback 绑定：

~~~text
workbenchId + phase + runId + assistantMessageId
~~~

只有 Owner 可以评价。Feedback 不改变 Run、Handoff、Review 或 Phase 状态，也不复用 Chat Session Feedback API。

### 6.4 Share

Workbench Share 必须建立独立安全只读投影：

- token 只保存哈希，支持撤销和过期。
- 不返回绝对路径、Runtime 环境、Secret、原始命令参数或未经脱敏的 stderr。
- 文件引用只展示 repositoryKey + relativePath；默认不返回文件正文。
- 不暴露 Run 停止、恢复、重放、附件下载或任何写入口。
- 分享创建和撤销只允许 Owner。

未完成该安全投影前，Workbench Share 保持不可用，并标记为待对齐能力，而不是复用 Chat Share API。

## 7. CSS 单一来源

conversation.css 必须包含完整对话视觉，而不只是当前重复规则：

- timeline 间距和消息根节点。
- 用户、Agent、系统和错误消息。
- 消息 header、角色、时间戳和操作按钮。
- Markdown segment、复制按钮和图片网格。
- Tool Block、Recall Card、loading dots 和命令弹窗。
- Composer 骨架和附件展示。
- 对话相关移动端规则。

页面专属 CSS 只保留：

- chat-panel.css：Chat 页面容器、Feedback、Share 和 Chat 专属动作。
- workbench.css：Workbench 布局、Phase、Handoff、Review 和阶段状态。

文件变更、MCP 工具调用和测试进度 segment 的样式纳入 conversation.css 共享层（test_progress 样式仅 Workbench 触发，但规则定义在共享文件中）。

加载关系：

所有页面在 HTML 中显式按序加载，避免 `@import` 的渲染阻塞串行链：

~~~html
<!-- Chat 首页 / Share / Admin Chat -->
<link rel="stylesheet" href="css/app.css">
<link rel="stylesheet" href="css/conversation.css">
<link rel="stylesheet" href="css/chat-panel.css">

<!-- Workbench -->
<link rel="stylesheet" href="css/conversation.css">
<link rel="stylesheet" href="css/workbench.css">
~~~

- 共享 CSS 先加载，页面专属 CSS 后加载，浏览器可并行下载。
- 需要修改 index.html、workbench.html 及 Share / Admin Chat 页面引入 conversation.css。
- 提取前必须比较同名规则差异，选定统一视觉；不能同时承诺规则原样不变和两端视觉完全一致。

## 8. 实施步骤

### Phase 0：功能基线与特征测试

1. 固化 Chat 的消息、命令、快捷键、上传、发送、停止、回退、重连、反馈和分享行为。
2. 固化 Workbench 的 Owner、Handoff、Review、Run、附件、归档和恢复行为。
3. 将本方案功能矩阵转成自动化用例清单。
4. 标识当前源码字符串测试中需要迁移为组件行为测试的断言。

### Phase 1：完整共享样式

1. 新建 conversation.css（含 file_change / mcp_tool_call / test_progress segment 样式）。
2. 迁移消息、工具、图片、命令、loading、Recall、Composer 和移动端样式。
3. 修改 index.html、workbench.html 及 Share / Admin Chat 页面，在 HTML 中显式按序加载 conversation.css。
4. chat-panel.css 删除已迁移规则，保留 Chat 专属规则。
5. workbench.css 删除已迁移规则，保留 Workbench 专属规则。
6. 验证 Chat、Share、Admin Chat 和 Workbench。

### Phase 2：共享消息与时间线

1. 新建 ConversationMessageView（含 segment type 联合）和两个消息 adapter。
2. 提取 ConversationMessage、ToolBlock 和 RecallCard。
3. Chat 先迁移并保持行为不变。
4. Workbench persisted messages 改用共享组件。
5. 当前活动 Run 映射为流式 ASSISTANT 消息（text / tool / file_change / mcp_tool_call / test_progress segment），不单独维护 Run blocks 渲染区。
6. 验证 persisted assistant 消息与当前流式消息不重复。

### Phase 3：共享 Composer 骨架

1. 新建 ConversationComposer，使用显式 props/emits。
2. 统一键盘、textarea ref、命令弹窗位置、发送和停止按钮。
3. Chat 通过 slots 注入图片、单文件、RAG 和上下文动作。
4. Workbench 通过 slots 注入仓内文档、多文件、拖拽、重试和阶段状态。
5. 分别传入 canSubmit、submitting、runActive 和 stopping。
6. 保留稳定 data-test，并支持由 adapter 指定。

### Phase 4：斜杠命令与附件对齐

1. 将 useSlashCommand 改为 useSlashCommandInteraction。
2. 注入 loadCommands 和 textareaElement，不保留硬编码 fallback。
3. 新增 Workbench 范围内的命令查询接口。
4. 建立 ConversationAttachmentView。
5. Chat 和 Workbench 分别适配上传动作，共享附件展示。

### Phase 5：事件管线统一

1. 扩展 `CodexEventNormalizer` 认 `file_change` 和 `mcp_tool_call` item type，产出归一化事件。
2. Workbench 事件路径切换为走 `CodexEventNormalizer` + chunk SSE，与 Chat 一致。
3. `RuntimeCommandPolicy` 作为归一化后可选 enrichment 层保留，对 tool/command 事件做命令分类并合成 test_progress；Chat 不跑此层。
4. 前端 `workbench-run-state.ts` 退化为与 Chat 相同的 `parseStreamJson` 解析，删除 blocks / staleDocuments / testProgress / operations 结构化集合。
5. 删除 `RuntimeEventDecoder`（其 `isWorkbenchSpecificEvent` 逻辑已合并进 `CodexEventNormalizer`）。
6. 删除 `WorkbenchRunProjectingStreamSink` 的逐事件 typed envelope，改为 chunk 发送。
7. 验证 Chat 现有行为无退化，Workbench Run 渲染不丢信息。

### Phase 6：上下文能力对齐

1. 统一”开始新对话上下文”入口。
2. 保留 Workbench restart generation 语义和确认。
3. 为 Chat 清除上下文与 Workbench restart 分别补 Playwright 测试。

### Phase 7：RAG、Feedback 与 Share

1. 实现 Workbench ConversationRecallPolicy 和 Snapshot 集成。
2. 实现 Workbench Run/Message Feedback。
3. 实现 Workbench Safe Share Projection。
4. 每项能力独立上线；未完成的能力在功能矩阵中保持”待对齐”。

### Phase 8：清理与全量回归

1. 删除 Workbench 内联消息和 Composer 死代码。
2. 删除 `RuntimeEventDecoder`、`workbench-run-state.ts` 及相关死代码。
3. 删除重复 CSS 和废弃选择器。
4. 删除旧字符串 provide/inject 合同和硬编码 DOM 选择器。
5. 完成 Chat、Workbench、Share、Admin Chat 全量回归。

Phase 1～4 是前端核心统一；Phase 5 是事件管线统一（前后端）；Phase 6～7 涉及后端领域用例和安全投影，应独立估算，不能继续使用原方案 2～3 天的整体估算。

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 把 submitting 当成 runActive | 运行中停止按钮消失 | Composer 使用三个独立状态 |
| 共享按钮绕过 Workbench canSubmit | 未接受 Handoff 或 Review 时发起请求 | adapter 计算 canSubmit，submit 回调再次守卫 |
| Chat 与 Workbench 上传控件同时出现 | 重复入口、错误上传端点 | Composer 不内置上传，只提供 slots |
| CSS 提取破坏 Share/Admin | 历史消息、工具和图片失去样式 | HTML 显式加载 conversation.css，覆盖所有消费者 |
| MessageItem 只迁移局部样式 | Workbench 消息气泡无样式 | 迁移完整消息视觉 |
| 命令查询复用 Chat workingDir | 绕过 Workbench 范围与 Capability | 注入命令加载器，新增 Workbench 嵌套接口 |
| Restart 删除旧历史 | Run、Snapshot 和 Handoff 引用失效 | Workbench 只创建新 generation |
| RAG 跨越用户或 Workbench | 数据越权 | Owner/Workbench/Phase 范围策略和 Snapshot |
| Share 泄漏本机信息 | 路径、命令或文件内容泄漏 | 独立脱敏只读投影 |
| 共享合同继续增长条件分支 | 组件重新变成产品编排器 | 稳定 props + segment type 联合 + 双 adapter；Workbench 独有字段超过 2 个时改 slot |
| 事件管线切换破坏 Workbench Run 渲染 | 文件变更、测试进度或工具状态丢失 | enrichment 层独立验证；Run 前后行为对比回归 |

## 10. 文件变更清单

### 10.1 前端核心统一

| 文件 | 操作 | Phase |
| --- | --- | --- |
| frontend/public/css/conversation.css | 新建完整共享样式（含 file_change / mcp_tool_call / test_progress segment 样式） | 1 |
| frontend/public/css/chat-panel.css | 改为消费共享样式，保留 Chat 专属规则 | 1 |
| frontend/public/css/workbench.css | 删除共享规则，保留 Workbench 专属规则 | 1 |
| frontend/index.html | 按序引入 app.css → conversation.css → chat-panel.css | 1 |
| frontend/workbench.html | 按序引入 conversation.css → workbench.css | 1 |
| frontend/js/lib/conversation-message-view.ts | 新建消息视图合同与 segment type 联合 | 2 |
| frontend/js/lib/conversation-attachment-view.ts | 新建附件展示合同 | 4 |
| frontend/js/components/conversation/ConversationTimeline.vue | 新建 | 2 |
| frontend/js/components/conversation/ConversationMessage.vue | 从 MessageItem 演进，支持 file_change / mcp_tool_call / test_progress segment | 2 |
| frontend/js/components/conversation/ConversationComposer.vue | 从 InputArea 中提取中性骨架 | 3 |
| frontend/js/components/conversation/ConversationAttachmentList.vue | 新建 | 4 |
| frontend/js/components/conversation/ConversationCommandPopup.vue | 从 CommandPopup 演进 | 3 |
| frontend/js/components/conversation/ToolBlock.vue | 迁移现有共享组件 | 2 |
| frontend/js/components/conversation/RecallCard.vue | 提取为共享组件 | 2 |
| frontend/js/composables/useSlashCommandInteraction.ts | 取代 Chat API 耦合实现 | 4 |
| frontend/js/lib/workbench-run-state.ts | 退化为 parseStreamJson 解析，删除结构化集合 | 5 |
| frontend/js/components/chat-panel.vue | 作为 Chat adapter 适配共享组件 | 2～4 |
| frontend/js/components/WorkbenchConversationPanel.vue | 作为 Workbench adapter 适配共享组件，Run 映射为流式消息 | 2～5 |

### 10.2 事件管线统一

| 文件 | 操作 | Phase |
| --- | --- | --- |
| src/main/java/.../infra/cli/CodexEventNormalizer.java | 扩展认 `file_change` / `mcp_tool_call` item type | 5 |
| src/main/java/.../infra/runtime/RuntimeEventDecoder.java | 删除（`isWorkbenchSpecificEvent` 逻辑合并进 CodexEventNormalizer） | 5 |
| src/main/java/.../infra/runtime/RuntimeCommandFactory.java | 保留，enrichment 层仍需命令分类 | 5 |
| src/main/java/.../app/chatrun/ChatRunRuntimeEventProcessor.java | 改为走 CodexEventNormalizer + 可选 enrichment + chunk SSE | 5 |
| src/main/java/.../app/workbench/run/WorkbenchRunProjectingStreamSink.java | 退化为 chunk 发送，删除逐事件 typed envelope | 5 |
| RuntimeCommandPolicy enrichment（新建或提取） | 作为 CodexEventNormalizer 之后的可选后处理层 | 5 |

### 10.3 Workbench 后端能力

Phase 7 的具体 Java 文件在进入对应实现前按领域用例设计和 TDD 门禁确定，至少涉及：

- Workbench 范围内的 Slash Command Query Service。
- ConversationRecallPolicy 与 WorkbenchRunSnapshot 集成（含 Phase Conversation 的 embed → store → recall 管线新建）。
- Workbench Run/Message Feedback。
- Workbench Safe Share Projection。

这些能力不得通过向 Chat Controller 增加 Workbench 条件分支实现。

### 10.4 测试

| 文件/范围 | 调整 |
| --- | --- |
| tests/unit/workbench-interaction-components.spec.ts | 从内联源码结构断言迁移到共享组件合同和适配行为 |
| tests/unit/use-workbench-conversation.spec.ts | 保留提交、停止、恢复、附件和幂等状态测试 |
| tests/e2e/chat.spec.ts | 回归 Chat 消息、命令、附件、上下文、回退和停止 |
| tests/e2e/workbench.spec.ts | 回归 Workbench 权限、Handoff、Review、附件、停止、归档和恢复 |
| tests/e2e/workbench-real.spec.ts | 验证真实 Run 提交、恢复和停止 |
| Share/Admin Chat 测试 | 验证共享 CSS 后消息、工具和图片仍可见 |

## 11. 验收标准

### 11.1 共享体验

1. Chat 和 Workbench 的用户/Agent 消息、Markdown、复制、图片和 Tool Block 视觉一致。
2. Enter 发送、Ctrl+Enter 换行、上下键选择命令、Tab 补全和 Escape 关闭行为一致。
3. 两端命令弹窗使用同一交互实现，但分别经过自己的命令查询 adapter。
4. 两端发送、停止按钮的位置和反馈一致。
5. Chat、Workbench、Share 和 Admin Chat 均使用 conversation.css，且消息渲染无退化。
6. 共享组件中不存在 /api/chat、workingDir、Workbench Phase 或 Review 条件。
7. 共享组件不使用 document.querySelector 定位 textarea。
8. Chat 和 Workbench 使用同一 `CodexEventNormalizer` 解码 CLI 输出，SSE 均为 chunk 协议。
9. `file_change` 和 `mcp_tool_call` 事件在 Chat 和 Workbench 中均可见（Chat 可选择不展示）。
10. `RuntimeEventDecoder` 已删除，`RuntimeCommandPolicy` enrichment 层仅 Workbench 运行。
11. Workbench Run 的文件变更、MCP 工具调用和测试进度在统一 segment 渲染中不丢信息。

### 11.2 Workbench 不变量

1. Owner 身份未就绪时不能提交。
2. 未接受必需 Handoff 时不能提交。
3. Review 未完成精确确认时不能发起写 Run。
4. 当前 Phase 有活动 Run 时不能重复提交。
5. submitting 为 false 且 runActive 为 true 时停止按钮仍显示。
6. stopping 为 true 时停止按钮显示加载态并阻止重复停止。
7. 上传或移除附件期间不能提交。
8. 归档 Workbench 的 Composer、附件、上下文、Feedback 和 Share 写动作均禁用。
9. 文档引用只来自授权后的 repositoryKey + relativePath。
10. Restart 不删除旧 generation、Run、Snapshot 或 Handoff。
11. Workbench 命令查询不接受客户端绝对路径作为授权依据。
12. 当前 Run 的 Recall 和 Capability 配置在 Snapshot 内保持不变。

### 11.3 自动化门禁

前端：

~~~bash
cd frontend
npm run typecheck
npm run lint
npm run build
~~~

测试工程：

~~~bash
cd tests
npm run typecheck
npm test
~~~

按阶段运行目标 Playwright：

- Chat：消息、输入、命令、附件、停止、上下文、回退、反馈和分享。验证 `file_change` / `mcp_tool_call` segment 可见。
- Workbench：消息、输入、命令、多附件、Handoff、Review、提交、停止、恢复、归档、文档引用、文件变更和测试进度。
- Share/Admin Chat：消息、工具、图片和共享 CSS。

涉及 Phase 5～6 的 Java 业务逻辑必须遵守红—绿—重构，领域测试不模拟聚合，SQLite 和接口按项目门禁验证。

## 12. 完成定义

满足以下条件后，才能宣称“Workbench 已向 Chat 功能对齐”：

1. 第 2 节功能矩阵中没有未批准的“待对齐”能力。
2. 共享组件只承载展示和通用交互，两个领域 adapter 的职责清晰。
3. Workbench 不变量和 Chat 原行为均有自动化证据。
4. RAG、Feedback、Share 等若未实现，已由产品明确批准为差异并从“完全对齐”口径中排除。
5. 前端、测试工程和目标 Playwright 门禁全部通过。
