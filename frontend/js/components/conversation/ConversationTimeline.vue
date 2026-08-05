<!--
  ConversationTimeline — 统一对话时间线

  提供消息列表的统一布局，支持空状态、加载更早消息和新输出提示 slot。
  当前活动 Run 表现为 Timeline 末尾的一条流式 ASSISTANT 消息。

  不读取 SSE，不判断 Workbench Stage，不操作滚动以外的业务状态。

  @author alex
  @since 2026-08-04
-->
<template>
  <div ref="container" class="conversation-timeline" @scroll="onScroll">
    <!-- 加载更早消息 -->
    <div v-if="$slots['load-older']" class="conversation-load-older">
      <slot name="load-older" />
    </div>
    <!-- 空状态 -->
    <div v-if="messages.length === 0 && $slots.empty" class="conversation-empty">
      <slot name="empty" />
    </div>
    <!-- 消息列表 -->
    <ConversationMessage
      v-for="msg in messages"
      :key="msg.messageKey"
      :view="msg"
      @open-document="ref => $emit('open-document', ref)"
    >
      <template v-if="$slots['message-actions']" #actions="{ view }">
        <slot name="message-actions" :view="view" />
      </template>
    </ConversationMessage>
    <!-- 新输出提示 -->
    <div v-if="$slots['new-output']" class="conversation-new-output-hint">
      <slot name="new-output" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import ConversationMessage from './ConversationMessage.vue';
import type { ConversationMessageView } from '../../lib/conversation-message-view.js';

const props = defineProps<{
  messages: ReadonlyArray<ConversationMessageView>;
}>();

const emit = defineEmits<{
  (e: 'scroll-to-bottom'): void;
  (e: 'open-document', ref: { repositoryKey: string; relativePath: string }): void;
}>();

const container = ref<HTMLElement | null>(null);

function onScroll() {
  if (!container.value) return;
  const el = container.value;
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  if (nearBottom) emit('scroll-to-bottom');
}

defineExpose({
  scrollToBottom() {
    if (container.value) container.value.scrollTop = container.value.scrollHeight;
  },
  isNearBottom(): boolean {
    if (!container.value) return true;
    const el = container.value;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  },
});
</script>
