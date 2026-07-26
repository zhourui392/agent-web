/*
 * ChatPanel: 可复用的「一次对话」全局组件(window.ChatPanel)。
 *
 * 承载完整聊天闭环:可恢复 SSE 收流 / 斜杠命令 / 图片附件 / RAG 召回开关 / 停止 / 断连重连 /
 * 消息回退 / 清空上下文 / 分析评价。主控台(index)与管理台(admin)共享同一份实现,
 * admin 内嵌本组件即可闭环 continue-as-chat 续聊。
 *
 * ⚠️ 关键:currentES 与 ChatRun 恢复状态全部放在 setup() 闭包中，保证组件实例之间互不干扰。
 *
 * 与宿主的契约见 §4.2:workingDir/agentType 等作 props 传入;新建会话 / 刷新历史
 * 通过 emits 通知宿主。组件自洽直调 /api/chat、/api/fs、/api/refinery 等,不依赖宿主方法。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
import {
  renderMarkdown,
  imageUrl,
  parseUserMessage,
  parseStreamJson,
  isStreamJson
} from '../lib/formatters.js';
import { copySegment } from '../lib/clipboard.js';
import { shareSession as shareSessionFn } from '../lib/share-session.js';
import { useFeedback } from '../composables/useFeedback.js';
import { useImageUpload } from '../composables/useImageUpload.js';
import { useSlashCommand } from '../composables/useSlashCommand.js';
import { useResumableRun } from '../composables/useResumableRun.js';
import { ref, reactive, computed, onMounted, nextTick, watch } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

const TEMPLATE = `
    <div class="chat-container">
      <!-- 会话头条:左侧预留标题位,右侧分析评价 + 分享 -->
      <div class="chat-header-bar">
        <div class="chat-header-title"></div>
        <div v-if="canFeedback" class="feedback-bar">
          <span class="feedback-label">分析评价</span>
          <button type="button" class="feedback-chip feedback-chip--correct"
                  :class="{ active: feedback.rating === 'CORRECT' }"
                  @click="setRating('CORRECT')" title="分析正确">✓ 正确</button>
          <button type="button" class="feedback-chip feedback-chip--partial"
                  :class="{ active: feedback.rating === 'PARTIALLY_CORRECT' }"
                  @click="setRating('PARTIALLY_CORRECT')" title="分析部分正确">~ 部分正确</button>
          <button type="button" class="feedback-chip feedback-chip--incorrect"
                  :class="{ active: feedback.rating === 'INCORRECT' }"
                  @click="setRating('INCORRECT')" title="分析错误">✗ 错误</button>
          <el-button size="small" text type="primary" @click="openFeedbackDialog" title="补充文字说明">
            <el-icon><edit-pen /></el-icon>
            <span>{{ feedback.comment ? '说明·已填' : '补充说明' }}</span>
          </el-button>
        </div>
        <el-button v-if="canShare" size="small" text type="primary" @click="shareSession" title="分享会话">
          <el-icon><share /></el-icon>
          <span>分享</span>
        </el-button>
      </div>
      <!-- 聊天消息区 -->
      <div class="chat-messages" ref="chatContainer">
        <div v-for="(msg, index) in messages" :key="msg.id != null ? 'm-' + msg.id : 'tmp-' + index" class="chat-message">
          <div v-if="msg.role === 'user'" class="user-row" style="display: flex; justify-content: flex-end; align-items: center; gap: 6px;">
            <button v-if="msg.id != null" class="rewind-btn" type="button"
                    title="从这里重开 (删除此条及之后, 清空 resumeId, 回填输入框)"
                    @click="rewindToMessage(msg, index)">↩</button>
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
          <div v-else-if="msg.role === 'agent'">
            <div class="message-agent">
              <div v-if="msg.recall" class="recall-card">
                <div class="recall-card-head" @click="msg.recallOpen = !msg.recallOpen">
                  <span class="recall-card-toggle" :class="{expanded: msg.recallOpen}">▶</span>
                  <span class="recall-card-title">🔍 召回了 {{ msg.recall.hits.length }} 条历史参考</span>
                  <span v-if="msg.recall.query" class="recall-card-query">“{{ msg.recall.query }}”</span>
                </div>
                <div v-show="msg.recallOpen" class="recall-card-body">
                  <div v-for="(h, hi) in msg.recall.hits" :key="hi" class="recall-hit">
                    <div class="recall-hit-title">{{ hi + 1 }}. {{ h.title }}</div>
                    <div v-if="h.conclusion" class="recall-hit-conclusion">{{ h.conclusion }}</div>
                  </div>
                  <div v-if="!msg.recall.hits.length" class="recall-empty">无匹配历史，已照常发送原消息</div>
                </div>
              </div>
              <template v-for="(seg, si) in (msg.segments || [])" :key="si">
                <div v-if="seg.type === 'text'" class="text-segment-wrap">
                  <button class="copy-btn" type="button" title="复制 Markdown" @click="copySegment(seg.content)">📋</button>
                  <div class="text-segment" v-html="renderMarkdown(seg.content)"></div>
                </div>
                <div v-else class="tool-block">
                  <div class="tool-header" @click="toggleTool(index, si)">
                    <span class="tool-toggle" :class="{expanded: isToolExpanded(index, si)}">▶</span>
                    <span class="tool-label">{{ seg.name }}</span>
                  </div>
                  <div v-show="isToolExpanded(index, si)" class="tool-content">{{ seg.content }}</div>
                </div>
              </template>
              <div v-if="sending && reconnecting && index === messages.length - 1" class="message-system">连接中断，正在恢复...</div>
              <div v-if="sending && index === messages.length - 1" class="loading-dots"><span></span><span></span><span></span></div>
            </div>
          </div>
          <div v-else-if="msg.role === 'system'">
            <div class="message-system">{{ msg.text }}</div>
          </div>
          <div v-else-if="msg.role === 'error'">
            <div class="message-error">{{ msg.text }}</div>
          </div>
        </div>

        <el-empty v-if="messages.length === 0" description="请选择工作目录，输入问题即可开始对话" :image-size="120"></el-empty>
      </div>

      <!-- 输入区 -->
      <div class="chat-input-area" style="position: relative;">
        <div class="command-popup" v-if="showCommandPopup && filteredCommands.length > 0">
          <div v-for="(cmd, idx) in filteredCommands" :key="cmd.name"
               class="command-item" :class="{active: idx === selectedCommandIdx}"
               @mousedown.prevent="selectCommand(cmd)">
            <span class="command-name">/{{ cmd.name }}</span>
            <span class="command-desc">{{ cmd.description || cmd.argumentHint }}</span>
          </div>
        </div>
        <div class="pending-image-list" v-if="pendingImages.length > 0">
          <div class="pending-image-card" v-for="(img, idx) in pendingImages" :key="img.path">
            <img :src="img.previewUrl" :alt="img.name" :title="img.name" />
            <span class="pending-image-remove" @click="removePendingImage(idx)">×</span>
          </div>
        </div>
        <div class="pending-file-card" v-if="pendingFile">
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
          @keydown.enter.exact.prevent="handleEnter" @keydown.up.prevent="handleArrowUp" @keydown.down.prevent="handleArrowDown" @keydown.tab.prevent="handleTab" @keydown.escape="hideCommandPopup"
          @keydown.ctrl.enter.exact.prevent="insertNewline"
          @paste="handlePaste"
          :disabled="!workingDir"
        ></el-input>
        <div style="margin-top: 12px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px;">
          <div style="display: flex; align-items: center; gap: 8px;">
            <span class="hidden-mobile" style="color: #909399; font-size: 13px;" v-if="workingDir">Enter 发送 | Ctrl+Enter 换行</span>
            <span v-if="!workingDir" style="color: #E6A23C; font-weight: bold; font-size: 13px;">⚠ 请先选择工作目录</span>
            <el-button size="small" @click="clearContext" :disabled="!sessionId" plain>
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
            <el-switch v-if="ragEnabled" v-model="ragRecall" size="small"
                       active-text="RAG召回" inline-prompt
                       title="开启后每条消息自动召回历史参考拼到提问中"></el-switch>
          </div>
          <div style="display: flex; gap: 8px;">
            <el-button type="danger" @click="stopSession" v-if="sending" plain>
              <el-icon><video-pause /></el-icon>
              <span>停止</span>
            </el-button>
            <el-button type="primary" @click="sendMessageStream" :loading="sending" :disabled="!workingDir || !userInput.trim()">
              <el-icon><connection /></el-icon>
              <span>发送</span>
            </el-button>
          </div>
        </div>
      </div>

      <!-- 分析评价补充说明弹窗 -->
      <el-dialog v-model="feedbackDialogVisible" title="补充反馈说明" :width="isMobile ? '92%' : '460px'" append-to-body>
        <el-input v-model="feedbackCommentDraft" type="textarea" :rows="5"
                  maxlength="1000" show-word-limit
                  placeholder="描述 AI 分析中哪里不准确、遗漏了什么，或其他改进建议（选填）"></el-input>
        <template #footer>
          <el-button @click="feedbackDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="feedbackSaving" @click="submitFeedbackComment">提交</el-button>
        </template>
      </el-dialog>
    </div>
  `;

const ChatPanel = {
    name: 'ChatPanel',
    props: {
      workingDir: { type: String, default: '' },
      agentType: { type: String, default: 'CODEX' },
      initialSessionId: { type: String, default: '' },
      initialResumeId: { type: String, default: '' },
      ragEnabled: { type: Boolean, default: true },
    },
    emits: ['session-created', 'refresh-history'],
    template: TEMPLATE,
    setup(props, { emit }) {
      // 纯函数工具顶部已 ES import, 直接复用, 调用点无需改动

      // ===== 聊天状态(组件自有) =====
      const messages = ref([]);
      const userInput = ref('');
      const sending = ref(false);
      const sessionId = ref('');
      const resumeId = ref('');
      const chatContainer = ref(null);
      const toolStates = reactive({});
      // RAG 召回开关: 默认开, 持久化 localStorage
      const ragRecall = ref(localStorage.getItem('ragRecall') !== 'false');

      // props.workingDir 传 composable 需 Ref,用 computed 桥接
      const workingDirRef = computed(() => props.workingDir);

      const isMobile = ref(window.innerWidth <= 768);
      const onResize = () => { isMobile.value = window.innerWidth <= 768; };
      window.addEventListener('resize', onResize);

      // ===== computed =====
      const canShare = computed(() =>
        !!sessionId.value && !sending.value && messages.value.some(m => m.role === 'agent'));
      const canFeedback = computed(() =>
        !!sessionId.value && !sending.value && messages.value.some(m => m.role === 'agent'));

      // ===== 消息渲染辅助 =====
      const addMessage = (role, text) => {
        messages.value.push({ id: null, role, text });
        nextTick(() => {
          if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
        });
      };

      const userMessageEntry = (id, content) => {
        const parsed = parseUserMessage(content);
        return { id: id, role: 'user', text: content, bodyText: parsed.text, images: parsed.images };
      };

      const insertNewline = () => {
        const ta = document.querySelector('.chat-input-area textarea');
        if (ta) {
          const start = ta.selectionStart;
          const end = ta.selectionEnd;
          const value = userInput.value;
          userInput.value = value.substring(0, start) + '\n' + value.substring(end);
          nextTick(() => { ta.selectionStart = ta.selectionEnd = start + 1; });
        }
      };

      const isToolExpanded = (msgIndex, segIndex) => {
        const key = msgIndex + '-' + segIndex;
        return key in toolStates ? toolStates[key] : false;
      };
      const toggleTool = (msgIndex, segIndex) => {
        const key = msgIndex + '-' + segIndex;
        toolStates[key] = !(toolStates[key] === true);
      };

      // ===== 会话生命周期 =====
      const ensureSession = async () => {
        if (sessionId.value) return;
        const req = {
          workingDir: props.workingDir,
          agentType: props.agentType,
        };
        const res = await fetch('/api/chat/session', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(req),
        });
        if (!res.ok) throw new Error(await res.text());
        const data = await res.json();
        sessionId.value = data.sessionId;
        resumeId.value = '';
        feedback.value = { rating: null, comment: null };
        emit('session-created', {
          sessionId: data.sessionId,
          workingDir: data.workingDir,
          agentType: data.agentType,
        });
      };

      const clearContext = () => {
        resumeId.value = '';
        addMessage('system', '上下文已清除');
        ElMessage.success('上下文已清除');
      };

      async function reloadMessages() {
        try {
          const prevRecalls = messages.value
            .filter(function (m) { return m.role === 'agent'; })
            .map(function (m) { return { recall: m.recall || null, recallOpen: !!m.recallOpen }; });
          const data = await fetch('/api/chat/session/' + encodeURIComponent(sessionId.value) + '/messages').then(r => r.json());
          messages.value = [];
          data.forEach(function (msg) {
            if (msg.role === 'user') {
              messages.value.push(userMessageEntry(msg.id, msg.content));
            } else if (msg.role === 'assistant') {
              const segments = isStreamJson(msg.content) ? parseStreamJson(msg.content) : [{ type: 'text', content: msg.content }];
              let recall = null;
              if (msg.recall) { try { recall = JSON.parse(msg.recall); } catch (e) { recall = null; } }
              messages.value.push({ id: msg.id, role: 'agent', segments: segments, recall: recall, recallOpen: false });
            }
          });
          let agentIdx = 0;
          messages.value.forEach(function (m) {
            if (m.role === 'agent') {
              const prev = prevRecalls[agentIdx++];
              if (!m.recall && prev && prev.recall) { m.recall = prev.recall; m.recallOpen = prev.recallOpen; }
            }
          });
          nextTick(() => {
            if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
          });
        } catch (e) { /* ignore */ }
      }

      // ===== FE-R3.5/R3.6 composable: 分析评价 / 图片附件 / 可恢复 ChatRun / 命令弹窗 =====
      const {
        feedback, feedbackDialogVisible, feedbackCommentDraft, feedbackSaving,
        loadFeedback, persistFeedback, setRating, openFeedbackDialog, submitFeedbackComment
      } = useFeedback({ sessionId });
      const {
        pendingImages, pendingFile, maxImagesPerMessage, maxImageBytes, maxChatFileBytes,
        allowedChatFileExts, readAsDataURL, uploadImageFile, beforeChatImageUpload,
        uploadChatImage, removePendingImage, formatChatFileSize, beforeChatFileUpload,
        uploadChatFile, removePendingFile, handlePaste
      } = useImageUpload({ ensureSession, sessionId, workingDir: workingDirRef });
      const {
        activeRunId, runStatus, lastAppliedEventSeq, reconnecting,
        restoreActiveRun, sendMessageStream, resetRunState
      } = useResumableRun({
        messages, userInput, sending, sessionId, resumeId, chatContainer, ragRecall,
        pendingImages, pendingFile, workingDir: workingDirRef,
        ensureSession, addMessage, userMessageEntry, reloadMessages, loadFeedback, emit
      });
      const {
        slashCommands, showCommandPopup, selectedCommandIdx, filteredCommands,
        loadSlashCommands, handleEnter, handleArrowUp, handleArrowDown,
        handleTab, selectCommand, hideCommandPopup
      } = useSlashCommand({ userInput, workingDir: workingDirRef, sendMessageStream });

      const stopSession = async () => {
        if (!sessionId.value || !sending.value) return;
        if (!activeRunId.value) {
          ElMessage.warning('运行任务标识尚未返回，请稍后重试或刷新页面恢复');
          return;
        }
        try {
          const res = await fetch('/api/chat/runs/' + encodeURIComponent(activeRunId.value) + '/stop', {
            method: 'POST',
          });
          if (!res.ok) throw new Error(await res.text());
          const result = await res.json();
          runStatus.value = result.status || 'CANCEL_REQUESTED';
          addMessage('system', '已请求停止，等待任务退出');
        } catch (e) {
          ElMessage.error('停止失败: ' + (e.message || e));
        }
      };

      // 清空当前对话(切目录 / 新对话),不留系统消息——与原 watch(currentPath) 行为一致
      const clearConversation = () => {
        resetRunState();
        sending.value = false;
        sessionId.value = '';
        resumeId.value = '';
        messages.value = [];
        feedback.value = { rating: null, comment: null };
        pendingImages.value = [];
        pendingFile.value = null;
      };

      const shareSession = () => shareSessionFn(sessionId.value);

      // ===== 对话回退 =====
      const rewindToMessage = async (msg, index) => {
        if (sending.value) { ElMessage.warning('请先停止当前对话再回退'); return; }
        if (msg.id == null) { ElMessage.warning('该消息尚未持久化, 请稍候再试'); return; }
        const toDelete = messages.value.slice(index).filter(m => m.role === 'user' || m.role === 'agent').length;
        try {
          await ElMessageBox.confirm(
            '将删除此条消息及其之后的 ' + toDelete + ' 条对话, 并清空当前会话的 resumeId. 是否继续?',
            '从这里重开',
            { confirmButtonText: '确认回退', cancelButtonText: '取消', type: 'warning' });
        } catch (e) { return; }
        try {
          const url = '/api/chat/session/' + encodeURIComponent(sessionId.value) + '/messages?fromId=' + encodeURIComponent(msg.id);
          const res = await fetch(url, { method: 'DELETE' });
          if (!res.ok) throw new Error('HTTP ' + res.status);
          const result = await res.json();
          messages.value = messages.value.slice(0, index);
          resumeId.value = '';
          if (result.prefillContent) { userInput.value = result.prefillContent; }
          ElMessage.success('已回退, 删除 ' + result.deletedCount + ' 条消息');
          emit('refresh-history');
          nextTick(() => {
            const ta = document.querySelector('.chat-input-area textarea');
            if (ta) ta.focus();
          });
        } catch (e) {
          ElMessage.error('回退失败: ' + (e.message || e));
        }
      };

      // ===== 恢复历史会话(宿主通过 initialSessionId 触发) =====
      const applyResume = async (sid, rid) => {
        if (!sid) return;
        try {
          const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/messages').then(r => r.json());
          sessionId.value = sid;
          resumeId.value = rid || '';
          messages.value = [];
          data.forEach(function (msg) {
            if (msg.role === 'user') {
              messages.value.push(userMessageEntry(msg.id, msg.content));
            } else if (msg.role === 'assistant') {
              const segments = isStreamJson(msg.content) ? parseStreamJson(msg.content) : [{ type: 'text', content: msg.content }];
              let recall = null;
              if (msg.recall) { try { recall = JSON.parse(msg.recall); } catch (e) { recall = null; } }
              messages.value.push({ id: msg.id, role: 'agent', segments: segments, recall: recall, recallOpen: false });
            }
          });
          loadFeedback(sid);
          addMessage('system', '已恢复历史会话');
          fetch('/api/chat/session/' + encodeURIComponent(sid) + '/commands')
            .then(r => r.json()).then(cmds => { slashCommands.value = cmds; }).catch(() => {});
          ElMessage.success('已恢复历史会话');
          await restoreActiveRun(sid);
        } catch (e) {
          ElMessage.error('恢复会话失败');
        }
      };

      // ===== watch / 生命周期 =====
      watch(ragRecall, (v) => { localStorage.setItem('ragRecall', v ? 'true' : 'false'); });

      watch(userInput, (val) => {
        if (val && val.startsWith('/') && val.indexOf(' ') < 0 && slashCommands.value.length > 0) {
          showCommandPopup.value = true;
          selectedCommandIdx.value = 0;
        } else {
          showCommandPopup.value = false;
        }
      });

      // 工作目录变化:清空当前对话并重载命令(原 app.js watch(currentPath) 行为)
      watch(() => props.workingDir, () => {
        clearConversation();
        loadSlashCommands();
        restoreActiveRun('');
      });

      // 宿主切换会话:非空且不同 → 恢复;置空 → 清空开新对话
      watch(() => props.initialSessionId, (newVal) => {
        if (newVal && newVal !== sessionId.value) {
          applyResume(newVal, props.initialResumeId);
        } else if (!newVal) {
          clearConversation();
        }
      });

      onMounted(() => {
        loadSlashCommands();
        if (props.initialSessionId) {
          applyResume(props.initialSessionId, props.initialResumeId);
        }
        if (!props.initialSessionId) restoreActiveRun('');
      });

      return {
        // state
        messages, userInput, sending, sessionId, activeRunId, runStatus, reconnecting, chatContainer,
        feedback, feedbackDialogVisible, feedbackCommentDraft, feedbackSaving,
        showCommandPopup, selectedCommandIdx, filteredCommands,
        pendingImages, maxImagesPerMessage, pendingFile,
        ragRecall, isMobile,
        // computed
        canShare, canFeedback,
        // shared formatters
        renderMarkdown, imageUrl,
        // methods
        setRating, openFeedbackDialog, submitFeedbackComment, shareSession,
        rewindToMessage, copySegment, isToolExpanded, toggleTool,
        handleEnter, handleArrowUp, handleArrowDown, handleTab, selectCommand, hideCommandPopup, insertNewline, handlePaste,
        clearContext, stopSession, sendMessageStream,
        uploadChatImage, beforeChatImageUpload, removePendingImage,
        uploadChatFile, beforeChatFileUpload, removePendingFile, formatChatFileSize,
      };
    },
  };

export default ChatPanel;
