/*
 * ChatPanel: 可复用的「一次对话」全局组件(window.ChatPanel)。
 *
 * 承载完整聊天闭环:SSE 收流 / 斜杠命令 / 图片附件 / RAG 召回开关 / 停止 / 断连重连 /
 * 消息回退 / 清空上下文 / 分析评价。主控台(index)与管理台(admin)共享同一份实现,
 * admin 内嵌本组件即可闭环 continue-as-chat 续聊。
 *
 * ⚠️ 关键:原 app.js 文件级闭包变量 currentES / lastEventTime / heartbeatTimer / recovering
 * 全部迁进 setup() 闭包 —— 迁入后天然每实例独立。漏迁会让 SSE / 心跳 / 断连重连失效。
 *
 * 与宿主的契约见 §4.2:workingDir/agentType/env 等作 props 传入;新建会话 / 刷新历史
 * 通过 emits 通知宿主。组件自洽直调 /api/chat、/api/fs、/api/refinery 等,不依赖宿主方法。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
(function () {
  const { ref, reactive, computed, onMounted, nextTick, watch } = Vue;

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
          placeholder="输入你的问题，例如：测试环境，traceId: xxx，问题描述"
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

  window.ChatPanel = {
    name: 'ChatPanel',
    props: {
      workingDir: { type: String, default: '' },
      agentType: { type: String, default: 'CODEX' },
      env: { type: String, default: '' },
      envList: { type: Array, default: () => [] },
      initialSessionId: { type: String, default: '' },
      initialResumeId: { type: String, default: '' },
      ragEnabled: { type: Boolean, default: true },
    },
    emits: ['session-created', 'title-changed', 'refresh-history'],
    template: TEMPLATE,
    setup(props, { emit }) {
      const { renderMarkdown, imageUrl, parseUserMessage, parseStreamJson, isStreamJson } = window.AgentFormatters;

      // ===== 聊天状态(组件自有) =====
      const messages = ref([]);
      const userInput = ref('');
      const sending = ref(false);
      const sessionId = ref('');
      const resumeId = ref('');
      const chatContainer = ref(null);
      const feedback = ref({ rating: null, comment: null });
      const feedbackDialogVisible = ref(false);
      const feedbackCommentDraft = ref('');
      const feedbackSaving = ref(false);
      const slashCommands = ref([]);
      const showCommandPopup = ref(false);
      const selectedCommandIdx = ref(0);
      const toolStates = reactive({});
      // RAG 召回开关: 默认开, 持久化 localStorage
      const ragRecall = ref(localStorage.getItem('ragRecall') !== 'false');

      // 待发送图片 / 附件
      const pendingImages = ref([]);
      const maxImagesPerMessage = 4;
      const maxImageBytes = 1024 * 1024;
      const pendingFile = ref(null);
      const maxChatFileBytes = 5 * 1024 * 1024;
      const allowedChatFileExts = ['log','txt','json','csv','md','yaml','yml','xml','properties','stacktrace','out','conf','ini'];

      const isMobile = ref(window.innerWidth <= 768);
      const onResize = () => { isMobile.value = window.innerWidth <= 768; };
      window.addEventListener('resize', onResize);

      // ⚠️ 原文件级闭包变量,迁进 setup 闭包 → 每实例独立(SSE / 心跳 / 断连重连)
      let currentES = null;
      let lastEventTime = 0;
      let heartbeatTimer = null;
      let recovering = false;

      // ===== computed =====
      const canShare = computed(() =>
        !!sessionId.value && !sending.value && messages.value.some(m => m.role === 'agent'));
      const canFeedback = computed(() =>
        !!sessionId.value && !sending.value && messages.value.some(m => m.role === 'agent'));
      const filteredCommands = computed(() => {
        const input = userInput.value;
        if (!input.startsWith('/')) return [];
        const query = input.indexOf(' ') > 0 ? input.substring(1, input.indexOf(' ')) : input.substring(1);
        if (!query) return slashCommands.value;
        return slashCommands.value.filter(c => c.name.toLowerCase().includes(query.toLowerCase()));
      });

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

      const copySegment = async (text) => {
        if (!text) return;
        try {
          if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
          } else {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
          }
          ElementPlus.ElMessage.success('已复制');
        } catch (e) {
          ElementPlus.ElMessage.error('复制失败');
        }
      };

      const copyToClipboard = async (text) => {
        if (navigator.clipboard && window.isSecureContext) {
          try { await navigator.clipboard.writeText(text); return true; } catch (e) { /* fall through */ }
        }
        try {
          const ta = document.createElement('textarea');
          ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-9999px';
          document.body.appendChild(ta); ta.select();
          const ok = document.execCommand('copy');
          document.body.removeChild(ta);
          return ok;
        } catch (e) { return false; }
      };

      // ===== 会话生命周期 =====
      const ensureSession = async () => {
        if (sessionId.value) return;
        const req = {
          workingDir: props.workingDir,
          agentType: props.agentType,
          env: props.env || null,
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
          env: props.env || null,
        });
      };

      const loadSlashCommands = async () => {
        if (!props.workingDir) { slashCommands.value = []; return; }
        try {
          const cmds = await fetch('/api/chat/commands?workingDir=' + encodeURIComponent(props.workingDir)).then(r => r.json());
          slashCommands.value = cmds;
        } catch (e) {
          slashCommands.value = [];
        }
      };

      const clearContext = () => {
        resumeId.value = '';
        addMessage('system', '上下文已清除');
        ElementPlus.ElMessage.success('上下文已清除');
      };

      const stopSession = async () => {
        if (!sessionId.value || !sending.value) return;
        try {
          await fetch('/api/chat/session/' + encodeURIComponent(sessionId.value) + '/stop', { method: 'POST' });
        } catch (e) { /* best effort */ }
        if (currentES) { currentES.close(); currentES = null; }
        sending.value = false;
        addMessage('system', '已手动停止');
      };

      // 清空当前对话(切目录 / 新对话),不留系统消息——与原 watch(currentPath) 行为一致
      const clearConversation = () => {
        if (currentES) { currentES.close(); currentES = null; }
        stopHeartbeat();
        sending.value = false;
        sessionId.value = '';
        resumeId.value = '';
        messages.value = [];
        feedback.value = { rating: null, comment: null };
        pendingImages.value = [];
        pendingFile.value = null;
      };

      // ===== 图片上传 =====
      const readAsDataURL = (file) => new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });

      const uploadImageFile = async (file) => {
        await ensureSession();
        const form = new FormData();
        form.append('file', file);
        const url = '/api/fs/upload-image?path=' + encodeURIComponent(props.workingDir)
                  + '&sessionId=' + encodeURIComponent(sessionId.value);
        const res = await fetch(url, { method: 'POST', body: form });
        if (!res.ok) throw new Error(await res.text());
        const data = await res.json();
        const previewUrl = await readAsDataURL(file);
        pendingImages.value.push({ path: data.path, previewUrl: previewUrl, name: file.name || 'clipboard.png' });
      };

      const beforeChatImageUpload = (file) => {
        if (!props.workingDir) { ElementPlus.ElMessage.warning('请先选择工作目录'); return false; }
        if (file.type && file.type.indexOf('image/') !== 0) { ElementPlus.ElMessage.warning('只能上传图片'); return false; }
        if (file.size > maxImageBytes) { ElementPlus.ElMessage.warning('图片大小不能超过 1MB'); return false; }
        if (pendingImages.value.length >= maxImagesPerMessage) { ElementPlus.ElMessage.warning('每条消息最多 ' + maxImagesPerMessage + ' 张图片'); return false; }
        return true;
      };

      const uploadChatImage = async (options) => {
        try {
          await uploadImageFile(options.file);
          ElementPlus.ElMessage.success('图片已上传');
          if (options.onSuccess) options.onSuccess({});
        } catch (e) {
          ElementPlus.ElMessage.error('图片上传失败: ' + e.message);
          if (options.onError) options.onError(e);
        }
      };

      const removePendingImage = (idx) => { pendingImages.value.splice(idx, 1); };

      // ===== 文本附件上传 =====
      const formatChatFileSize = (bytes) => {
        if (bytes == null) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1024 / 1024).toFixed(2) + ' MB';
      };

      const extOfName = (name) => {
        if (!name) return '';
        const dot = name.lastIndexOf('.');
        if (dot < 0 || dot === name.length - 1) return '';
        return name.substring(dot + 1).toLowerCase();
      };

      const beforeChatFileUpload = (file) => {
        if (!props.workingDir) { ElementPlus.ElMessage.warning('请先选择工作目录'); return false; }
        if (pendingFile.value) { ElementPlus.ElMessage.warning('每条消息只能附一个文件,请先移除当前附件'); return false; }
        if (file.size > maxChatFileBytes) { ElementPlus.ElMessage.warning('文件大小不能超过 5MB'); return false; }
        const ext = extOfName(file.name);
        if (!ext || allowedChatFileExts.indexOf(ext) < 0) {
          ElementPlus.ElMessage.warning('仅支持文本类附件:' + allowedChatFileExts.join('/'));
          return false;
        }
        return true;
      };

      const uploadChatFileBytes = async (file) => {
        await ensureSession();
        const form = new FormData();
        form.append('file', file);
        const url = '/api/fs/upload-file?path=' + encodeURIComponent(props.workingDir)
                  + '&sessionId=' + encodeURIComponent(sessionId.value);
        const res = await fetch(url, { method: 'POST', body: form });
        if (!res.ok) throw new Error(await res.text());
        const data = await res.json();
        pendingFile.value = { path: data.path, name: file.name, size: file.size };
      };

      const uploadChatFile = async (options) => {
        try {
          await uploadChatFileBytes(options.file);
          ElementPlus.ElMessage.success('附件已上传');
          if (options.onSuccess) options.onSuccess({});
        } catch (e) {
          ElementPlus.ElMessage.error('附件上传失败: ' + e.message);
          if (options.onError) options.onError(e);
        }
      };

      const removePendingFile = () => { pendingFile.value = null; };

      const handlePaste = async (event) => {
        const items = (event.clipboardData && event.clipboardData.items) || [];
        let imageFile = null;
        for (let i = 0; i < items.length; i++) {
          if (items[i].kind === 'file' && items[i].type.indexOf('image/') === 0) {
            imageFile = items[i].getAsFile();
            break;
          }
        }
        if (!imageFile) return;
        event.preventDefault();
        if (!props.workingDir) { ElementPlus.ElMessage.warning('请先选择工作目录'); return; }
        if (pendingImages.value.length >= maxImagesPerMessage) { ElementPlus.ElMessage.warning('每条消息最多 ' + maxImagesPerMessage + ' 张图片'); return; }
        if (imageFile.size > maxImageBytes) { ElementPlus.ElMessage.warning('图片大小不能超过 1MB'); return; }
        try {
          await uploadImageFile(imageFile);
          ElementPlus.ElMessage.success('图片已上传');
        } catch (e) {
          ElementPlus.ElMessage.error('图片上传失败: ' + e.message);
        }
      };

      // ===== 分析评价 =====
      const loadFeedback = async (sid) => {
        feedback.value = { rating: null, comment: null };
        if (!sid) return;
        try {
          const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/feedback').then(r => r.json());
          feedback.value = { rating: data.rating || null, comment: data.comment || null };
        } catch (e) { /* best effort */ }
      };

      const persistFeedback = async () => {
        const sid = sessionId.value;
        if (!sid) return false;
        try {
          const res = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/feedback', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ rating: feedback.value.rating, comment: feedback.value.comment }),
          });
          if (!res.ok) throw new Error(await res.text());
          const data = await res.json();
          feedback.value = { rating: data.rating || null, comment: data.comment || null };
          return true;
        } catch (e) {
          ElementPlus.ElMessage.error('保存反馈失败: ' + (e.message || '未知错误'));
          return false;
        }
      };

      const setRating = async (rating) => {
        const prev = feedback.value.rating;
        const next = prev === rating ? null : rating;
        feedback.value = { rating: next, comment: feedback.value.comment };
        const ok = await persistFeedback();
        if (ok) {
          ElementPlus.ElMessage.success(next ? '已记录评价' : '已取消评价');
        } else {
          feedback.value = { rating: prev, comment: feedback.value.comment };
        }
      };

      const openFeedbackDialog = () => {
        feedbackCommentDraft.value = feedback.value.comment || '';
        feedbackDialogVisible.value = true;
      };

      const submitFeedbackComment = async () => {
        feedbackSaving.value = true;
        const prev = feedback.value.comment;
        feedback.value = { rating: feedback.value.rating, comment: feedbackCommentDraft.value.trim() || null };
        const ok = await persistFeedback();
        feedbackSaving.value = false;
        if (ok) {
          feedbackDialogVisible.value = false;
          ElementPlus.ElMessage.success('反馈已提交');
        } else {
          feedback.value = { rating: feedback.value.rating, comment: prev };
        }
      };

      const shareSession = async () => {
        const target = sessionId.value;
        if (!target) return;
        try {
          const res = await fetch('/api/chat/session/' + encodeURIComponent(target) + '/share', { method: 'POST' });
          if (!res.ok) throw new Error(await res.text());
          const data = await res.json();
          const shareUrl = window.location.origin + window.withBase('/share.html?token=' + data.shareToken);
          const copied = await copyToClipboard(shareUrl);
          if (copied) {
            ElementPlus.ElMessage.success('分享链接已复制到剪贴板');
          } else {
            ElementPlus.ElMessageBox.alert(shareUrl, '分享链接（请手动复制）', { confirmButtonText: '关闭', customClass: 'share-link-dialog' });
          }
        } catch (e) {
          ElementPlus.ElMessage.error('生成分享链接失败: ' + (e.message || '未知错误'));
        }
      };

      // ===== 命令弹窗交互 =====
      const handleEnter = () => {
        if (showCommandPopup.value && filteredCommands.value.length > 0) {
          selectCommand(filteredCommands.value[selectedCommandIdx.value]);
        } else {
          sendMessageStream();
        }
      };
      const scrollCommandIntoView = () => {
        nextTick(() => {
          const popup = document.querySelector('.command-popup');
          if (!popup) return;
          const active = popup.querySelector('.command-item.active');
          if (active) active.scrollIntoView({ block: 'nearest' });
        });
      };
      const handleArrowUp = () => {
        if (!showCommandPopup.value) return;
        selectedCommandIdx.value = Math.max(0, selectedCommandIdx.value - 1);
        scrollCommandIntoView();
      };
      const handleArrowDown = () => {
        if (!showCommandPopup.value) return;
        selectedCommandIdx.value = Math.min(filteredCommands.value.length - 1, selectedCommandIdx.value + 1);
        scrollCommandIntoView();
      };
      const handleTab = () => {
        if (showCommandPopup.value && filteredCommands.value.length > 0) {
          selectCommand(filteredCommands.value[selectedCommandIdx.value]);
        }
      };
      const selectCommand = (cmd) => {
        userInput.value = '/' + cmd.name + ' ';
        showCommandPopup.value = false;
        nextTick(() => {
          const textarea = document.querySelector('.chat-input-area textarea');
          if (textarea) textarea.focus();
        });
      };
      const hideCommandPopup = () => { showCommandPopup.value = false; };

      // ===== 断连恢复 =====
      function startHeartbeat() {
        stopHeartbeat();
        lastEventTime = Date.now();
        heartbeatTimer = setInterval(function () {
          if (!sending.value) { stopHeartbeat(); return; }
          if (currentES && currentES.readyState === EventSource.CLOSED) { recoverSession(); return; }
          if (!currentES && sending.value) { recoverSession(); return; }
          if (Date.now() - lastEventTime > 30000) { recoverSession(); }
        }, 10000);
      }
      function stopHeartbeat() {
        if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      }
      async function recoverSession() {
        if (recovering || !sessionId.value) return;
        recovering = true;
        stopHeartbeat();
        if (currentES) { currentES.close(); currentES = null; }
        try {
          const statusRes = await fetch('/api/chat/session/' + encodeURIComponent(sessionId.value) + '/status').then(r => r.json());
          if (statusRes.running) { await pollUntilDone(); }
          await reloadMessages();
        } catch (e) { /* fallback */ }
        sending.value = false;
        recovering = false;
      }
      async function pollUntilDone() {
        while (true) {
          await new Promise(r => setTimeout(r, 3000));
          try {
            const res = await fetch('/api/chat/session/' + encodeURIComponent(sessionId.value) + '/status').then(r => r.json());
            if (!res.running) break;
          } catch (e) { break; }
        }
      }
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

      // ===== 对话回退 =====
      const rewindToMessage = async (msg, index) => {
        if (sending.value) { ElementPlus.ElMessage.warning('请先停止当前对话再回退'); return; }
        if (msg.id == null) { ElementPlus.ElMessage.warning('该消息尚未持久化, 请稍候再试'); return; }
        const toDelete = messages.value.slice(index).filter(m => m.role === 'user' || m.role === 'agent').length;
        try {
          await ElementPlus.ElMessageBox.confirm(
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
          ElementPlus.ElMessage.success('已回退, 删除 ' + result.deletedCount + ' 条消息');
          emit('refresh-history');
          nextTick(() => {
            const ta = document.querySelector('.chat-input-area textarea');
            if (ta) ta.focus();
          });
        } catch (e) {
          ElementPlus.ElMessage.error('回退失败: ' + (e.message || e));
        }
      };

      // ===== SSE 发送 =====
      const sendMessageStream = async () => {
        if (!props.workingDir || !userInput.value.trim() || sending.value) return;
        try {
          await ensureSession();
        } catch (error) {
          ElementPlus.ElMessage.error('创建会话失败: ' + error.message);
          return;
        }

        const baseText = userInput.value.trim();
        const imagePaths = pendingImages.value.map(img => img.path);
        const filePath = pendingFile.value ? pendingFile.value.path : null;
        let message = baseText;
        if (imagePaths.length) { message += '\n' + imagePaths.join('\n'); }
        if (filePath) { message += '\n\n[附件清单]\n- ' + filePath; }
        messages.value.push(userMessageEntry(null, message));
        userInput.value = '';
        pendingImages.value = [];
        pendingFile.value = null;
        sending.value = true;

        const msgIndex = messages.value.length;
        messages.value.push({ id: null, role: 'agent', segments: [], recall: null, recallOpen: false });

        let url = '/api/chat/session/' + encodeURIComponent(sessionId.value) + '/message/stream?message=' + encodeURIComponent(message);
        if (resumeId.value) { url += '&resumeId=' + encodeURIComponent(resumeId.value); }
        url += '&recall=' + (ragRecall.value ? 'true' : 'false');

        const es = new EventSource(url);
        currentES = es;
        startHeartbeat();
        let segments = [];
        let inToolUse = false;
        let flushTimer = null;
        let flushPending = false;

        function appendText(text) {
          const last = segments.length > 0 ? segments[segments.length - 1] : null;
          if (last && last.type === 'text') { last.content += text; }
          else { segments.push({ type: 'text', content: text }); }
        }
        function appendToolContent(text) {
          for (let i = segments.length - 1; i >= 0; i--) {
            if (segments[i].type === 'tool') { segments[i].content += text; return; }
          }
          segments.push({ type: 'tool', name: 'Tool', content: text });
        }
        function flushSegmentsNow() {
          if (flushTimer) {
            clearTimeout(flushTimer);
            flushTimer = null;
          }
          flushPending = false;
          messages.value[msgIndex].segments = segments.map(function (s) { return { type: s.type, name: s.name, content: s.content }; });
          nextTick(() => {
            if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
          });
        }
        function scheduleFlushSegments() {
          if (flushPending) return;
          flushPending = true;
          flushTimer = setTimeout(flushSegmentsNow, 150);
        }

        es.addEventListener('recall', (e) => {
          lastEventTime = Date.now();
          try {
            const payload = JSON.parse(e.data);
            messages.value[msgIndex].recall = payload;
            messages.value[msgIndex].recallOpen = false;
          } catch (err) { /* ignore */ }
        });

        es.addEventListener('chunk', (e) => {
          lastEventTime = Date.now();
          const data = e.data;
          try {
            const json = JSON.parse(data);
            if (json.session_id) { resumeId.value = json.session_id; }
            if (json.type === 'stream_event' && json.event) {
              const evt = json.event;
              if (evt.type === 'content_block_start' && evt.content_block) {
                if (evt.content_block.type === 'tool_use') {
                  inToolUse = true;
                  segments.push({ type: 'tool', name: evt.content_block.name, content: '' });
                } else { inToolUse = false; }
              } else if (evt.type === 'content_block_delta' && evt.delta) {
                if (evt.delta.text) { appendText(evt.delta.text); }
                else if (evt.delta.partial_json) { appendToolContent(evt.delta.partial_json); }
              } else if (evt.type === 'content_block_stop') { inToolUse = false; }
            } else if (json.type === 'assistant' && json.message && json.message.content) {
              const hasContent = segments.some(function (s) { return s.content && s.content.trim(); });
              if (!hasContent) {
                segments = [];
                for (const block of json.message.content) {
                  if (block.type === 'text' && block.text) {
                    segments.push({ type: 'text', content: block.text });
                  } else if (block.type === 'tool_use') {
                    segments.push({ type: 'tool', name: block.name, content: JSON.stringify(block.input, null, 2) });
                  }
                }
              }
            } else if (json.type === 'user' && json.message && json.message.content) {
              for (const block of json.message.content) {
                if (block.type === 'tool_result') {
                  let resultText = '';
                  if (json.tool_use_result) {
                    const tur = json.tool_use_result;
                    if (tur.file) { resultText = '[文件: ' + tur.file.filePath + ' (' + tur.file.numLines + '行)]'; }
                    else if (typeof tur === 'string') { resultText = tur; }
                    else if (tur.type === 'text' && typeof block.content === 'string') { resultText = block.content; }
                  }
                  if (!resultText && typeof block.content === 'string') { resultText = block.content; }
                  if (resultText) {
                    if (resultText.length > 2000) { resultText = resultText.substring(0, 2000) + '\n... (共 ' + resultText.length + ' 字符，已截断)'; }
                    appendToolContent('\n' + resultText);
                  }
                }
              }
            } else if (json.type === 'result' && json.result) {
              const hasContent = segments.some(function (s) { return s.content && s.content.trim(); });
              if (!hasContent) { segments = [{ type: 'text', content: json.result }]; }
            }
          } catch (err) {
            if (data && !data.startsWith('{') && !data.startsWith('[')) { appendText(data); }
          }
          scheduleFlushSegments();
        });

        es.addEventListener('ping', () => { lastEventTime = Date.now(); });

        es.addEventListener('exit', (e) => {
          stopHeartbeat();
          flushSegmentsNow();
          addMessage('system', e.data === '0' ? '任务已完成' : '进程异常退出，退出码: ' + e.data);
          es.close();
          currentES = null;
          sending.value = false;
          reloadMessages();
          emit('refresh-history');
        });

        es.addEventListener('error', (e) => {
          stopHeartbeat();
          flushSegmentsNow();
          addMessage('error', e.data || 'SSE 连接错误');
          es.close();
          currentES = null;
          sending.value = false;
        });

        es.onerror = () => {
          es.close();
          currentES = null;
          fetch('/api/auth/status').then(r => r.json()).then(s => {
            if (!s.authenticated && s.loginUrl) { window.location.href = s.loginUrl; }
          }).catch(() => {});
        };
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
          ElementPlus.ElMessage.success('已恢复历史会话');
        } catch (e) {
          ElementPlus.ElMessage.error('恢复会话失败');
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
        document.addEventListener('visibilitychange', function () {
          if (document.visibilityState === 'visible' && sending.value && !recovering) {
            if (!currentES || currentES.readyState === EventSource.CLOSED || Date.now() - lastEventTime > 30000) {
              recoverSession();
            }
          }
        });
      });

      return {
        // state
        messages, userInput, sending, sessionId, chatContainer,
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
})();
