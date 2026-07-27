<template>
  <div class="chat-input-area" style="position: relative;">
    <CommandPopup
      v-if="showCommandPopup && filteredCommands.length > 0"
      :commands="filteredCommands"
      :selectedIdx="selectedCommandIdx"
      @select="selectCommand"
    />
    <PendingImageList :images="pendingImages" @remove="removePendingImage" />
    <div v-if="pendingFile" class="pending-file-card">
      <el-icon><document /></el-icon>
      <span class="pending-file-name" :title="pendingFile.name">{{ pendingFile.name }}</span>
      <span class="pending-file-size">{{ formatChatFileSize(pendingFile.size) }}</span>
      <span class="pending-file-remove" @click="removePendingFile">×</span>
    </div>
    <el-input
      v-model="userInput"
      type="textarea"
      :rows="3"
      placeholder="输入你的问题，例如：traceId: xxx，问题描述"
      :disabled="!workingDir" @keydown.enter.exact.prevent="handleEnter" @keydown.up.prevent="handleArrowUp" @keydown.down.prevent="handleArrowDown" @keydown.tab.prevent="handleTab"
      @keydown.escape="hideCommandPopup"
      @keydown.ctrl.enter.exact.prevent="insertNewline"
      @paste="handlePaste"
    ></el-input>
    <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px;">
      <div style="display: flex; align-items: center; gap: 8px;">
        <span v-if="workingDir" class="hidden-mobile" style="color: #909399; font-size: 13px;">Enter 发送 | Ctrl+Enter 换行</span>
        <span v-if="!workingDir" style="color: #E6A23C; font-weight: bold; font-size: 13px;">⚠ 请先选择工作目录</span>
        <el-button size="small" :disabled="!sessionId" plain @click="clearContext">
          <el-icon><delete /></el-icon>
          <span>清除上下文</span>
        </el-button>
        <el-upload
          :http-request="uploadChatImage"
          name="file"
          accept="image/*"
          :show-file-list="false"
          :before-upload="beforeChatImageUpload"
          multiple>
          <el-button size="small" :disabled="!workingDir || pendingImages.length >= maxImagesPerMessage" plain>
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
          <el-button size="small" :disabled="!workingDir || !!pendingFile" plain>
            <el-icon><document /></el-icon>
            <span>{{ pendingFile ? '附件已选' : '附件 (≤5MB)' }}</span>
          </el-button>
        </el-upload>
        <el-switch
v-if="ragEnabled" v-model="ragRecall" size="small"
                     active-text="RAG召回" inline-prompt
                     title="开启后每条消息自动召回历史参考拼到提问中"></el-switch>
      </div>
      <div style="display: flex; gap: 8px;">
        <el-button v-if="sending" type="danger" plain @click="stopSession">
          <el-icon><video-pause /></el-icon>
          <span>停止</span>
        </el-button>
        <el-button type="primary" :loading="sending" :disabled="!workingDir || !userInput.trim()" @click="sendMessageStream">
          <el-icon><connection /></el-icon>
          <span>发送</span>
        </el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import { Delete, Upload, Document, VideoPause, Connection } from '@element-plus/icons-vue';
import CommandPopup from './CommandPopup.vue';
import PendingImageList from './PendingImageList.vue';

export default {
  name: 'InputArea',
  components: { CommandPopup, PendingImageList },
  setup() {
    const s = inject('inputAreaState');
    const a = inject('inputAreaActions');
    // inject 返回普通对象,ref 在模板中不自动解包;解构到顶层使 Vue 模板正确处理 ref
    return {
      // state (refs/computed — 模板自动解包)
      userInput: s.userInput, sending: s.sending, sessionId: s.sessionId, ragRecall: s.ragRecall,
      workingDir: s.workingDir, ragEnabled: s.ragEnabled,
      pendingImages: s.pendingImages, pendingFile: s.pendingFile, maxImagesPerMessage: s.maxImagesPerMessage,
      showCommandPopup: s.showCommandPopup, filteredCommands: s.filteredCommands, selectedCommandIdx: s.selectedCommandIdx,
      // actions (普通函数)
      handleEnter: a.handleEnter, handleArrowUp: a.handleArrowUp, handleArrowDown: a.handleArrowDown,
      handleTab: a.handleTab, selectCommand: a.selectCommand, hideCommandPopup: a.hideCommandPopup,
      insertNewline: a.insertNewline, handlePaste: a.handlePaste, clearContext: a.clearContext,
      stopSession: a.stopSession, sendMessageStream: a.sendMessageStream,
      uploadChatImage: a.uploadChatImage, beforeChatImageUpload: a.beforeChatImageUpload, removePendingImage: a.removePendingImage,
      uploadChatFile: a.uploadChatFile, beforeChatFileUpload: a.beforeChatFileUpload, removePendingFile: a.removePendingFile,
      formatChatFileSize: a.formatChatFileSize,
    };
  },
};
</script>