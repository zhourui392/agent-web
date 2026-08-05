<!--
  ConversationAttachmentList — 共享附件展示组件

  只展示标准化附件状态。上传、重试、取消、移除由各端 adapter 提供。
  不包含 storageKey、绝对路径或上传端点。

  @author alex
  @since 2026-08-04
-->
<template>
  <div v-if="attachments.length" class="conversation-attachment-list">
    <article
      v-for="attachment in attachments"
      :key="attachment.attachmentKey"
      class="conversation-attachment-item"
      :data-test="dataTestPrefix + '-item'"
    >
      <img
        v-if="attachment.previewUrl"
        :src="attachment.previewUrl"
        :alt="attachment.displayName"
        class="conversation-attachment-preview"
        :data-test="dataTestPrefix + '-preview'"
      />
      <div class="conversation-attachment-body">
        <strong :title="attachment.displayName">{{ attachment.displayName }}</strong>
        <small>{{ formatSize(attachment.size) }} · {{ statusLabel(attachment.status) }}</small>
        <small v-if="attachment.errorMessage" class="conversation-attachment-error">
          {{ attachment.errorMessage }}
        </small>
      </div>
      <el-button
        v-if="attachment.retryable"
        link
        type="primary"
        :data-test="dataTestPrefix + '-retry'"
        @click="$emit('retry', attachment.attachmentKey)"
      >
        重试
      </el-button>
      <el-button
        v-if="attachment.removable"
        link
        type="danger"
        :loading="attachment.status === 'REMOVING'"
        :disabled="attachment.status === 'REMOVING'"
        :data-test="dataTestPrefix + '-remove'"
        @click="$emit('remove', attachment.attachmentKey)"
      >
        {{ attachment.status === 'UPLOADING' ? '取消' : '移除' }}
      </el-button>
    </article>
  </div>
</template>

<script setup lang="ts">
import type { ConversationAttachmentView, ConversationAttachmentStatus } from '../../lib/conversation-attachment-view.js';

defineProps<{
  attachments: ReadonlyArray<ConversationAttachmentView>;
  dataTestPrefix?: string;
}>();

defineEmits<{
  (e: 'retry', attachmentKey: string): void;
  (e: 'remove', attachmentKey: string): void;
}>();

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function statusLabel(status: ConversationAttachmentStatus): string {
  return {
    UPLOADING: '上传中',
    AVAILABLE: '待发送',
    FAILED: '上传失败',
    REMOVING: '正在移除',
  }[status] || '状态未知';
}
</script>