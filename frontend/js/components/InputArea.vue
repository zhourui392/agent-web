<template>
  <ConversationComposer
    :model-value="userInput"
    placeholder="输入你的问题，例如：traceId: xxx，问题描述"
    :maximum-length="8000"
    :textarea-rows="3"
    :input-disabled="!workingDir || !runtimeAvailable"
    :can-submit="!!workingDir && !!runtimeAvailable && !!userInput.trim()"
    :submitting="sending"
    :run-active="sending"
    :stopping="false"
    :commands="filteredCommands"
    :command-popup-visible="showCommandPopup"
    :selected-command-index="selectedCommandIdx"
    textarea-data-test="chat-input-textarea"
    stop-data-test="chat-stop"
    submit-data-test="chat-submit"
    @update:model-value="val => (userInput = val)"
    @submit="sendMessageStream"
    @stop="stopSession"
    @paste-files="handlePasteFiles"
    @select-command="selectCommand"
    @arrow-up="handleArrowUp"
    @arrow-down="handleArrowDown"
    @escape="hideCommandPopup"
  >
    <template #attachments>
      <PendingImageList :images="pendingImages" @remove="removePendingImage" />
      <div v-if="pendingFile" class="pending-file-card">
        <el-icon><document /></el-icon>
        <span class="pending-file-name" :title="pendingFile.name">{{ pendingFile.name }}</span>
        <span class="pending-file-size">{{ formatChatFileSize(pendingFile.size) }}</span>
        <span class="pending-file-remove" @click="removePendingFile">×</span>
      </div>
    </template>
    <template #left-actions>
      <span v-if="workingDir && runtimeAvailable" class="hidden-mobile" style="color: #909399; font-size: 13px;">Enter 发送 | Ctrl+Enter 换行</span>
      <span v-if="!workingDir" style="color: #E6A23C; font-weight: bold; font-size: 13px;">⚠ 请先选择工作目录</span>
      <span v-else-if="!runtimeAvailable" style="color: #E6A23C; font-weight: bold; font-size: 13px;">⚠ 当前 Agent 不可用，暂时不能继续发送</span>
      <el-button size="small" :disabled="!sessionId || !runtimeAvailable" plain data-test="chat-start-new-context" @click="clearContext">
        <el-icon><delete /></el-icon>
        <span>开始新对话上下文</span>
      </el-button>
      <el-upload
        :http-request="uploadChatImage"
        name="file"
        accept="image/*"
        :show-file-list="false"
        :before-upload="beforeChatImageUpload"
        multiple>
        <el-button size="small" :disabled="!workingDir || !runtimeAvailable || pendingImages.length >= maxImagesPerMessage" plain>
          <el-icon><upload /></el-icon>
          <span>图片 ({{ pendingImages.length }}/{{ maxImagesPerMessage }})</span>
        </el-button>
      </el-upload>
      <el-upload
        :http-request="uploadChatFile"
        name="file"
        accept=".log,.txt,.json,.csv,.md,.yaml,.yml,.xml,.properties,.stacktrace,.out,.conf,.ini"
        :show-file-list="false"
        :before-upload="beforeChatFileUpload">
        <el-button size="small" :disabled="!workingDir || !runtimeAvailable || !!pendingFile" plain>
          <el-icon><document /></el-icon>
          <span>{{ pendingFile ? '附件已选' : '附件 (≤5MB)' }}</span>
        </el-button>
      </el-upload>
      <el-switch
        v-if="ragEnabled" v-model="ragRecall" size="small"
        active-text="RAG召回" inline-prompt
        title="开启后每条消息自动召回历史参考拼到提问中"></el-switch>
    </template>
  </ConversationComposer>
</template>

<script>
import { inject } from 'vue';
import { Delete, Upload, Document } from '@element-plus/icons-vue';
import PendingImageList from './PendingImageList.vue';
import ConversationComposer from './conversation/ConversationComposer.vue';

export default {
  name: 'InputArea',
  components: { ConversationComposer, PendingImageList },
  setup() {
    const s = inject('inputAreaState');
    const a = inject('inputAreaActions');
    return {
      // state (refs/computed — 模板自动解包)
      userInput: s.userInput, sending: s.sending, sessionId: s.sessionId, ragRecall: s.ragRecall,
      workingDir: s.workingDir, ragEnabled: s.ragEnabled,
      runtimeAvailable: s.runtimeAvailable,
      pendingImages: s.pendingImages, pendingFile: s.pendingFile, maxImagesPerMessage: s.maxImagesPerMessage,
      showCommandPopup: s.showCommandPopup, filteredCommands: s.filteredCommands, selectedCommandIdx: s.selectedCommandIdx,
      // actions (普通函数)
      handleArrowUp: a.handleArrowUp, handleArrowDown: a.handleArrowDown,
      selectCommand: a.selectCommand, hideCommandPopup: a.hideCommandPopup,
      handlePaste: a.handlePaste, clearContext: a.clearContext,
      stopSession: a.stopSession, sendMessageStream: a.sendMessageStream,
      uploadChatImage: a.uploadChatImage, beforeChatImageUpload: a.beforeChatImageUpload, removePendingImage: a.removePendingImage,
      uploadChatFile: a.uploadChatFile, beforeChatFileUpload: a.beforeChatFileUpload, removePendingFile: a.removePendingFile,
      formatChatFileSize: a.formatChatFileSize,
      // paste files handler
      handlePasteFiles: (files) => {
        if (files && files.length > 0) {
          a.handlePaste({ clipboardData: { files: files } });
        }
      },
    };
  },
};
</script>
