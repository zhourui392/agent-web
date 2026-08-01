<template>
  <div class="workbench-conversation-panel">
    <div class="workbench-panel-heading">
      <div>
        <span class="workbench-panel-kicker">阶段对话</span>
        <h3>{{ phaseLabel }}</h3>
      </div>
      <div class="workbench-conversation-heading-actions">
        <el-tag :type="connectionType" effect="plain">{{ connectionLabel }}</el-tag>
        <el-tag v-if="runState?.status" :type="runStatusType">{{ runStatusLabel }}</el-tag>
        <el-button v-if="mobile" text @click="emit('open-document-pane')">打开文档区</el-button>
        <el-button v-else-if="documentCollapsed" text @click="emit('restore-document-pane')">
          恢复文档区
        </el-button>
      </div>
    </div>

    <slot name="review"></slot>

    <el-alert
      v-if="error"
      class="workbench-conversation-alert"
      type="error"
      show-icon
      :closable="false"
      :title="error"
    />
    <el-alert
      v-else-if="notice"
      class="workbench-conversation-alert"
      type="info"
      show-icon
      :closable="false"
      :title="notice"
    />
    <el-alert
      v-if="!handoffReady"
      class="workbench-conversation-alert"
      type="warning"
      show-icon
      :closable="false"
      title="请先在“阶段交接”中预览并接受上游版本；系统不会静默注入最新内容。"
    />
    <el-alert
      v-if="terminalDocumentStale"
      class="workbench-conversation-alert"
      data-test="workbench-terminal-document-stale"
      type="warning"
      show-icon
      :closable="false"
      title="本轮运行已结束，当前打开文件已有更新；请在文档区手动刷新。"
    />

    <div
      ref="timeline"
      v-loading="messagesLoading"
      class="workbench-conversation-timeline"
      data-test="workbench-run-timeline"
      @scroll.passive="handleTimelineScroll"
    >
      <el-button
        v-if="newOutputAvailable"
        class="workbench-new-output"
        type="primary"
        size="small"
        round
        data-test="workbench-new-output"
        @click="scrollToLatest"
      >
        有新输出，回到底部
      </el-button>
      <div v-if="hasOlderMessages" class="workbench-load-older-messages">
        <el-button
          text
          :loading="olderMessagesLoading"
          data-test="workbench-load-older-messages"
          @click="emit('load-older-messages')"
        >
          加载更早消息
        </el-button>
      </div>
      <div v-if="!messagesLoading && !hasTimeline" class="workbench-conversation-empty">
        <span>本阶段还没有可恢复的 Run 输出。</span>
        <small>发送消息后，Agent 文本、工具、命令、文件和测试事件会在这里持续展示。</small>
      </div>

      <article
        v-for="message in visibleMessages"
        :key="message.messageId"
        :class="['workbench-timeline-block', `kind-${message.role}`]"
      >
        <header>
          <span>{{ message.role === 'user' ? '你' : 'Agent' }}</span>
          <small>{{ formatTime(message.timestamp) }}</small>
        </header>
        <p v-if="message.role === 'user'">{{ message.content }}</p>
        <div v-else v-html="renderMarkdown(message.content)"></div>
      </article>

      <template v-if="runState">
        <article
          v-for="block in visibleRunBlocks"
          :key="block.eventId"
          :class="['workbench-timeline-block', `kind-${block.kind}`]"
        >
          <header>
            <span>{{ blockLabel(block.kind) }}</span>
            <small>#{{ block.eventId }}</small>
          </header>
          <p v-if="block.content">{{ block.content }}</p>
          <details v-else>
            <summary>{{ block.commandSummary || block.outputSummary || block.summary || block.tool || block.commandClass || block.eventType }}</summary>
            <p v-if="block.outputSummary" data-test="workbench-command-output-summary">
              {{ block.outputSummary }}
            </p>
            <dl>
              <template v-if="block.repositoryKey"><dt>仓库</dt><dd>{{ block.repositoryKey }}</dd></template>
              <template v-if="block.status"><dt>状态</dt><dd>{{ block.status }}</dd></template>
              <template v-if="block.durationMs != null"><dt>耗时</dt><dd>{{ block.durationMs }} ms</dd></template>
              <template v-if="block.exitCode != null"><dt>退出码</dt><dd>{{ block.exitCode }}</dd></template>
            </dl>
          </details>
        </article>

        <button
          v-for="document in runState.staleDocuments"
          :key="`${document.repositoryKey}:${document.path}:${document.eventId}`"
          type="button"
          class="workbench-file-event"
          @click="emit('open-document', document.repositoryKey, document.path)"
        >
          <span>文件 {{ document.changeType }}</span>
          <strong>{{ document.repositoryKey }}/{{ document.path }}</strong>
          <small>点击在右侧查看；已打开内容只标记 stale，不会自动替换。</small>
        </button>

        <article
          v-for="test in runState.testProgress"
          :key="`${test.eventId}:${test.repositoryKey}:${test.suite}`"
          class="workbench-test-event"
        >
          <header><span>测试 · {{ test.repositoryKey }}</span><el-tag size="small">{{ test.status }}</el-tag></header>
          <strong>{{ test.suite }}</strong>
          <p>{{ test.summary }}</p>
        </article>

        <el-result
          v-if="runState.terminal"
          :icon="terminalIcon"
          :title="terminalLabel"
          :sub-title="runState.terminal.publicMessage || undefined"
        />
      </template>
    </div>

    <slot name="operations"></slot>

    <div class="workbench-composer">
      <div class="workbench-composer-mode">
        <span>本轮模式</span>
        <el-radio-group
          :model-value="runMode"
          size="small"
          :disabled="readOnly || runActive || submitting"
          @update:model-value="updateRunMode"
        >
          <el-radio-button value="DISCUSS_READ_ONLY">只读讨论</el-radio-button>
          <el-radio-button v-if="modifyAllowed" value="MODIFY_WORKSPACE">修改工作区</el-radio-button>
        </el-radio-group>
        <small v-if="runMode === 'DISCUSS_READ_ONLY'" data-test="run-read-only-scope">
          只读模式不授予仓库写入权限。
        </small>
        <small v-else-if="runMode === 'MODIFY_WORKSPACE'" data-test="run-modify-scope">
          本轮允许写入仓库：
          <el-tag
            v-for="repositoryKey in repositoryKeys"
            :key="repositoryKey"
            size="small"
            effect="plain"
          >
            {{ repositoryKey }}
          </el-tag>
        </small>
        <small v-if="phase === 'REVIEW_REFACTOR' && runMode === 'MODIFY_WORKSPACE'">
          必须绑定上方当前 Review Confirmation。
        </small>
      </div>
      <div
        v-if="attachments.length"
        class="workbench-pending-attachments"
        data-test="workbench-pending-attachments"
      >
        <span>待发送文档</span>
        <el-tag
          v-for="attachment in attachments"
          :key="`${attachment.repositoryKey}:${attachment.relativePath}`"
          closable
          :disable-transitions="true"
          @close="emit('remove-attachment', attachment.repositoryKey, attachment.relativePath)"
        >
          {{ attachment.repositoryKey }}/{{ attachment.relativePath }}
        </el-tag>
      </div>
      <el-input
        :model-value="modelValue"
        type="textarea"
        :rows="4"
        :maxlength="16000"
        show-word-limit
        resize="vertical"
        placeholder="输入本阶段问题或任务；普通聊天文字不会授权 commit、push 或部署"
        :disabled="readOnly || submitting"
        data-test="workbench-run-composer"
        @update:model-value="updateText"
        @keydown.ctrl.enter.prevent="emitSubmit"
        @keydown.meta.enter.prevent="emitSubmit"
      />
      <div class="workbench-composer-actions">
        <span v-if="readOnly">已归档，仅可恢复查看历史。</span>
        <span v-else>Ctrl / ⌘ + Enter 发送</span>
        <el-button
          v-if="runActive"
          type="danger"
          plain
          :loading="stopping"
          :disabled="readOnly"
          data-test="workbench-run-stop"
          @click="emit('stop')"
        >
          停止
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          data-test="workbench-run-submit"
          @click="emitSubmit"
        >
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Workbench Phase Run timeline and composer.
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, nextTick, ref, watch } from 'vue';
import { renderMarkdown, formatTime } from '../lib/formatters.js';

const props = defineProps({
  phase: { type: String, required: true },
  phaseLabel: { type: String, required: true },
  modelValue: { type: String, required: true },
  runMode: { type: String, required: true },
  runState: { type: Object, default: null },
  messages: { type: Array, required: true },
  messagesLoading: { type: Boolean, required: true },
  hasOlderMessages: { type: Boolean, required: true },
  olderMessagesLoading: { type: Boolean, required: true },
  repositoryKeys: { type: Array, required: true },
  attachments: { type: Array, required: true },
  connectionStatus: { type: String, required: true },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  submitting: { type: Boolean, required: true },
  stopping: { type: Boolean, required: true },
  readOnly: { type: Boolean, required: true },
  identityReady: { type: Boolean, required: true },
  handoffReady: { type: Boolean, required: true },
  modifyAllowed: { type: Boolean, required: true },
  modifyReady: { type: Boolean, required: true },
  mobile: { type: Boolean, required: true },
  documentCollapsed: { type: Boolean, required: true },
  terminalDocumentStale: { type: Boolean, required: true },
});

const emit = defineEmits([
  'update:modelValue',
  'update:runMode',
  'submit',
  'stop',
  'open-document',
  'open-document-pane',
  'restore-document-pane',
  'remove-attachment',
  'load-older-messages',
]);
const timeline = ref(null);
const newOutputAvailable = ref(false);

const runActive = computed(() => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED']
  .includes(props.runState?.status || ''));
const visibleMessages = computed(() => props.messages);
const persistedAssistantRunIds = computed(() => new Set(
  props.messages
    .filter(message => message.role === 'assistant' && message.runId)
    .map(message => message.runId),
));
const visibleRunBlocks = computed(() => (props.runState?.blocks || []).filter(block => !(
  block.kind === 'agent_chunk'
    && persistedAssistantRunIds.value.has(props.runState?.context.runId || '')
)));
const hasTimeline = computed(() => Boolean(
  props.messages.length || props.runState && (
    visibleRunBlocks.value.length ||
    props.runState.staleDocuments.length ||
    props.runState.testProgress.length ||
    props.runState.terminal
  ),
));
const canSubmit = computed(() =>
  !props.readOnly &&
  props.identityReady &&
  !props.submitting &&
  !runActive.value &&
  props.handoffReady &&
  (props.phase !== 'REVIEW_REFACTOR' || props.runMode !== 'MODIFY_WORKSPACE' || props.modifyReady) &&
  Boolean(props.modelValue.trim()),
);
const connectionLabel = computed(() => ({
  idle: '未连接',
  connecting: '连接中',
  streaming: '实时输出',
  reconnecting: '正在重连',
  closed: '流已关闭',
}[props.connectionStatus] || '流状态未知'));
const connectionType = computed(() => props.connectionStatus === 'streaming'
  ? 'success'
  : props.connectionStatus === 'reconnecting' ? 'warning' : 'info');
const runStatusLabel = computed(() => ({
  PENDING: '等待启动',
  RUNNING: '运行中',
  CANCEL_REQUESTED: '正在停止',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  INTERRUPTED: '已中断',
}[props.runState?.status || ''] || props.runState?.status || ''));
const runStatusType = computed(() => props.runState?.status === 'SUCCEEDED'
  ? 'success'
  : ['FAILED', 'INTERRUPTED'].includes(props.runState?.status || '')
    ? 'danger'
    : ['RUNNING', 'CANCEL_REQUESTED'].includes(props.runState?.status || '') ? 'warning' : 'info');
const terminalIcon = computed(() => props.runState?.terminal?.status === 'SUCCEEDED'
  ? 'success'
  : props.runState?.terminal?.status === 'CANCELLED' ? 'warning' : 'error');
const terminalLabel = computed(() => ({
  SUCCEEDED: '本轮运行成功',
  FAILED: '本轮运行失败',
  CANCELLED: '本轮运行已取消',
  INTERRUPTED: '本轮运行已中断，需要核对',
}[props.runState?.terminal?.status || ''] || '运行已结束'));

function updateText(value) {
  if (typeof value === 'string') emit('update:modelValue', value);
}

function updateRunMode(value) {
  if (value === 'DISCUSS_READ_ONLY' || value === 'MODIFY_WORKSPACE') {
    emit('update:runMode', value);
  }
}

function emitSubmit() {
  if (canSubmit.value) emit('submit');
}

function blockLabel(kind) {
  return {
    agent_chunk: 'Agent',
    tool_started: '工具开始',
    tool_finished: '工具完成',
    command_started: '命令开始',
    command_finished: '命令完成',
    generic: '运行事件',
  }[kind];
}

function nearTimelineBottom(element) {
  return element.scrollHeight - element.scrollTop - element.clientHeight <= 72;
}

function handleTimelineScroll() {
  if (timeline.value && nearTimelineBottom(timeline.value)) {
    newOutputAvailable.value = false;
  }
}

function scrollToLatest() {
  const element = timeline.value;
  if (!element) return;
  element.scrollTop = element.scrollHeight;
  newOutputAvailable.value = false;
}

watch(
  () => props.runState?.lastAppliedEventSeq || 0,
  async (next, previous) => {
    if (next <= previous) return;
    const element = timeline.value;
    const shouldFollow = !element || nearTimelineBottom(element);
    await nextTick();
    if (shouldFollow) scrollToLatest();
    else newOutputAvailable.value = true;
  },
  { flush: 'pre' },
);
</script>
