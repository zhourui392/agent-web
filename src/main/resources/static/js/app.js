const { createApp, ref, reactive, onMounted, nextTick, watch } = Vue;

const app = createApp({
  setup() {
    // 数据
    const DEFAULT_WORKING_PATH = '/home/ubuntu/workspace/qpon';
    const roots = ref([]);
    const selectedRoot = ref('');
    const currentPath = ref('');
    const folderList = ref([]);
    const agentType = ref('CLAUDE');
    const sessionId = ref('');
    const workingDir = ref('');
    const resumeId = ref('');
    const username = ref('admin');
    const env = ref('');
    const envList = ref([]);
    const messages = ref([]);
    const userInput = ref('');
    const starting = ref(false);
    const sending = ref(false);
    const chatContainer = ref(null);
    const historyList = ref([]);
    const historyPage = ref(1);
    const historyPageSize = 20;
    const historyHasMore = ref(true);
    const historyLoading = ref(false);
    const historyMessages = ref([]);
    const historyDrawerVisible = ref(false);
    const currentHistorySessionId = ref('');
    const summarizing = ref(false);
    const slashCommands = ref([]);
    const showCommandPopup = ref(false);
    const selectedCommandIdx = ref(0);
    const selectedBranch = ref('');
    const currentBranch = ref('');
    const switchingBranch = ref(false);
    const savedBranches = ref(JSON.parse(localStorage.getItem('agent_saved_branches') || '[]'));
    const switchResult = ref(null);
    const removingBranch = ref('');
    const originalWorkspacePath = ref('');

    // --- 定时任务 ---
    const taskList = ref([]);
    const taskDialogVisible = ref(false);
    const taskEditing = ref(null);
    const taskForm = reactive({
      name: '',
      cronExpr: '',
      prompt: '',
      workingDir: '',
    });
    const taskLoading = ref(false);
    const workspaceDialogVisible = ref(false);
    const taskManagerVisible = ref(false);
    const sidebarVisible = ref(false);
    const isMobile = ref(window.innerWidth <= 768);

    window.addEventListener('resize', () => {
      isMobile.value = window.innerWidth <= 768;
      if (!isMobile.value) sidebarVisible.value = false;
    });

    const groupedHistory = Vue.computed(() => {
      const now = new Date();
      const today = now.toDateString();
      const yesterday = new Date(now - 86400000).toDateString();
      const groups = { today: [], yesterday: [], older: [] };

      historyList.value.forEach(h => {
        const d = new Date(h.createdAt).toDateString();
        if (d === today) groups.today.push(h);
        else if (d === yesterday) groups.yesterday.push(h);
        else groups.older.push(h);
      });

      const result = [];
      if (groups.today.length) result.push({ label: '今天', items: groups.today });
      if (groups.yesterday.length) result.push({ label: '昨天', items: groups.yesterday });
      if (groups.older.length) result.push({ label: '更早', items: groups.older });
      return result;
    });

    const filteredCommands = Vue.computed(() => {
      const input = userInput.value;
      if (!input.startsWith('/')) return [];
      const query = input.indexOf(' ') > 0 ? input.substring(1, input.indexOf(' ')) : input.substring(1);
      if (!query) return slashCommands.value;
      return slashCommands.value.filter(c => c.name.toLowerCase().includes(query.toLowerCase()));
    });

    let currentES = null;

    // ========== 初始化 ==========
    const init = async () => {
      try {
        const envData = await fetch('/api/chat/envs').then(r => r.json());
        envList.value = envData;
        if (envData.length > 0) {
          env.value = envData[0].key;
        }
        const data = await fetch('/api/fs/roots').then(r => r.json());
        roots.value = data;
        if (data.length > 0) {
          // 尝试恢复上次的 worktree 状态
          const saved = JSON.parse(localStorage.getItem('agent_worktree_state') || 'null');
          if (saved && saved.worktreePath && saved.currentBranch) {
            const check = await fetch('/api/fs/list?path=' + encodeURIComponent(saved.worktreePath));
            if (check.ok) {
              const wsRoot = data.find(root => saved.originalWorkspacePath.startsWith(root));
              selectedRoot.value = wsRoot || data[0];
              originalWorkspacePath.value = saved.originalWorkspacePath;
              currentBranch.value = saved.currentBranch;
              selectedBranch.value = saved.currentBranch;
              currentPath.value = saved.worktreePath;
              await loadList(saved.worktreePath);
              return;
            }
            clearWorktreeState();
          }

          const defaultRoot = data.find(root => DEFAULT_WORKING_PATH.startsWith(root));
          if (defaultRoot) {
            selectedRoot.value = defaultRoot;
            currentPath.value = DEFAULT_WORKING_PATH;
            await loadList(DEFAULT_WORKING_PATH);
          } else {
            selectedRoot.value = data[0];
            currentPath.value = data[0];
            await loadList(data[0]);
          }
        }
      } catch (error) {
        ElementPlus.ElMessage.error('加载根路径失败');
      }
    };

    // ========== 文件系统 ==========
    const handleRootChange = async () => {
      currentPath.value = selectedRoot.value;
      await loadList(selectedRoot.value);
    };

    const loadList = async (path) => {
      if (path) currentPath.value = path;
      try {
        const data = await fetch('/api/fs/list?path=' + encodeURIComponent(currentPath.value))
          .then(r => {
            if (!r.ok) throw new Error('加载失败');
            return r.json();
          });
        folderList.value = data;
      } catch (error) {
        ElementPlus.ElMessage.error('加载目录失败: ' + error.message);
      }
    };

    const formatSize = (bytes) => {
      if (bytes == null) return '';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    };

    const handleFileCommand = (command, item) => {
      if (command === 'download') {
        window.open('/api/fs/download?path=' + encodeURIComponent(item.path), '_blank');
      } else if (command === 'delete') {
        ElementPlus.ElMessageBox.confirm('确定要删除 ' + item.name + ' 吗？', '确认删除', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning'
        }).then(() => {
          fetch('/api/fs/delete?path=' + encodeURIComponent(item.path), { method: 'DELETE' })
            .then(r => {
              if (!r.ok) throw new Error('删除失败');
              return r.json();
            })
            .then(() => {
              ElementPlus.ElMessage.success('已删除');
              loadList(currentPath.value);
            })
            .catch(e => ElementPlus.ElMessage.error(e.message));
        }).catch(() => {});
      }
    };

    const onUploadSuccess = () => {
      ElementPlus.ElMessage.success('上传成功');
      loadList(currentPath.value);
    };

    const onUploadError = () => {
      ElementPlus.ElMessage.error('上传失败');
    };

    // ========== 会话管理 ==========
    const ensureSession = async () => {
      if (sessionId.value) return;
      const req = { agentType: agentType.value, workingDir: currentPath.value };
      const res = await fetch('/api/chat/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req)
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text);
      }
      const data = await res.json();
      sessionId.value = data.sessionId;
      workingDir.value = data.workingDir;
      resumeId.value = '';
    };

    const loadSlashCommands = async () => {
      if (!currentPath.value) return;
      try {
        const cmds = await fetch('/api/chat/commands?workingDir=' + encodeURIComponent(currentPath.value)).then(r => r.json());
        slashCommands.value = cmds;
      } catch (e) {
        slashCommands.value = [];
      }
    };

    const newConversation = async () => {
      sessionId.value = '';
      messages.value = [];
      resumeId.value = '';
      addMessage('system', '新对话已就绪，工作目录：' + currentPath.value);
      ElementPlus.ElMessage.success('新对话已就绪');
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
      } catch (e) {
        // best effort
      }
      if (currentES) {
        currentES.close();
        currentES = null;
      }
      sending.value = false;
      addMessage('system', '已手动停止');
    };

    // ========== 消息与渲染 ==========
    const addMessage = (role, text) => {
      messages.value.push({ role, text });
      nextTick(() => {
        if (chatContainer.value) {
          chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
        }
      });
    };

    const insertNewline = () => {
      const textarea = document.querySelector('textarea');
      if (textarea) {
        const start = textarea.selectionStart;
        const end = textarea.selectionEnd;
        const value = userInput.value;
        userInput.value = value.substring(0, start) + '\n' + value.substring(end);
        nextTick(() => {
          textarea.selectionStart = textarea.selectionEnd = start + 1;
        });
      }
    };

    // 工具折叠状态管理
    const toolStates = reactive({});

    const isToolExpanded = (msgIndex, segIndex) => {
      const key = msgIndex + '-' + segIndex;
      if (key in toolStates) {
        return toolStates[key];
      }
      return false;
    };

    const toggleTool = (msgIndex, segIndex) => {
      const key = msgIndex + '-' + segIndex;
      toolStates[key] = !(toolStates[key] === true);
    };

    const renderMarkdown = (text) => {
      if (!text) return '';
      let cleaned = text.replace(/\n{3,}/g, '\n\n');
      try {
        if (typeof marked !== 'undefined' && marked.parse) {
          return marked.parse(cleaned, { breaks: false });
        }
      } catch (e) { /* fallback */ }
      return cleaned.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
    };

    const formatTime = (isoStr) => {
      if (!isoStr) return '';
      try {
        const d = new Date(isoStr);
        return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
      } catch (e) { return isoStr; }
    };

    const escapeHtml = (text) => {
      if (!text) return '';
      return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
    };

    // ========== 环境与分支 ==========
    const onEnvChange = (val) => {
      const found = envList.value.find(e => e.key === val);
      const label = found ? found.label : '无环境';
      ElementPlus.ElMessage.info('已切换到' + label);
      if (val !== 'test') {
        clearBranch();
      }
    };

    const saveWorktreeState = () => {
      localStorage.setItem('agent_worktree_state', JSON.stringify({
        originalWorkspacePath: originalWorkspacePath.value,
        currentBranch: currentBranch.value,
        worktreePath: currentPath.value
      }));
    };

    const clearWorktreeState = () => {
      localStorage.removeItem('agent_worktree_state');
    };

    const switchBranch = async () => {
      if (!selectedBranch.value || !currentPath.value) return;
      switchingBranch.value = true;
      switchResult.value = null;
      try {
        if (!originalWorkspacePath.value) {
          originalWorkspacePath.value = currentPath.value;
        }
        const wsPath = originalWorkspacePath.value || currentPath.value;
        const res = await fetch('/api/worktree/switch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ workspacePath: wsPath, branch: selectedBranch.value })
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text);
        }
        const data = await res.json();
        if (!savedBranches.value.includes(selectedBranch.value)) {
          savedBranches.value.push(selectedBranch.value);
          localStorage.setItem('agent_saved_branches', JSON.stringify(savedBranches.value));
        }
        currentBranch.value = selectedBranch.value;
        switchResult.value = data.repos;
        currentPath.value = data.worktreePath;
        await loadList(data.worktreePath);
        saveWorktreeState();
        const created = data.repos.filter(function(r) { return r.created; }).length;
        ElementPlus.ElMessage.success('已切换到 ' + selectedBranch.value + '，' + created + ' 个仓库');
      } catch (e) {
        ElementPlus.ElMessage.error('切换分支失败: ' + e.message);
      } finally {
        switchingBranch.value = false;
      }
    };

    const clearBranch = () => {
      if (originalWorkspacePath.value) {
        currentPath.value = originalWorkspacePath.value;
        loadList(originalWorkspacePath.value);
      }
      currentBranch.value = '';
      selectedBranch.value = '';
      switchResult.value = null;
      originalWorkspacePath.value = '';
      clearWorktreeState();
    };

    const removeSavedBranch = async (branch) => {
      removingBranch.value = branch;
      const wsPath = originalWorkspacePath.value || currentPath.value;
      try {
        await fetch('/api/worktree/remove?workspacePath=' + encodeURIComponent(wsPath)
            + '&branch=' + encodeURIComponent(branch), { method: 'DELETE' });
        ElementPlus.ElMessage.success('已清理分支 ' + branch + ' 的 worktree');
      } catch (e) {
        ElementPlus.ElMessage.warning('清理 worktree 失败，已移除标签');
      } finally {
        removingBranch.value = '';
      }
      savedBranches.value = savedBranches.value.filter(function(b) { return b !== branch; });
      localStorage.setItem('agent_saved_branches', JSON.stringify(savedBranches.value));
      if (currentBranch.value === branch) {
        clearBranch();
      } else if (selectedBranch.value === branch) {
        selectedBranch.value = '';
      }
    };

    // ========== 历史记录 ==========
    const loadHistory = async (reset) => {
      if (reset) {
        historyPage.value = 1;
        historyHasMore.value = true;
        historyList.value = [];
      }
      if (!historyHasMore.value || historyLoading.value) return;
      historyLoading.value = true;
      try {
        const data = await fetch('/api/chat/sessions?page=' + historyPage.value + '&size=' + historyPageSize).then(r => r.json());
        historyList.value = historyList.value.concat(data);
        historyHasMore.value = data.length >= historyPageSize;
        historyPage.value++;
      } catch (e) {
        ElementPlus.ElMessage.error('加载历史记录失败');
      } finally {
        historyLoading.value = false;
      }
    };

    const onHistoryScroll = (e) => {
      const wrap = e.target || e;
      if (wrap.scrollHeight - wrap.scrollTop - wrap.clientHeight < 50) {
        loadHistory(false);
      }
    };

    const deleteHistory = async (sid) => {
      try {
        await ElementPlus.ElMessageBox.confirm('确定删除该对话记录？', '确认删除', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning'
        });
        await fetch('/api/chat/session/' + encodeURIComponent(sid), { method: 'DELETE' });
        historyList.value = historyList.value.filter(function(h) { return h.sessionId !== sid; });
        ElementPlus.ElMessage.success('已删除');
      } catch (e) {
        // 取消或失败，忽略
      }
    };

    const parseStreamJson = (raw) => {
      if (!raw) return [];
      const segments = [];

      function appendText(text) {
        const last = segments.length > 0 ? segments[segments.length - 1] : null;
        if (last && last.type === 'text') {
          last.content += text;
        } else {
          segments.push({ type: 'text', content: text });
        }
      }

      const lines = raw.split('\n');
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (!line) continue;
        try {
          const json = JSON.parse(line);
          if (json.type === 'stream_event' && json.event) {
            const evt = json.event;
            if (evt.type === 'content_block_start' && evt.content_block) {
              if (evt.content_block.type === 'tool_use') {
                segments.push({ type: 'tool', name: evt.content_block.name, input: '' });
              }
            } else if (evt.type === 'content_block_delta' && evt.delta) {
              if (evt.delta.type === 'text_delta' && evt.delta.text) {
                appendText(evt.delta.text);
              } else if (evt.delta.type === 'input_json_delta' && evt.delta.partial_json) {
                for (let j = segments.length - 1; j >= 0; j--) {
                  if (segments[j].type === 'tool') {
                    segments[j].input += evt.delta.partial_json;
                    break;
                  }
                }
              }
            }
          } else if (json.type === 'user' && json.message && json.message.content) {
            for (const block of json.message.content) {
              if (block.type === 'tool_result') {
                let result = '';
                if (json.tool_use_result && typeof json.tool_use_result === 'string') {
                  result = json.tool_use_result;
                } else if (typeof block.content === 'string') {
                  result = block.content;
                }
                if (result) {
                  segments.push({ type: 'tool_result', content: result.length > 500 ? result.substring(0, 500) + '...' : result });
                }
              }
            }
          } else if (json.type === 'result' && json.result) {
            const hasText = segments.some(function(s) { return s.type === 'text' && s.content.trim(); });
            if (!hasText) {
              segments.push({ type: 'text', content: json.result });
            }
          }
        } catch (e) { /* skip non-JSON */ }
      }
      return segments;
    };

    const viewHistory = async (sid) => {
      try {
        currentHistorySessionId.value = sid;
        const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/messages').then(r => r.json());
        historyMessages.value = data.map(function(msg) {
          if (msg.role === 'assistant' && msg.content && msg.content.startsWith('{')) {
            return Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content) });
          }
          return msg;
        });
        historyDrawerVisible.value = true;
      } catch (e) {
        ElementPlus.ElMessage.error('加载消息失败');
      }
    };

    const resumeHistory = async (session) => {
      try {
        const data = await fetch('/api/chat/session/' + encodeURIComponent(session.sessionId) + '/messages').then(r => r.json());
        sessionId.value = session.sessionId;
        resumeId.value = session.resumeId || '';
        workingDir.value = session.workingDir;
        agentType.value = session.agentType;
        messages.value = [];
        data.forEach(function(msg) {
          if (msg.role === 'user') {
            messages.value.push({ role: 'user', text: msg.content });
          } else if (msg.role === 'assistant') {
            const segments = msg.content && msg.content.startsWith('{') ? parseStreamJson(msg.content) : [{ type: 'text', content: msg.content }];
            messages.value.push({ role: 'agent', segments: segments });
          }
        });
        addMessage('system', '已恢复历史会话');
        fetch('/api/chat/session/' + encodeURIComponent(session.sessionId) + '/commands')
          .then(r => r.json())
          .then(cmds => { slashCommands.value = cmds; })
          .catch(() => {});
        ElementPlus.ElMessage.success('已恢复历史会话');
      } catch (e) {
        ElementPlus.ElMessage.error('恢复会话失败');
      }
    };

    const summarizeSession = async () => {
      if (!currentHistorySessionId.value) return;
      summarizing.value = true;
      try {
        const res = await fetch('/api/chat/session/' + encodeURIComponent(currentHistorySessionId.value) + '/summarize', { method: 'POST' });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text);
        }
        const data = await res.json();
        ElementPlus.ElMessage.success('已生成: ' + data.issueId + ' ' + data.title);
      } catch (e) {
        ElementPlus.ElMessage.error('生成总结失败: ' + (e.message || '未知错误'));
      } finally {
        summarizing.value = false;
      }
    };

    // ========== 定时任务 ==========
    const loadTasks = async () => {
      try {
        const data = await fetch('/api/tasks').then(r => r.json());
        taskList.value = data;
      } catch (e) {
        ElementPlus.ElMessage.error('加载定时任务失败');
      }
    };

    const openTaskDialog = (task) => {
      if (task) {
        taskEditing.value = task.id;
        taskForm.name = task.name;
        taskForm.cronExpr = task.cronExpr;
        taskForm.prompt = task.prompt;
        taskForm.workingDir = task.workingDir;
      } else {
        taskEditing.value = null;
        taskForm.name = '';
        taskForm.cronExpr = '';
        taskForm.prompt = '';
        taskForm.workingDir = currentPath.value;
      }
      taskDialogVisible.value = true;
    };

    const saveTask = async () => {
      taskLoading.value = true;
      try {
        const body = JSON.stringify({
          name: taskForm.name,
          cronExpr: taskForm.cronExpr,
          prompt: taskForm.prompt,
          workingDir: taskForm.workingDir,
        });
        const headers = { 'Content-Type': 'application/json' };
        if (taskEditing.value) {
          const res = await fetch('/api/tasks/' + taskEditing.value, { method: 'PUT', headers, body });
          if (!res.ok) throw new Error(await res.text());
        } else {
          const res = await fetch('/api/tasks', { method: 'POST', headers, body });
          if (!res.ok) throw new Error(await res.text());
        }
        taskDialogVisible.value = false;
        ElementPlus.ElMessage.success(taskEditing.value ? '任务已更新' : '任务已创建');
        await loadTasks();
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + (e.message || '未知错误'));
      } finally {
        taskLoading.value = false;
      }
    };

    const deleteTask = async (id) => {
      try {
        await ElementPlus.ElMessageBox.confirm('确定删除该定时任务？', '确认删除', {
          confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
        });
        await fetch('/api/tasks/' + id, { method: 'DELETE' });
        ElementPlus.ElMessage.success('已删除');
        await loadTasks();
      } catch (e) { /* cancelled or failed */ }
    };

    const toggleTask = async (id) => {
      try {
        await fetch('/api/tasks/' + id + '/toggle', { method: 'POST' });
        await loadTasks();
      } catch (e) {
        ElementPlus.ElMessage.error('切换失败');
      }
    };

    const runTask = async (id) => {
      try {
        await fetch('/api/tasks/' + id + '/run', { method: 'POST' });
        ElementPlus.ElMessage.success('任务已触发，结果将在历史对话中查看');
      } catch (e) {
        ElementPlus.ElMessage.error('触发失败');
      }
    };

    const setCronPreset = (expr) => {
      taskForm.cronExpr = expr;
    };

    // ========== 命令弹窗 ==========
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
        const textarea = document.querySelector('textarea');
        if (textarea) textarea.focus();
      });
    };

    const hideCommandPopup = () => {
      showCommandPopup.value = false;
    };

    // ========== SSE 消息发送 ==========
    const sendMessageStream = async () => {
      if (!currentPath.value || !userInput.value.trim() || sending.value) return;
      try {
        await ensureSession();
      } catch (error) {
        ElementPlus.ElMessage.error('创建会话失败: ' + error.message);
        return;
      }

      const message = userInput.value.trim();
      addMessage('user', message);
      userInput.value = '';
      sending.value = true;

      const msgIndex = messages.value.length;
      messages.value.push({ role: 'agent', segments: [] });

      let url = '/api/chat/session/' + encodeURIComponent(sessionId.value) + '/message/stream?message=' + encodeURIComponent(message);
      if (env.value) {
        url += '&env=' + encodeURIComponent(env.value);
      }
      if (resumeId.value) {
        url += '&resumeId=' + encodeURIComponent(resumeId.value);
      }

      const es = new EventSource(url);
      currentES = es;
      let segments = [];
      let inToolUse = false;

      function appendText(text) {
        const last = segments.length > 0 ? segments[segments.length - 1] : null;
        if (last && last.type === 'text') {
          last.content += text;
        } else {
          segments.push({ type: 'text', content: text });
        }
      }

      function appendToolContent(text) {
        for (let i = segments.length - 1; i >= 0; i--) {
          if (segments[i].type === 'tool') {
            segments[i].content += text;
            return;
          }
        }
        segments.push({ type: 'tool', name: 'Tool', content: text });
      }

      function flushSegments() {
        messages.value[msgIndex].segments = segments.map(function(s) { return { type: s.type, name: s.name, content: s.content }; });
        nextTick(() => {
          if (chatContainer.value) {
            chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
          }
        });
      }

      es.addEventListener('chunk', (e) => {
        const data = e.data;
        try {
          const json = JSON.parse(data);
          if (json.session_id && !resumeId.value) {
            resumeId.value = json.session_id;
          }
          if (json.type === 'stream_event' && json.event) {
            const evt = json.event;
            if (evt.type === 'content_block_start' && evt.content_block) {
              if (evt.content_block.type === 'tool_use') {
                inToolUse = true;
                segments.push({ type: 'tool', name: evt.content_block.name, content: '' });
              } else {
                inToolUse = false;
              }
            } else if (evt.type === 'content_block_delta' && evt.delta) {
              if (evt.delta.text) {
                appendText(evt.delta.text);
              } else if (evt.delta.partial_json) {
                appendToolContent(evt.delta.partial_json);
              }
            } else if (evt.type === 'content_block_stop') {
              inToolUse = false;
            }
          } else if (json.type === 'assistant' && json.message && json.message.content) {
            const hasContent = segments.some(function(s) { return s.content && s.content.trim(); });
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
                  if (tur.file) {
                    resultText = '[文件: ' + tur.file.filePath + ' (' + tur.file.numLines + '行)]';
                  } else if (typeof tur === 'string') {
                    resultText = tur;
                  } else if (tur.type === 'text' && typeof block.content === 'string') {
                    resultText = block.content;
                  }
                }
                if (!resultText && typeof block.content === 'string') {
                  resultText = block.content;
                }
                if (resultText) {
                  if (resultText.length > 2000) {
                    resultText = resultText.substring(0, 2000) + '\n... (共 ' + resultText.length + ' 字符，已截断)';
                  }
                  appendToolContent('\n' + resultText);
                }
              }
            }
          } else if (json.type === 'result' && json.result) {
            const hasContent = segments.some(function(s) { return s.content && s.content.trim(); });
            if (!hasContent) {
              segments = [{ type: 'text', content: json.result }];
            }
          }
        } catch (err) {
          if (data && !data.startsWith('{') && !data.startsWith('[')) {
            appendText(data);
          }
        }
        flushSegments();
      });

      es.addEventListener('exit', (e) => {
        flushSegments();
        addMessage('system', e.data === '0' ? '任务已完成' : '进程异常退出，退出码: ' + e.data);
        es.close();
        currentES = null;
        sending.value = false;
      });

      es.addEventListener('error', (e) => {
        addMessage('error', e.data || 'SSE 连接错误');
        es.close();
        currentES = null;
        sending.value = false;
      });

      es.onerror = () => {
        es.close();
        currentES = null;
        sending.value = false;
      };
    };

    // ========== 生命周期 ==========
    onMounted(async () => {
      await init();
      await loadHistory(true);
      await loadTasks();
    });

    watch(currentPath, () => {
      sessionId.value = '';
      messages.value = [];
      resumeId.value = '';
      loadSlashCommands();
    });

    watch(userInput, (val) => {
      if (val && val.startsWith('/') && val.indexOf(' ') < 0 && slashCommands.value.length > 0) {
        showCommandPopup.value = true;
        selectedCommandIdx.value = 0;
      } else {
        showCommandPopup.value = false;
      }
    });

    async function doLogout() {
      try {
        await fetch('/api/auth/logout', { method: 'POST' });
      } catch (e) {
        // ignore
      }
      window.location.href = '/login.html';
    }

    return {
      roots,
      selectedRoot,
      currentPath,
      folderList,
      agentType,
      sessionId,
      workingDir,
      resumeId,
      env,
      envList,
      username,
      messages,
      userInput,
      starting,
      sending,
      chatContainer,
      handleRootChange,
      loadList,
      newConversation,
      insertNewline,
      clearContext,
      onEnvChange,
      stopSession,
      slashCommands,
      showCommandPopup,
      selectedCommandIdx,
      filteredCommands,
      handleEnter,
      handleArrowUp,
      handleArrowDown,
      handleTab,
      selectCommand,
      hideCommandPopup,
      sendMessageStream,
      formatSize,
      handleFileCommand,
      selectedBranch,
      currentBranch,
      switchingBranch,
      savedBranches,
      switchResult,
      removingBranch,
      switchBranch,
      clearBranch,
      removeSavedBranch,
      onUploadSuccess,
      onUploadError,
      renderMarkdown,
      isToolExpanded,
      toggleTool,
      historyList,
      historyLoading,
      historyHasMore,
      historyMessages,
      historyDrawerVisible,
      loadHistory,
      onHistoryScroll,
      deleteHistory,
      viewHistory,
      resumeHistory,
      summarizeSession,
      summarizing,
      formatTime,
      escapeHtml,
      taskList,
      taskDialogVisible,
      taskEditing,
      taskForm,
      taskLoading,
      loadTasks,
      openTaskDialog,
      saveTask,
      deleteTask,
      toggleTask,
      runTask,
      setCronPreset,
      workspaceDialogVisible,
      taskManagerVisible,
      sidebarVisible,
      isMobile,
      groupedHistory,
      doLogout,
    };
  }
});

app.use(ElementPlus);
// 全局注册 Element Plus 图标组件
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, comp);
  app.component(name.toLowerCase(), comp);
  const kebab = name.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
  if (kebab !== name.toLowerCase()) {
    app.component(kebab, comp);
  }
}
app.mount('#app');
