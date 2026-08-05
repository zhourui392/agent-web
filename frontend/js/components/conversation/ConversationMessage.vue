<!--
  ConversationMessage — 共享消息渲染组件

  渲染 USER / ASSISTANT / SYSTEM / ERROR 四种角色消息，
  支持 text / tool / file_change / mcp_tool_call / test_progress segment。
  通过 header 和 actions slot 扩展领域专属内容。

  不依赖 Chat 或 Workbench 业务状态，只消费 ConversationMessageView 合同。

  @author alex
  @since 2026-08-04
-->
<template>
  <div class="conversation-message">
    <!-- 用户消息 -->
    <div v-if="view.role === 'USER'" class="user-row" style="display: flex; justify-content: flex-end; align-items: center; gap: 6px;">
      <slot name="actions" :view="view" />
      <div class="message-user">
        <div v-if="view.bodyText" class="message-user-text">{{ view.bodyText }}</div>
        <div v-if="view.images && view.images.length" class="message-image-grid">
          <el-image
            v-for="(img, ii) in view.images"
            :key="ii"
            :src="imageUrl(img)"
            :preview-src-list="view.images.map(imageUrl)"
            :initial-index="ii"
            fit="cover"
            hide-on-click-modal
            preview-teleported
            class="chat-image"
          >
            <template #error>
              <div class="chat-image-broken">图片不可用</div>
            </template>
          </el-image>
        </div>
      </div>
    </div>
    <!-- Agent 消息 -->
    <div v-else-if="view.role === 'ASSISTANT'">
      <div class="message-agent">
        <slot name="header" :view="view" />
        <RecallCard v-if="view.recall" :recall="view.recall" />
        <template v-for="(seg, si) in view.segments" :key="messageKey + '-' + si">
          <div v-if="seg.type === 'text'" class="text-segment-wrap">
            <button class="copy-btn" type="button" title="复制 Markdown" @click="copySegment(seg.content)">📋</button>
            <div class="text-segment md-body" v-html="renderMarkdown(seg.content)"></div>
          </div>
          <ToolBlock
            v-else-if="seg.type === 'tool' || seg.type === 'mcp_tool_call'"
            :segment="toToolSegment(seg)"
            :expanded="isToolExpanded(si)"
            :status="seg.status"
            :duration-ms="seg.durationMs"
            :command-summary="seg.commandSummary"
            :output-summary="seg.outputSummary"
            :repository-key="seg.repositoryKey"
            :command-class="seg.commandClass"
            :exit-code="seg.exitCode"
            @toggle="toggleTool(si)"
          />
          <article
            v-else-if="seg.type === 'file_change'"
            class="conversation-file-change"
            :data-test="fileChangeDataTest"
            :role="seg.repositoryKey ? 'button' : undefined"
            :tabindex="seg.repositoryKey ? 0 : undefined"
            @click="seg.repositoryKey ? $emit('open-document', { repositoryKey: seg.repositoryKey, relativePath: seg.relativePath || '' }) : undefined"
          >
            <span class="conversation-file-icon">{{ fileChangeIcon(seg.changeType) }}</span>
            <span v-if="seg.repositoryKey" class="conversation-file-repo">{{ seg.repositoryKey }}/</span>
            <span class="conversation-file-path">{{ seg.relativePath || '' }}</span>
            <small v-if="seg.repositoryKey" class="conversation-file-hint">点击查看</small>
          </article>
          <article
            v-else-if="seg.type === 'test_progress'"
            class="conversation-test-progress"
            data-test="workbench-live-test-event"
          >
            <header v-if="seg.suiteName || seg.testStatus || seg.repositoryKey">
              <span v-if="seg.repositoryKey">测试 · {{ seg.repositoryKey }}</span>
              <strong v-if="seg.suiteName">{{ seg.suiteName }}</strong>
              <span v-if="seg.testStatus">{{ seg.testStatus }}</span>
            </header>
            <p v-if="seg.summary">{{ seg.summary }}</p>
          </article>
        </template>
        <!-- 文档引用 -->
        <div v-if="view.documentReferences && view.documentReferences.length" class="conversation-document-references">
          <el-button
            v-for="ref in view.documentReferences"
            :key="ref.repositoryKey + '/' + ref.relativePath"
            size="small"
            type="primary"
            link
            @click="$emit('open-document', ref)"
          >{{ ref.repositoryKey }}/{{ ref.relativePath }}</el-button>
        </div>
        <!-- 流式加载指示 -->
        <div v-if="view.streaming" class="loading-dots"><span></span><span></span><span></span></div>
        <slot name="actions" :view="view" />
      </div>
    </div>
    <!-- 系统消息 -->
    <div v-else-if="view.role === 'SYSTEM'">
      <div class="message-system">{{ view.bodyText }}</div>
    </div>
    <!-- 错误消息 -->
    <div v-else-if="view.role === 'ERROR'">
      <div class="message-error">{{ view.bodyText }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue';
import { renderMarkdown, imageUrl } from '../../lib/formatters.js';
import { copySegment } from '../../lib/clipboard.js';
import RecallCard from '../RecallCard.vue';
import ToolBlock from '../ToolBlock.vue';
import type { ConversationMessageView, ConversationSegmentView } from '../../lib/conversation-message-view.js';

const props = defineProps<{
  view: ConversationMessageView;
  fileChangeDataTest?: string;
}>();

defineEmits<{
  (e: 'open-document', ref: { repositoryKey: string; relativePath: string }): void;
}>();

const messageKey = props.view.messageKey;
const toolStates: Record<string, boolean> = reactive({});

function isToolExpanded(segIndex: number): boolean {
  const key = String(segIndex);
  return key in toolStates ? toolStates[key] : false;
}

function toggleTool(segIndex: number): void {
  const key = String(segIndex);
  toolStates[key] = !(toolStates[key] === true);
}

function toToolSegment(seg: ConversationSegmentView) {
  return {
    type: seg.type,
    name: seg.toolName || seg.type,
    content: seg.content || '',
  };
}

function fileChangeIcon(changeType?: string): string {
  switch (changeType) {
    case 'CREATE': return '✨';
    case 'DELETE': return '🗑';
    default: return '✏';
  }
}
</script>
