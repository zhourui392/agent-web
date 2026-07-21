// ===== 全局 401 拦截 =====
// 任何 API 响应 401 且 body 带 loginUrl 时，自动跳本站 /login.html。
// loginUrl 由后端 SessionAuthFilter / AuthController 提供，已带 ?redirect=<原路径>。
// 覆盖所有经 window.fetch 的调用；SSE(EventSource) 不走 fetch，单独在错误处理里兜底。
(function installAuthInterceptor() {
  const rawFetch = window.fetch.bind(window);
  let redirecting = false;
  window.fetch = async function (...args) {
    const res = await rawFetch(...args);
    if (res.status === 401 && !redirecting) {
      try {
        const data = await res.clone().json();
        if (data && data.loginUrl) {
          redirecting = true;
          window.location.href = window.withBase(data.loginUrl);
        } else {
          // 401 但响应没带 loginUrl(老接口/非 JSON 兜底): 直接跳本站登录页, 带回当前路径。
          redirecting = true;
          const redirect = encodeURIComponent(window.location.pathname + window.location.search);
          window.location.href = window.withBase('/login.html?redirect=' + redirect);
        }
      } catch (e) {
        redirecting = true;
        const redirect = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.href = window.withBase('/login.html?redirect=' + redirect);
      }
    }
    return res;
  };
})();

const { createApp, ref, reactive, computed, onMounted, nextTick, watch } = Vue;

const app = createApp({
  setup() {
    // 数据
    const roots = ref([]);
    const selectedRoot = ref('');
    const currentPath = ref('');
    const folderList = ref([]);
    // 对话默认模型由管理后台控制: GET /api/chat/agent-default 返回 {agentType, version}。
    // 「强制全员跟随」: 本地记录的版本(agent_type_force_version)与服务端不一致时, 覆盖本地选择
    // (agent_type)并切到服务端默认、写回新版本; 一致则尊重用户后续手动选择。
    // 同步初始化先用本地缓存(或 CLAUDE 兜底), 服务端版本回来后由 applyServerAgentDefault 再按需强制。
    const readPreferredAgentType = () => {
      const stored = localStorage.getItem('agent_type');
      return stored || 'CLAUDE';
    };
    const agentType = ref(readPreferredAgentType());
    // 当前 ChatPanel 的会话标识:由组件 session-created 回填 / 宿主点历史时设置,
    // 驱动顶栏 env/agent 选择器锁定,并作为 initialSessionId/initialResumeId 传给组件触发 resume。
    const activeSessionId = ref('');
    const activeResumeId = ref('');
    const username = ref('admin');
    const currentUserId = ref('');
    // 登录用户均可管理自己的定时任务
    const canUseScheduledTask = computed(() => Boolean(currentUserId.value));
    const env = ref('');
    const envList = ref([]);
    const starting = ref(false);
    const historyList = ref([]);
    const historyPage = ref(1);
    const historyPageSize = 20;
    const historyHasMore = ref(true);
    const historyLoading = ref(false);
    const historyMessages = ref([]);
    const historyDrawerVisible = ref(false);
    const currentHistorySessionId = ref('');
    const selectedBranch = ref('');
    const currentBranch = ref('');
    const switchingBranch = ref(false);
    const savedBranches = ref(JSON.parse(localStorage.getItem('agent_saved_branches') || '[]'));
    const switchResult = ref(null);
    const removingBranch = ref('');
    const originalWorkspacePath = ref('');
    const updatingBranch = ref(false);
    const updateResult = ref(null);
    const worktreeBranches = ref([]);
    const branchPopoverVisible = ref(false);

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

    // --- chat-rag 召回开关探测 (召回历史浏览已迁至管理后台 /admin/refinery.html) ---
    const chatRagEnabled = ref(false);

    // --- 用户建议 / 反馈 ---
    const suggestionDialogVisible = ref(false);
    const suggestionTab = ref('submit');
    const suggestionSubmitting = ref(false);
    const suggestionLoading = ref(false);
    const suggestions = ref([]);
    const suggestionForm = reactive({
      title: '',
      content: ''
    });

    const sidebarVisible = ref(false);
    const isMobile = ref(window.innerWidth <= 768);
    const authEnabled = ref(true);

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

    // 下拉选项 = 本地已有 worktree 分支 + localStorage 保存过的分支，去重后保留 worktree 优先顺序
    const branchOptions = Vue.computed(() => {
      const seen = new Set();
      const result = [];
      for (const b of worktreeBranches.value) {
        if (b && !seen.has(b)) { seen.add(b); result.push(b); }
      }
      for (const b of savedBranches.value) {
        if (b && !seen.has(b)) { seen.add(b); result.push(b); }
      }
      return result;
    });

    // ========== 初始化 ==========
    const init = async () => {
      try {
        const authStatus = await fetch('/api/auth/status').then(r => r.json());
        authEnabled.value = !!authStatus.authEnabled;
        if (!authStatus.authenticated && authStatus.loginUrl) {
          window.location.href = window.withBase(authStatus.loginUrl);
          return;
        }
        if (authStatus.username) {
          username.value = authStatus.username;
        }
        if (authStatus.userId) {
          currentUserId.value = authStatus.userId;
        }
      } catch (e) {
        // 忽略，保留默认值
      }
      // 探测 chat-rag 是否启用: enabled=false 时 controller 不装配, /chunks 返回 404 → 隐藏入口
      try {
        const probe = await fetch('/api/refinery/chunks?page=1&size=1');
        chatRagEnabled.value = probe.ok;
      } catch (e) {
        // 忽略，保留 false
      }
      // 对话默认模型「强制全员跟随」: 服务端版本与本地不一致即覆盖本地选择并切换(仅在无进行中会话时切)。
      try {
        const def = await fetch('/api/chat/agent-default').then(r => r.json());
        if (def && def.agentType) {
          const appliedVer = localStorage.getItem('agent_type_force_version');
          if (appliedVer !== String(def.version)) {
            localStorage.setItem('agent_type', def.agentType);
            localStorage.setItem('agent_type_force_version', String(def.version));
            if (!activeSessionId.value) {
              agentType.value = def.agentType;
            }
          }
        }
      } catch (e) {
        // 忽略: 取不到默认值就保留本地选择
      }
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

          selectedRoot.value = data[0];
          currentPath.value = data[0];
          await loadList(data[0]);
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

    // 纯函数工具从 lib 引入 (避免内嵌定义,便于独立单测);保持原变量名,调用点无需改动
    const {
      formatSize,
      renderMarkdown,
      parseUserMessage,
      imageUrl,
      formatTime,
      escapeHtml,
      parseStreamJson,
      isStreamJson,
      IMAGE_PATH_RE
    } = window.AgentFormatters;

    const handleFileCommand = (command, item) => {
      if (command === 'download') {
        window.open(window.withBase('/api/fs/download?path=' + encodeURIComponent(item.path)), '_blank');
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

    // ========== 会话管理(宿主侧) ==========
    // 聊天闭环已迁入 ChatPanel 组件;宿主只管「开新对话」:置空 active*,
    // 组件经 initialSessionId='' 自行清空,顶栏 agent 恢复用户偏好。
    const newConversation = async () => {
      activeSessionId.value = '';
      activeResumeId.value = '';
      agentType.value = readPreferredAgentType();
      ElementPlus.ElMessage.success('新对话已就绪');
    };

    // copySegment 供历史详情抽屉的 assistant 段落复制按钮使用(聊天区复制在组件内自带)
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

    // formatTime / escapeHtml 由 lib/formatters.js 提供,顶部已解构。

    // ========== 环境与分支 ==========
    // env 一旦会话开始就锁定 (单选 disabled), 这里只处理新建态切换
    const onEnvChange = (val) => {
      const found = envList.value.find(e => e.key === val);
      const label = found ? found.label : '无环境';
      ElementPlus.ElMessage.info('已切换到' + label);
      if (val !== 'test') {
        clearBranch();
      }
    };

    // ========== Agent 类型 ==========
    const onAgentTypeChange = (val) => {
      // 会话开始后下拉是 disabled 的, 这里只处理新建态的切换
      localStorage.setItem('agent_type', val);
      ElementPlus.ElMessage.info({ message: '已切换到 ' + val, duration: 2000 });
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

    const loadWorktreeBranches = async () => {
      const wsPath = originalWorkspacePath.value || currentPath.value;
      if (!wsPath) { worktreeBranches.value = []; return; }
      try {
        const data = await fetch('/api/worktree/list?workspacePath=' + encodeURIComponent(wsPath)).then(r => r.json());
        worktreeBranches.value = Array.isArray(data) ? data.map(w => w.branch).filter(Boolean) : [];
      } catch (e) {
        worktreeBranches.value = [];
      }
    };

    const switchBranch = async () => {
      const trimmedBranch = (selectedBranch.value || '').trim();
      if (!trimmedBranch || !currentPath.value) return;
      selectedBranch.value = trimmedBranch;
      switchingBranch.value = true;
      switchResult.value = null;
      updateResult.value = null;
      try {
        if (!originalWorkspacePath.value) {
          originalWorkspacePath.value = currentPath.value;
        }
        const wsPath = originalWorkspacePath.value || currentPath.value;
        const res = await fetch('/api/worktree/switch', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ workspacePath: wsPath, branch: trimmedBranch })
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text);
        }
        const data = await res.json();
        if (!savedBranches.value.includes(trimmedBranch)) {
          savedBranches.value.push(trimmedBranch);
          localStorage.setItem('agent_saved_branches', JSON.stringify(savedBranches.value));
        }
        currentBranch.value = trimmedBranch;
        switchResult.value = data.repos;
        currentPath.value = data.worktreePath;
        await loadList(data.worktreePath);
        saveWorktreeState();
        loadWorktreeBranches();
        const switched = data.repos.filter(function(r) {
          return r.created && r.actualBranch === trimmedBranch;
        }).length;
        ElementPlus.ElMessage.success('已切换到 ' + trimmedBranch + '，' + switched + ' 个服务');
      } catch (e) {
        ElementPlus.ElMessage.error('切换分支失败: ' + e.message);
      } finally {
        switchingBranch.value = false;
      }
    };

    const updateBranch = async () => {
      if (!currentBranch.value) return;
      const wsPath = originalWorkspacePath.value || currentPath.value;
      if (!wsPath) return;
      updatingBranch.value = true;
      updateResult.value = null;
      switchResult.value = null;
      try {
        const res = await fetch('/api/worktree/update', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ workspacePath: wsPath, branch: currentBranch.value })
        });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text);
        }
        const data = await res.json();
        updateResult.value = data.repos;
        const ok = data.repos.filter(function(r) { return r.updated; }).length;
        const failed = data.repos.filter(function(r) { return !r.updated && !r.skipped; }).length;
        if (failed === 0) {
          ElementPlus.ElMessage.success('已更新 ' + ok + ' 个服务');
        } else {
          ElementPlus.ElMessage.warning('成功 ' + ok + '，失败 ' + failed);
        }
      } catch (e) {
        ElementPlus.ElMessage.error('更新失败: ' + e.message);
      } finally {
        updatingBranch.value = false;
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
      updateResult.value = null;
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
      loadWorktreeBranches();
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

    // 仅创建者(或无归属老数据)可删除; 删他人会话的按钮隐藏, 后端再兜底 403
    const canDelete = (h) => !h.userId || h.userId === currentUserId.value;

    const deleteHistory = async (sid) => {
      try {
        await ElementPlus.ElMessageBox.confirm('确定删除该对话记录？', '确认删除', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning'
        });
      } catch (e) {
        return; // 用户取消
      }
      try {
        const r = await fetch('/api/chat/session/' + encodeURIComponent(sid), { method: 'DELETE' });
        if (!r.ok) {
          ElementPlus.ElMessage.error(r.status === 403 ? '只能删除自己创建的对话' : '删除失败');
          return;
        }
        historyList.value = historyList.value.filter(function(h) { return h.sessionId !== sid; });
        ElementPlus.ElMessage.success('已删除');
      } catch (e) {
        ElementPlus.ElMessage.error('删除失败');
      }
    };

    const viewHistory = async (sid) => {
      try {
        currentHistorySessionId.value = sid;
        const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/messages').then(r => r.json());
        historyMessages.value = data.map(function(msg) {
          let recall = null;
          if (msg.role === 'assistant' && msg.recall) {
            try { recall = JSON.parse(msg.recall); } catch (e) { recall = null; }
          }
          if (msg.role === 'assistant' && isStreamJson(msg.content)) {
            return Object.assign({}, msg, { parsedSegments: parseStreamJson(msg.content), recall: recall, recallOpen: false });
          }
          if (msg.role === 'user') {
            const parsed = parseUserMessage(msg.content);
            return Object.assign({}, msg, { bodyText: parsed.text, images: parsed.images });
          }
          return Object.assign({}, msg, { recall: recall, recallOpen: false });
        });
        historyDrawerVisible.value = true;
      } catch (e) {
        ElementPlus.ElMessage.error('加载消息失败');
      }
    };

    // 恢复历史会话:设宿主 agent/env 锁定态与 active*,由 ChatPanel 经 initialSessionId 拉消息续聊。
    // 先设 resumeId 再设 sessionId,确保组件 watch(initialSessionId) 触发时拿到正确的 initialResumeId。
    const resumeHistory = (session) => {
      if (!session || !session.sessionId) return;
      if (session.agentType) agentType.value = session.agentType;
      env.value = session.env || '';
      activeResumeId.value = session.resumeId || '';
      activeSessionId.value = session.sessionId;
    };

    const copyToClipboard = async (text) => {
      if (navigator.clipboard && window.isSecureContext) {
        try {
          await navigator.clipboard.writeText(text);
          return true;
        } catch (e) { /* fall through */ }
      }
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
      } catch (e) {
        return false;
      }
    };

    const shareSession = async (sid) => {
      // 头条按钮显式传当前会话 id；历史详情抽屉按钮 @click="shareSession" 传入的是事件对象，回退到 currentHistorySessionId
      const target = (typeof sid === 'string' && sid) ? sid : currentHistorySessionId.value;
      if (!target) return;
      try {
        const res = await fetch('/api/chat/session/' + encodeURIComponent(target) + '/share', { method: 'POST' });
        if (!res.ok) {
          const text = await res.text();
          throw new Error(text);
        }
        const data = await res.json();
        const shareUrl = window.location.origin + window.withBase('/share.html?token=' + data.shareToken);
        const copied = await copyToClipboard(shareUrl);
        if (copied) {
          ElementPlus.ElMessage.success('分享链接已复制到剪贴板');
        } else {
          ElementPlus.ElMessageBox.alert(shareUrl, '分享链接（请手动复制）', {
            confirmButtonText: '关闭',
            customClass: 'share-link-dialog'
          });
        }
      } catch (e) {
        ElementPlus.ElMessage.error('生成分享链接失败: ' + (e.message || '未知错误'));
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

    // ========== 用户建议 / 反馈 ==========
    function openSuggestionDialog() {
      suggestionDialogVisible.value = true;
      loadSuggestions();
    }

    async function submitSuggestion() {
      if (!suggestionForm.content || !suggestionForm.content.trim()) {
        ElementPlus.ElMessage.warning('请填写建议内容');
        return;
      }
      suggestionSubmitting.value = true;
      try {
        const res = await fetch('/api/user-suggestions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            title: suggestionForm.title,
            content: suggestionForm.content
          })
        });
        if (!res.ok) {
          throw new Error(await res.text());
        }
        suggestionForm.title = '';
        suggestionForm.content = '';
        ElementPlus.ElMessage.success('建议已提交');
        suggestionTab.value = 'status';
        await loadSuggestions();
      } catch (e) {
        ElementPlus.ElMessage.error('提交失败: ' + (e.message || '未知错误'));
      } finally {
        suggestionSubmitting.value = false;
      }
    }

    async function loadSuggestions() {
      suggestionLoading.value = true;
      try {
        const data = await fetch('/api/user-suggestions?limit=50').then(r => r.json());
        suggestions.value = Array.isArray(data) ? data : [];
      } catch (e) {
        ElementPlus.ElMessage.error('加载建议状态失败');
      } finally {
        suggestionLoading.value = false;
      }
    }

    function suggestionStatusType(status) {
      const map = {
        PENDING: 'warning',
        PROCESSING: 'primary',
        REPLIED: 'success',
        CLOSED: 'info'
      };
      return map[status] || 'info';
    }

    // ========== ChatPanel 宿主回调 ==========
    // 组件新建会话:回填 active* 锁定顶栏 env/agent,并刷新历史列表让新会话显现
    const onSessionCreated = (payload) => {
      activeSessionId.value = payload.sessionId;
      activeResumeId.value = '';
      loadHistory(true);
    };
    // 组件流结束 / 回退后:重拉历史列表,同步标题与消息数
    const onRefreshHistory = () => {
      loadHistory(true);
    };

    // ========== 生命周期 ==========
    onMounted(async () => {
      await init();
      await loadHistory(true);
      await loadTasks();
    });

    // 切工作目录:置空 active*,ChatPanel 经 workingDir / initialSession 自行清空并重载命令
    watch(currentPath, () => {
      activeSessionId.value = '';
      activeResumeId.value = '';
    });

    watch(branchPopoverVisible, (v) => {
      if (v) loadWorktreeBranches();
    });

    // 跳转需求看板页(M0 独立 MPA 页面,与主控台同源共享登录态)
    function goRequirementBoard() {
      window.location.href = window.withBase('/requirement-board.html');
    }

    async function doLogout() {
      // 登出后跳本站 /login.html（loginUrl 由后端返回，已带 ?redirect=）；拿不到则退回首页重新鉴权。
      let loginUrl = '/';
      try {
        const r = await fetch('/api/auth/logout', { method: 'POST' }).then((x) => x.json());
        if (r && r.loginUrl) {
          loginUrl = r.loginUrl;
        }
      } catch (e) {
        // ignore
      }
      window.location.href = window.withBase(loginUrl);
    }

    return {
      withBase: window.withBase,
      roots,
      selectedRoot,
      currentPath,
      folderList,
      agentType,
      activeSessionId,
      activeResumeId,
      env,
      envList,
      username,
      starting,
      handleRootChange,
      loadList,
      newConversation,
      onEnvChange,
      onAgentTypeChange,
      onSessionCreated,
      onRefreshHistory,
      formatSize,
      handleFileCommand,
      selectedBranch,
      currentBranch,
      switchingBranch,
      savedBranches,
      branchOptions,
      branchPopoverVisible,
      switchResult,
      removingBranch,
      switchBranch,
      updateBranch,
      updatingBranch,
      updateResult,
      clearBranch,
      removeSavedBranch,
      onUploadSuccess,
      onUploadError,
      renderMarkdown,
      imageUrl,
      copySegment,
      historyList,
      historyLoading,
      historyHasMore,
      historyMessages,
      historyDrawerVisible,
      loadHistory,
      onHistoryScroll,
      deleteHistory,
      canDelete,
      canUseScheduledTask,
      viewHistory,
      resumeHistory,
      shareSession,
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
      chatRagEnabled,
      sidebarVisible,
      isMobile,
      groupedHistory,
      authEnabled,
      doLogout,
      goRequirementBoard,
      suggestionDialogVisible,
      suggestionTab,
      suggestionForm,
      suggestionSubmitting,
      suggestionLoading,
      suggestions,
      openSuggestionDialog,
      submitSuggestion,
      loadSuggestions,
      suggestionStatusType,
    };
  }
});

app.use(ElementPlus);
// 注册可复用的 ChatPanel 组件(挂在 window.ChatPanel,由 chat-panel.js 提供)
app.component('chat-panel', window.ChatPanel);
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
