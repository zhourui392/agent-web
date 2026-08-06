<template>
  <div class="workbench-conversation-panel">
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

      <ConversationMessage
        v-for="msg in conversationMessages"
        :key="msg.messageKey"
        :view="msg"
        file-change-data-test="workbench-structured-document-reference"
        @open-document="ref => emit('open-document', ref)"
      />

      <div
        v-if="runState?.terminal"
        :class="['workbench-run-terminal', terminalStatusClass]"
        data-test="workbench-run-terminal"
      >
        {{ terminalLabel }}<template v-if="runState.terminal.publicMessage"> · {{ runState.terminal.publicMessage }}</template>
      </div>
    </div>

    <div
      :class="['workbench-composer', { 'is-file-dragging': fileDragging }]"
      @dragenter.prevent="fileDragging = true"
      @dragover.prevent
      @dragleave="handleAttachmentDragLeave"
      @drop.prevent="handleAttachmentDrop"
    >
      <ConversationComposer
        ref="composerRef"
        :model-value="modelValue"
        placeholder="输入本阶段问题或任务；普通聊天文字不会授权 commit、push 或部署"
        :maximum-length="16000"
        :textarea-rows="3"
        :input-disabled="readOnly || submitting"
        :can-submit="canSubmit"
        :submitting="submitting"
        :run-active="runActive"
        :stopping="stopping"
        :commands="filteredCommands"
        :command-popup-visible="showCommandPopup && filteredCommands.length > 0"
        :selected-command-index="selectedCommandIdx"
        textarea-data-test="workbench-run-composer"
        stop-data-test="workbench-run-stop"
        submit-data-test="workbench-run-submit"
        :stop-disabled="readOnly"
        @update:model-value="updateText"
        @submit="emitSubmit"
        @stop="emit('stop')"
        @paste-files="handlePasteFiles"
        @select-command="selectCommand"
        @arrow-up="handleArrowUp"
        @arrow-down="handleArrowDown"
        @escape="hideCommandPopup"
      >
        <template #attachments>
          <div
            v-if="repositoryAttachments.length"
            class="workbench-pending-attachments"
            data-test="workbench-pending-attachments"
          >
            <span>仓内文档</span>
            <el-tag
              v-for="attachment in repositoryAttachments"
              :key="`${attachment.repositoryKey}:${attachment.relativePath}`"
              closable
              :disable-transitions="true"
              @close="emit('remove-attachment', attachment.repositoryKey, attachment.relativePath)"
            >
              {{ attachment.repositoryKey }}/{{ attachment.relativePath }}
            </el-tag>
          </div>
          <el-alert
            v-if="uploadNotice"
            class="workbench-upload-alert"
            data-test="workbench-upload-notice"
            type="warning"
            show-icon
            :closable="false"
            :title="uploadNotice"
          />
          <div
            v-if="uploadItems.length"
            class="workbench-upload-items"
            data-test="workbench-upload-items"
          >
            <span>浏览器上传</span>
            <article
              v-for="item in uploadItems"
              :key="item.clientId"
              class="workbench-upload-item"
              data-test="workbench-upload-item"
            >
              <img
                v-if="item.previewUrl"
                :src="item.previewUrl"
                :alt="item.displayName"
                class="workbench-upload-preview"
                data-test="workbench-upload-preview"
              />
              <div class="workbench-upload-item-body">
                <strong :title="item.displayName">{{ item.displayName }}</strong>
                <small>{{ formatAttachmentSize(item.size) }} · {{ uploadStatusLabel(item.status) }}</small>
                <small v-if="item.error" class="workbench-upload-error">{{ item.error }}</small>
              </div>
              <el-button
                v-if="item.status === 'FAILED'"
                link
                type="primary"
                data-test="workbench-upload-retry"
                :disabled="attachmentInteractionDisabled"
                @click="emit('retry-upload', item.clientId)"
              >
                重试
              </el-button>
              <el-button
                link
                type="danger"
                data-test="workbench-upload-remove"
                :loading="item.status === 'REMOVING'"
                :disabled="readOnly || item.status === 'REMOVING'"
                @click="emit('remove-upload', item.clientId)"
              >
                {{ item.status === 'UPLOADING' ? '取消' : '移除' }}
              </el-button>
            </article>
          </div>
        </template>
        <template #left-actions>
          <span v-if="readOnly">已归档，仅可恢复查看历史。</span>
          <template v-else>
            <el-button
              size="small"
              plain
              data-test="workbench-start-new-context"
              :disabled="runActive || submitting"
              @click="emit('start-new-context')"
            >
              清空上下文
            </el-button>
            <input
              ref="imagePicker"
              type="file"
              accept=".png,.jpg,.jpeg,.gif,.webp,image/png,image/jpeg,image/gif,image/webp"
              multiple
              hidden
              data-test="workbench-upload-image-input"
              @change="handleImageSelection"
            />
            <input
              ref="filePicker"
              type="file"
              :accept="acceptedAttachmentTypes"
              multiple
              hidden
              data-test="workbench-upload-file-input"
              @change="handleFileSelection"
            />
            <el-button
              size="small"
              plain
              data-test="workbench-upload-image-button"
              :disabled="attachmentInteractionDisabled || attachmentCapacityReached"
              @click="imagePicker?.click()"
            >
              选择图片
            </el-button>
            <el-button
              size="small"
              plain
              data-test="workbench-upload-file-button"
              :disabled="attachmentInteractionDisabled || attachmentCapacityReached"
              @click="filePicker?.click()"
            >
              选择附件
            </el-button>
          </template>
        </template>
      </ConversationComposer>
    </div>
  </div>
</template>

<script setup>
/**
 * Workbench Stage Run timeline and composer.
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, nextTick, ref, watch } from 'vue';
import {
  isStreamJson,
  parseStreamJson,
  parseUserMessage,
} from '../lib/formatters.js';
import {
  authorizedDocumentReference,
  extractAuthorizedAgentDocumentReferences,
} from '../lib/workbench-document-state.js';
import ConversationMessage from './conversation/ConversationMessage.vue';
import ConversationComposer from './conversation/ConversationComposer.vue';
import {
  toMessageRole,
  persistedMessageKey,
} from '../lib/conversation-message-view.js';
import { useSlashCommandInteraction } from '../composables/useSlashCommandInteraction.ts';

const props = defineProps({
  stageInstanceIdentifier: { type: String, required: true },
  stageLabel: { type: String, required: true },
  workbenchId: { type: String, default: '' },
  modelValue: { type: String, required: true },
  runState: { type: Object, default: null },
  messages: { type: Array, required: true },
  messagesLoading: { type: Boolean, required: true },
  hasOlderMessages: { type: Boolean, required: true },
  olderMessagesLoading: { type: Boolean, required: true },
  repositoryKeys: { type: Array, required: true },
  attachments: { type: Array, required: true },
  uploadItems: { type: Array, default: () => [] },
  uploadNotice: { type: String, default: null },
  error: { type: String, default: null },
  notice: { type: String, default: null },
  allowedRunModes: { type: Array, required: true },
  selectedRunMode: { type: String, default: null },
  submitting: { type: Boolean, required: true },
  stopping: { type: Boolean, required: true },
  readOnly: { type: Boolean, required: true },
  identityReady: { type: Boolean, required: true },
  terminalDocumentStale: { type: Boolean, required: true },
  workspaceRoot: { type: String, default: '' },
});

const emit = defineEmits([
  'update:modelValue',
  'submit',
  'stop',
  'open-document',
  'remove-attachment',
  'upload-files',
  'retry-upload',
  'remove-upload',
  'load-older-messages',
  'start-new-context',
]);
const timeline = ref(null);
const imagePicker = ref(null);
const filePicker = ref(null);
const fileDragging = ref(false);
const newOutputAvailable = ref(false);
const composerRef = ref(null);

const acceptedAttachmentTypes = [
  '.png', '.jpg', '.jpeg', '.gif', '.webp', '.pdf',
  '.txt', '.log', '.md', '.markdown', '.json', '.xml', '.csv', '.yaml', '.yml', '.toml',
  '.java', '.kt', '.kts', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.vue', '.py', '.go', '.rs',
  '.c', '.h', '.cc', '.cpp', '.cxx', '.hpp', '.sql', '.properties',
].join(',');

// ===== 斜杠命令交互（共享 composable） =====
const userInputWritable = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
});
const {
  slashCommands, showCommandPopup, selectedCommandIdx, filteredCommands,
  loadSlashCommands, selectCommand, handleArrowUp, handleArrowDown, hideCommandPopup,
} = useSlashCommandInteraction({
  userInput: userInputWritable,
  loadCommands: async () => {
    if (!props.workbenchId || !props.stageInstanceIdentifier) return [];
    const cmds = await fetch(
      '/api/workbenches/' + encodeURIComponent(props.workbenchId)
      + '/stages/' + encodeURIComponent(props.stageInstanceIdentifier)
      + '/commands',
    ).then(r => r.json());
    return cmds;
  },
  onSubmit: () => { if (canSubmit.value) emit('submit'); },
  focusTextarea: () => { composerRef.value?.focus?.(); },
});

const runActive = computed(() => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED']
  .includes(props.runState?.status || ''));
const repositoryAttachments = computed(() => props.attachments.filter(
  attachment => attachment.type !== 'UPLOADED_CONVERSATION',
));
const uploadsBusy = computed(() => props.uploadItems.some(
  item => item.status === 'UPLOADING' || item.status === 'REMOVING',
));
const attachmentInteractionDisabled = computed(() =>
  props.readOnly || props.submitting || runActive.value);
const attachmentCapacityReached = computed(() => {
  const uploading = props.uploadItems.filter(item => item.status === 'UPLOADING').length;
  return props.attachments.length + uploading >= 8;
});
const visibleMessages = computed(() => props.messages.map(message => {
  const documentReferences = message.role === 'assistant'
    ? extractAuthorizedAgentDocumentReferences(message.content, props.repositoryKeys)
    : [];
  if (message.role === 'user') {
    const parsed = parseUserMessage(message.content);
    return { ...message, bodyText: parsed.text, images: parsed.images, documentReferences };
  }
  const segments = isStreamJson(message.content)
    ? parseStreamJson(message.content)
    : [{ type: 'text', content: message.content }];
  return { ...message, segments, documentReferences };
}));
const conversationMessages = computed(() => {
  const msgs = visibleMessages.value.map((message, index) => {
    const role = toMessageRole(message.role);
    const messageKey = message.messageId != null
      ? persistedMessageKey(message.messageId)
      : 'wb-tmp-' + index;
    return {
      messageKey,
      persistedMessageId: message.messageId ?? null,
      role,
      bodyText: message.bodyText || message.text || '',
      images: message.images || [],
      segments: message.segments || [],
      createdAt: message.timestamp || null,
      recall: null,
      documentReferences: message.documentReferences || [],
      streaming: false,
    };
  });
  if (streamingRunMessage.value) {
    msgs.push(streamingRunMessage.value);
  }
  return msgs;
});
const persistedAssistantRunIds = computed(() => new Set(
  props.messages
    .filter(message => message.role === 'assistant' && message.runId)
    .map(message => message.runId),
));
const visibleRunBlocks = computed(() => (props.runState?.blocks || [])
  .filter(block => {
    if (block.kind === 'agent_chunk'
        && persistedAssistantRunIds.value.has(props.runState?.context.runId || '')) {
      return false;
    }
    // 运行结束后已持久化助手消息时，tool 块也不再展示，避免结论后堆叠 CLI 调用
    if (block.kind === 'tool'
        && props.runState?.terminal
        && persistedAssistantRunIds.value.has(props.runState?.context.runId || '')) {
      return false;
    }
    return true;
  })
  .map(block => ({
    ...block,
    documentReferences: block.kind === 'agent_chunk'
      ? extractAuthorizedAgentDocumentReferences(block.content, props.repositoryKeys)
      : [],
  })));
// 结构化事件和显式反引号候选都必须携带已选 repositoryKey；绝不从模糊文本猜仓库。
const visibleDocumentEvents = computed(() => (props.runState?.staleDocuments || [])
  .map(document => ({
    ...document,
    reference: authorizedDocumentReference({
      repositoryKey: document.repositoryKey,
      relativePath: document.path,
    }, props.repositoryKeys),
  }))
  .filter(document => document.reference != null));
const streamingRunMessage = computed(() => {
  if (!props.runState) return null;
  const runId = props.runState?.context?.runId || 'current';
  const segments = [];
  const allDocumentReferences = [];
  for (const block of visibleRunBlocks.value) {
    if (block.kind === 'agent_chunk' && block.content) {
      segments.push({ type: 'text', content: block.content });
      if (block.documentReferences.length) {
        allDocumentReferences.push(...block.documentReferences);
      }
    } else if (block.kind === 'tool') {
      segments.push({
        type: 'tool',
        content: commandExecutionContent(block)
          || block.commandSummary || block.outputSummary || '',
        toolName: block.tool || block.commandClass || 'tool',
        status: block.status,
        durationMs: block.durationMs,
        commandSummary: block.commandSummary,
        outputSummary: block.outputSummary,
        repositoryKey: block.repositoryKey,
        commandClass: block.commandClass,
        exitCode: block.exitCode,
      });
    }
  }
  for (const doc of visibleDocumentEvents.value) {
    segments.push({
      type: 'file_change',
      content: '',
      relativePath: doc.reference.relativePath,
      repositoryKey: doc.reference.repositoryKey,
      changeType: doc.changeType,
    });
  }
  for (const test of props.runState.testProgress || []) {
    segments.push({
      type: 'test_progress',
      content: '',
      suiteName: test.suite,
      repositoryKey: test.repositoryKey,
      testStatus: test.status,
      summary: test.summary,
    });
  }
  if (segments.length === 0 && (!runActive.value || props.runState?.terminal)) return null;
  return {
    messageKey: 'run-' + runId + '-streaming',
    persistedMessageId: null,
    role: 'ASSISTANT',
    bodyText: '',
    images: [],
    segments,
    createdAt: null,
    recall: null,
    documentReferences: allDocumentReferences,
    streaming: runActive.value && !props.runState?.terminal,
  };
});

function commandExecutionContent(block) {
  return block.outputContent || '';
}

const hasTimeline = computed(() => Boolean(
  conversationMessages.value.length > 0 || props.runState?.terminal,
));
const canSubmit = computed(() =>
  !props.readOnly &&
  props.identityReady &&
  props.allowedRunModes.includes(props.selectedRunMode) &&
  !props.submitting &&
  !uploadsBusy.value &&
  !runActive.value &&
  Boolean(props.modelValue.trim()),
);
const terminalStatusClass = computed(() => {
  const status = props.runState?.terminal?.status;
  if (status === 'SUCCEEDED') return 'terminal-success';
  if (status === 'CANCELLED') return 'terminal-warning';
  return 'terminal-error';
});
const terminalLabel = computed(() => ({
  SUCCEEDED: '本轮运行成功',
  FAILED: '本轮运行失败',
  CANCELLED: '本轮运行已取消',
  INTERRUPTED: '本轮运行已中断，需要核对',
}[props.runState?.terminal?.status || ''] || '运行已结束'));

function updateText(value) {
  if (typeof value === 'string') emit('update:modelValue', value);
}

function emitSubmit() {
  if (canSubmit.value) emit('submit');
}

function handleImageSelection(event) {
  emitSelectedFiles(event, file => file.type.startsWith('image/'));
}

function handleFileSelection(event) {
  emitSelectedFiles(event, () => true);
}

function emitSelectedFiles(event, predicate) {
  const input = event?.target;
  const files = Array.from(input?.files || []).filter(predicate);
  if (input) input.value = '';
  if (files.length && !attachmentInteractionDisabled.value) emit('upload-files', files);
}

function handlePasteFiles(files) {
  if (attachmentInteractionDisabled.value) return;
  emit('upload-files', files);
}

function handleAttachmentDrop(event) {
  fileDragging.value = false;
  if (attachmentInteractionDisabled.value) return;
  const files = Array.from(event?.dataTransfer?.files || []);
  if (files.length) emit('upload-files', files);
}

function handleAttachmentDragLeave(event) {
  const current = event?.currentTarget;
  const related = event?.relatedTarget;
  if (!current || !related || !current.contains(related)) fileDragging.value = false;
}

function formatAttachmentSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function uploadStatusLabel(status) {
  return {
    UPLOADING: '上传中',
    AVAILABLE: '待发送',
    FAILED: '上传失败',
    REMOVING: '正在移除',
  }[status] || '状态未知';
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

watch(() => props.modelValue, (val) => {
  if (val && val.startsWith('/') && val.indexOf(' ') < 0 && slashCommands.value.length > 0) {
    showCommandPopup.value = true;
    selectedCommandIdx.value = 0;
  } else {
    showCommandPopup.value = false;
  }
});

watch(() => [props.workbenchId, props.stageInstanceIdentifier], () => {
  loadSlashCommands();
}, { immediate: true });

watch(() => props.messages.length, async () => {
  const element = timeline.value;
  if (!element) return;
  const shouldFollow = nearTimelineBottom(element);
  await nextTick();
  if (shouldFollow) scrollToLatest();
});
</script>
