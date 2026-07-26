<template>
  <div class="chat-message">
    <!-- 用户消息 -->
    <div v-if="msg.role === 'user'" class="user-row" style="display: flex; justify-content: flex-end; align-items: center; gap: 6px;">
      <button v-if="msg.id != null" class="rewind-btn" type="button"
              title="从这里重开 (删除此条及之后, 清空 resumeId, 回填输入框)"
              @click="$emit('rewind', msg, index)">↩</button>
      <div class="message-user">
        <div v-if="msg.bodyText" class="message-user-text">{{ msg.bodyText }}</div>
        <div v-if="msg.images && msg.images.length" class="message-image-grid">
          <el-image
            v-for="(img, ii) in msg.images"
            :key="ii"
            :src="imageUrl(img)"
            :preview-src-list="msg.images.map(imageUrl)"
            :initial-index="ii"
            fit="cover"
            hide-on-click-modal
            preview-teleported
            class="chat-image">
            <template #error>
              <div class="chat-image-broken">图片不可用</div>
            </template>
          </el-image>
        </div>
      </div>
    </div>
    <!-- Agent 消息 -->
    <div v-else-if="msg.role === 'agent'">
      <div class="message-agent">
        <RecallCard :recall="msg.recall" />
        <template v-for="(seg, si) in (msg.segments || [])" :key="si">
          <div v-if="seg.type === 'text'" class="text-segment-wrap">
            <button class="copy-btn" type="button" title="复制 Markdown" @click="copySegment(seg.content)">📋</button>
            <div class="text-segment md-body" v-html="renderMarkdown(seg.content)"></div>
          </div>
          <ToolBlock v-else :segment="seg" :expanded="isToolExpanded(index, si)" @toggle="toggleTool(index, si)" />
        </template>
        <div v-if="sending && reconnecting && isLast" class="message-system">连接中断，正在恢复...</div>
        <div v-if="sending && isLast" class="loading-dots"><span></span><span></span><span></span></div>
      </div>
    </div>
    <!-- 系统消息 -->
    <div v-else-if="msg.role === 'system'">
      <div class="message-system">{{ msg.text }}</div>
    </div>
    <!-- 错误消息 -->
    <div v-else-if="msg.role === 'error'">
      <div class="message-error">{{ msg.text }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { renderMarkdown, imageUrl } from '../lib/formatters.js';
import { copySegment } from '../lib/clipboard.js';
import RecallCard from './RecallCard.vue';
import ToolBlock from './ToolBlock.vue';

interface ChatMessage {
  id: number | null;
  role: string;
  text?: string;
  bodyText?: string;
  images?: string[];
  segments?: { type: string; name?: string; content: string }[];
  recall?: { hits: { title: string; conclusion?: string }[]; query?: string; recallOpen: boolean } | null;
  [key: string]: unknown;
}

defineProps<{
  msg: ChatMessage;
  index: number;
  isLast: boolean;
  sending: boolean;
  reconnecting: boolean;
  isToolExpanded: (msgIndex: number, segIndex: number) => boolean;
  toggleTool: (msgIndex: number, segIndex: number) => void;
}>();

defineEmits<{ (e: 'rewind', msg: ChatMessage, index: number): void }>();
</script>