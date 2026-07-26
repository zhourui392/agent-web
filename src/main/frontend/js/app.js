import {
  formatSize,
  renderMarkdown,
  imageUrl,
  formatTime,
  escapeHtml,
  IMAGE_PATH_RE
} from './lib/formatters.js';
import { copySegment } from './lib/clipboard.js';
import { shareSession } from './lib/share-session.js';
import ChatPanel from './components/chat-panel.js';
import { createApp, ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { setupElementPlus } from './element-plus-setup.js';
import 'element-plus/dist/index.css';
import { installAuthInterceptor } from './lib/auth-interceptor.js';
import { useAuth } from './composables/useAuth.js';
import { useFileSystem } from './composables/useFileSystem.js';
import { useWorktree } from './composables/useWorktree.js';
import { useHistory } from './composables/useHistory.js';
import { useScheduledTask } from './composables/useScheduledTask.js';

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
    // worktree 从 composable 引入(FE-R3.3 拆出,原内联状态/方法删除)
    const {
      selectedBranch, currentBranch, switchingBranch, savedBranches, switchResult,
      removingBranch, originalWorkspacePath, updatingBranch, updateResult,
      worktreeBranches, branchPopoverVisible, branchOptions,
      saveWorktreeState, clearWorktreeState, loadWorktreeBranches,
      switchBranch, updateBranch, clearBranch, removeSavedBranch,
      restoreWorktreeState
    } = useWorktree({ selectedRoot, currentPath, loadList });
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
    // 历史 + 定时任务 从 composable 引入(FE-R3.4 拆出,原内联状态/方法删除)
    const {
      historyList, historyPage, historyHasMore, historyLoading,
      historyMessages, historyDrawerVisible, currentHistorySessionId,
      groupedHistory, loadHistory, onHistoryScroll, canDelete,
      deleteHistory, viewHistory, resumeHistory, shareSessionFor
    } = useHistory({ currentUserId, agentType, activeResumeId, activeSessionId });
    const {
      taskList, taskDialogVisible, taskEditing, taskForm, taskLoading, taskManagerVisible,
      loadTasks, openTaskDialog, saveTask, deleteTask, toggleTask, runTask, setCronPreset
    } = useScheduledTask({ currentPath });

    // --- chat-rag 召回开关探测 (召回历史浏览已迁至管理后台 /admin/refinery.html) ---
    const chatRagEnabled = ref(false);

    const sidebarVisible = ref(false);
    const isMobile = ref(window.innerWidth <= 768);

    window.addEventListener('resize', () => {
      isMobile.value = window.innerWidth <= 768;
      if (!isMobile.value) sidebarVisible.value = false;
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
      // fs roots(useFileSystem.initFileSystem) + worktree 恢复(useWorktree.restoreWorktreeState)
      const data = await initFileSystem();
      if (data.length > 0) {
        const restored = await restoreWorktreeState(data);
        if (!restored) await setDefaultRoot();
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
