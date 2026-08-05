# Workbench 对话与 Chat 对话统一计划

## 背景

Workbench 对话面板 (`WorkbenchConversationPanel.vue`) 和 Chat 对话面板 (`chat-panel.vue` + `MessageItem.vue` + `InputArea.vue`) 在消息渲染、输入交互、实时流渲染方面存在显著不一致。两边后端共用 `chat_message` 表和 `StreamChunkHandler`，agent 消息存储格式相同（stream JSON），但前端渲染方式完全不同。

## 改动清单

### 1. 扩展 `ToolBlock.vue` — 支持可选富字段

**文件**: `frontend/js/components/ToolBlock.vue`

当前 ToolBlock 只接受 `{ name, content }`。Workbench 实时 tool block 还携带 `status`、`durationMs`、`commandSummary`、`outputSummary`、`repositoryKey`、`commandClass`、`exitCode`。

改动：
- 新增可选 props: `status?`, `durationMs?`, `commandSummary?`, `outputSummary?`, `repositoryKey?`, `commandClass?`, `exitCode?`
- header 中 status 存在时显示 el-tag（success/danger/primary）
- header 中 durationMs 存在时显示时长
- 展开内容中显示 commandSummary、outputSummary、repositoryKey、commandClass、exitCode（存在则显示）
- 保持向后兼容：chat 侧仍只传 `segment` prop，新增字段全部可选

### 2. 改造 `WorkbenchConversationPanel.vue` — 消息渲染统一

**文件**: `frontend/js/components/WorkbenchConversationPanel.vue`

#### 2a. Agent 历史消息 — 解析 stream JSON 为 segments

当前：`v-html="renderMarkdown(message.content)"`（纯 markdown，工具调用不可见）

改为：
- import `isStreamJson`, `parseStreamJson`, `parseUserMessage`, `imageUrl` from `formatters.ts`
- import `ToolBlock` 组件
- 对 `visibleMessages` computed 增加 segments 解析：
  - assistant 消息：`isStreamJson(content) ? parseStreamJson(content) : [{ type: 'text', content }]`
  - user 消息：`parseUserMessage(content)` -> `{ bodyText, images }`
- 模板渲染：
  - assistant text segment：copy 按钮 + `v-html="renderMarkdown(seg.content)"`（与 MessageItem.vue 一致）
  - assistant tool segment：`<ToolBlock :segment="seg" ...>`
  - user：`bodyText` + images grid（`el-image` 带 preview，与 MessageItem.vue 一致）
- 保留 workbench 特有的 documentReferences（在 segments 之后渲染）
- 保留 timestamp 显示

#### 2b. User 历史消息 — 解析 bodyText + images

当前：`<p>{{ message.content }}</p>`

改为：parseUserMessage 提取 bodyText + images，渲染 bodyText 文本 + 图片网格（与 MessageItem.vue 一致，但不含 rewind 按钮，因为 workbench 无此功能）

#### 2c. 实时 Run tool block — 使用 ToolBlock

当前：自定义 `<details>` 渲染（summary + tool-body with commandSummary/outputSummary/dl meta）

改为：`<ToolBlock>` + 扩展字段（status, durationMs, commandSummary, outputSummary, repositoryKey, commandClass, exitCode）。ToolBlock 展开后展示这些字段，视觉与 chat 的 ToolBlock 一致。

agent_chunk 实时块保持 `v-html="renderMarkdown(block.content)"` 不变（已是文本 chunk，无 stream JSON）。

### 3. 改造 `WorkbenchConversationPanel.vue` — 输入区统一

**文件**: `frontend/js/components/WorkbenchConversationPanel.vue`

#### 3a. 键盘快捷键统一

当前：`@keydown.ctrl.enter.prevent="emitSubmit"` + `@keydown.meta.enter.prevent="emitSubmit"`（Ctrl+Enter 发送）

改为（与 chat 一致）：
- `@keydown.enter.exact.prevent="handleEnter"` — Enter 发送（或选中命令）
- `@keydown.ctrl.enter.exact.prevent="insertNewline"` — Ctrl+Enter 换行
- 新增 `insertNewline()` 方法（在 textarea 光标处插入换行）

#### 3b. 斜杠命令弹窗

当前：无

改为：
- import `CommandPopup` 组件
- 新增内部状态：`slashCommands`, `showCommandPopup`, `selectedCommandIdx`, `filteredCommands`
- 新增 `loadSlashCommands()` — 调用 `/api/chat/commands?workingDir=<workspaceRoot>`，由父组件传入 workspaceRoot prop
- watch `modelValue`：以 `/` 开头且无空格时显示弹窗
- `handleEnter()`：弹窗显示且有匹配命令时选中命令（替换输入为 `/cmdname `），否则 emit submit
- 新增 `handleArrowUp/Down/Tab/Escape` 导航
- 模板：在 `<el-input>` 上方渲染 `<CommandPopup>`

#### 3c. 不添加 RAG 召回开关

后端 `WorkbenchExecutionPlanProvider` 经由 common Runtime，当前不支持 recall（会抛异常）。添加 RAG toggle 需要先在 common Runtime 实现 recall 支持，属于独立的后端工作项，不在本次统一范围内。

### 4. 后端 — Workbench 添加斜杠命令展开

**文件**: `src/main/java/com/example/agentweb/app/workbench/run/WorkbenchRunPreparationService.java`

当前：`WorkbenchRunPromptComposer.compose(plan, ..., command.getMessage())` 直接用原始用户输入

改为：
- 注入 `SlashCommandExpander`（Spring bean 已存在）
- 在 `prepare()` 方法中、调用 `compose()` 之前，对 `command.getMessage()` 做斜杠命令展开：
  ```java
  String workspaceRoot = plan.getRepositoryScope().getWorkspaceRoot();
  SlashExpansionResult expansion = commandExpander.expand(workspaceRoot, command.getMessage());
  String expandedInput = expansion.getExpandedPrompt();
  ```
- 将 `expandedInput` 传给 `WorkbenchRunPromptComposer.compose()`
- 原始 `command.getMessage()` 仍由 `WorkbenchRunSubmissionCommitter` 存入 `chat_message`（与 chat 行为一致：存原始、发展开后）

### 5. `Workbench.vue` — 传入 workspaceRoot

**文件**: `frontend/js/pages/Workbench.vue`

向 `<workbench-conversation-panel>` 传入 `:workspace-root` prop（用于斜杠命令加载）。

### 6. 测试

- 前端 E2E (`tests/e2e/`): 确认 workbench 对话中 agent 消息显示 ToolBlock、user 消息显示图片、Enter 发送、斜杠命令弹窗
- 后端测试：`WorkbenchRunPreparationService` 新增 slash command 展开测试
- 前端单测：如 `formatters.ts` 已有覆盖，无需新增

## 不在范围内

- RAG 召回开关（需 common Runtime 支持 recall，独立工作项）
- RecallCard（workbench 无 recall 数据）
- 回退(rewind)按钮（workbench 无此功能）
- 分享/分析评价（workbench 无此功能）
- 实时流传输协议统一（chat 用 stream JSON，workbench 用 RuntimeSemanticEvent，传输层不同）
