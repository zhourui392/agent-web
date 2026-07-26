import {
  formatSize,
  renderMarkdown,
  imageUrl,
  formatTime,
  escapeHtml,
  IMAGE_PATH_RE
} from './lib/formatters.js';
import { copySegment } from './lib/clipboard.js';
import { mapMessages } from './lib/message-view.js';
import { shareSession } from './lib/share-session.js';
import ChatPanel from './components/chat-panel.js';
import { createApp, ref, reactive, computed, onMounted, nextTick, watch } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { setupElementPlus } from './element-plus-setup.js';
import 'element-plus/dist/index.css';
import { installAuthInterceptor } from './lib/auth-interceptor.js';
import { useAuth } from './composables/useAuth.js';
import { useFileSystem } from './composables/useFileSystem.js';

// 全局 401 拦截(模块级副作用,从 lib/auth-interceptor.ts 引入,原内联 IIFE 抽出)
installAuthInterceptor();

const app = createApp({
  setup() {
    // auth + file-system 从 composable 引入(FE-R3.2 拆出,原内联状态/方法删除)
    const {
      authEnabled, username, currentUserId, canUseScheduledTask, initAuth, doLogout
    } = useAuth();
    const {
      roots, selectedRoot, workspaceCandidatePath, currentPath, folderList,
      workspaceDialogVisible, previewVisible, previewTitle, previewHtml, previewLoading,
      loadList, handleRootChange, openWorkspaceDialog, confirmWorkspace,
      handleFileCommand, closePreview, isMarkdown, onUploadSuccess, onUploadError,
      initFileSystem, setDefaultRoot
    } = useFileSystem();
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
    // 驱动顶栏 Agent 选择器锁定,并作为 initialSessionId/initialResumeId 传给组件触发 resume。
    const activeSessionId = ref('');
    const activeResumeId = ref('');
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
    const taskManagerVisible = ref(false);

    // --- chat-rag 召回开关探测 (召回历史浏览已迁至管理后台 /admin/refinery.html) ---
    const chatRagEnabled = ref(false);

    const sidebarVisible = ref(false);
    const isMobile = ref(window.innerWidth <= 768);

    window.addEventListener('resize', () => {
      isMobile.value = window.innerWidth <= 768;
      if (!isMobile.value) sidebarVisible.value = false;
    });

    const groupedHistory = computed(() => {
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
    const branchOptions = computed(() => {
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
      // auth: useAuth.initAuth(未登录跳转,返回 false 时 init 终止)
      const authed = await initAuth();
      if (!authed) return;
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
      // fs roots(useFileSystem.initFileSystem) + worktree 恢复(留 app.js,FE-R3.3 拆 useWorktree)
      const data = await initFileSystem();
      if (data.length > 0) {
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
        await setDefaultRoot();
      }
    };

    // ========== 会话管理(宿主侧) ==========
    // 聊天闭环已迁入 ChatPanel 组件;宿主只管「开新对话」:置空 active*,
    // 组件经 initialSessionId='' 自行清空,顶栏 agent 恢复用户偏好。
    const newConversation = async () => {
      activeSessionId.value = '';
      activeResumeId.value = '';
      agentType.value = readPreferredAgentType();
      ElMessage.success('新对话已就绪');
    };

    // formatTime / escapeHtml 由 lib/formatters.js 提供,顶部已解构。

    // ========== Agent 类型 ==========
    const onAgentTypeChange = (val) => {
      // 会话开始后下拉是 disabled 的, 这里只处理新建态的切换
      localStorage.setItem('agent_type', val);
      ElMessage.info({ message: '已切换到 ' + val, duration: 2000 });
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
        ElMessage.success('已切换到 ' + trimmedBranch + '，' + switched + ' 个服务');
      } catch (e) {
        ElMessage.error('切换分支失败: ' + e.message);
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
          ElMessage.success('已更新 ' + ok + ' 个服务');
        } else {
          ElMessage.warning('成功 ' + ok + '，失败 ' + failed);
        }
      } catch (e) {
        ElMessage.error('更新失败: ' + e.message);
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
        ElMessage.success('已清理分支 ' + branch + ' 的 worktree');
      } catch (e) {
        ElMessage.warning('清理 worktree 失败，已移除标签');
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
        let activeBySession = {};
        try {
          const activeResponse = await fetch('/api/chat/runs/active');
          if (activeResponse.ok) {
            const activeRuns = await activeResponse.json();
            activeRuns.forEach(run => { activeBySession[run.sessionId] = true; });
          }
        } catch (e) { /* feature flag 关闭或探测失败时不影响历史列表 */ }
        const decorated = data.map(item => Object.assign({}, item, {
          running: !!activeBySession[item.sessionId],
        }));
        historyList.value = historyList.value.concat(decorated);
        historyHasMore.value = data.length >= historyPageSize;
        historyPage.value++;
      } catch (e) {
        ElMessage.error('加载历史记录失败');
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
        await ElMessageBox.confirm('确定删除该对话记录？', '确认删除', {
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
          ElMessage.error(r.status === 403 ? '只能删除自己创建的对话' : '删除失败');
          return;
        }
        historyList.value = historyList.value.filter(function(h) { return h.sessionId !== sid; });
        ElMessage.success('已删除');
      } catch (e) {
        ElMessage.error('删除失败');
      }
    };

    const viewHistory = async (sid) => {
      try {
        currentHistorySessionId.value = sid;
        const data = await fetch('/api/chat/session/' + encodeURIComponent(sid) + '/messages').then(r => r.json());
        historyMessages.value = mapMessages(data, { withRecall: true });
        historyDrawerVisible.value = true;
      } catch (e) {
        ElMessage.error('加载消息失败');
      }
    };

    // 恢复历史会话:设宿主 Agent 锁定态与 active*,由 ChatPanel 经 initialSessionId 拉消息续聊。
    // 先设 resumeId 再设 sessionId,确保组件 watch(initialSessionId) 触发时拿到正确的 initialResumeId。
    const resumeHistory = (session) => {
      if (!session || !session.sessionId) return;
      if (session.agentType) agentType.value = session.agentType;
      activeResumeId.value = session.resumeId || '';
      activeSessionId.value = session.sessionId;
    };

    const shareSessionFor = (sid) => shareSession(
      (typeof sid === 'string' && sid) ? sid : currentHistorySessionId.value
    );

    // ========== 定时任务 ==========
    const loadTasks = async () => {
      try {
        const data = await fetch('/api/tasks').then(r => r.json());
        taskList.value = data;
      } catch (e) {
        ElMessage.error('加载定时任务失败');
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
        ElMessage.success(taskEditing.value ? '任务已更新' : '任务已创建');
        await loadTasks();
      } catch (e) {
        ElMessage.error('保存失败: ' + (e.message || '未知错误'));
      } finally {
        taskLoading.value = false;
      }
    };

    const deleteTask = async (id) => {
      try {
        await ElMessageBox.confirm('确定删除该定时任务？', '确认删除', {
          confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
        });
        await fetch('/api/tasks/' + id, { method: 'DELETE' });
        ElMessage.success('已删除');
        await loadTasks();
      } catch (e) { /* cancelled or failed */ }
    };

    const toggleTask = async (id) => {
      try {
        await fetch('/api/tasks/' + id + '/toggle', { method: 'POST' });
        await loadTasks();
      } catch (e) {
        ElMessage.error('切换失败');
      }
    };

    const runTask = async (id) => {
      try {
        await fetch('/api/tasks/' + id + '/run', { method: 'POST' });
        ElMessage.success('任务已触发，结果将在历史对话中查看');
      } catch (e) {
        ElMessage.error('触发失败');
      }
    };

    const setCronPreset = (expr) => {
      taskForm.cronExpr = expr;
    };

    // ========== ChatPanel 宿主回调 ==========
    // 组件新建会话:回填 active* 锁定顶栏 Agent,并刷新历史列表让新会话显现
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

    return {
      roots,
      selectedRoot,
      workspaceCandidatePath,
      currentPath,
      folderList,
      previewVisible,
      previewTitle,
      previewHtml,
      previewLoading,
      isMarkdown,
      closePreview,
      agentType,
      activeSessionId,
      activeResumeId,
      username,
      starting,
      handleRootChange,
      loadList,
      openWorkspaceDialog,
      confirmWorkspace,
      newConversation,
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
    };
  }
});

setupElementPlus(app);
// 注册可复用的 ChatPanel 组件 (ES import from components/chat-panel.js)
app.component('chat-panel', ChatPanel);
app.mount('#app');
