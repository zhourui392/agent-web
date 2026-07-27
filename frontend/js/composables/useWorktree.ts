/**
 * useWorktree composable: app.js 主页 worktree 切片(FE-R3.3)。
 *
 * 从 app.js setup 抽出: worktree 状态(selectedBranch/currentBranch/switchingBranch/
 * savedBranches/switchResult/removingBranch/originalWorkspacePath/updatingBranch/
 * updateResult/worktreeBranches/branchPopoverVisible) + branchOptions computed +
 * saveWorktreeState/clearWorktreeState/loadWorktreeBranches/switchBranch/updateBranch/
 * clearBranch/removeSavedBranch + restoreWorktreeState(init 恢复 localStorage)。
 *
 * 与 useFileSystem 的耦合: switch/restore 需切工作目录,故传入 selectedRoot/currentPath/
 * loadList(fs refs/方法)。saveWorktreeState 持久化 currentPath,故 currentPath 亦读。
 *
 * 行为照搬 app.js 原内联实现,零逻辑变更。依赖 ElMessage。
 */
import { ref, computed, type Ref, type ComputedRef } from 'vue';
import { ElMessage } from 'element-plus';

interface SwitchRepo {
  name?: string;
  created?: boolean;
  actualBranch?: string;
  updated?: boolean;
  skipped?: boolean;
}

interface SwitchData {
  worktreePath: string;
  repos: SwitchRepo[];
}

interface FsIntegration {
  selectedRoot: Ref<string>;
  currentPath: Ref<string>;
  loadList: (path?: string) => Promise<void>;
}

export function useWorktree(fs: FsIntegration): {
  selectedBranch: Ref<string>;
  currentBranch: Ref<string>;
  switchingBranch: Ref<boolean>;
  savedBranches: Ref<string[]>;
  switchResult: Ref<SwitchRepo[] | null>;
  removingBranch: Ref<string>;
  originalWorkspacePath: Ref<string>;
  updatingBranch: Ref<boolean>;
  updateResult: Ref<SwitchRepo[] | null>;
  worktreeBranches: Ref<string[]>;
  branchPopoverVisible: Ref<boolean>;
  branchOptions: ComputedRef<string[]>;
  saveWorktreeState: () => void;
  clearWorktreeState: () => void;
  loadWorktreeBranches: () => Promise<void>;
  switchBranch: () => Promise<void>;
  updateBranch: () => Promise<void>;
  clearBranch: () => void;
  removeSavedBranch: (branch: string) => Promise<void>;
  restoreWorktreeState: (fsRoots: string[]) => Promise<boolean>;
} {
  const selectedBranch = ref('');
  const currentBranch = ref('');
  const switchingBranch = ref(false);
  const savedBranches = ref<string[]>(JSON.parse(localStorage.getItem('agent_saved_branches') || '[]'));
  const switchResult = ref<SwitchRepo[] | null>(null);
  const removingBranch = ref('');
  const originalWorkspacePath = ref('');
  const updatingBranch = ref(false);
  const updateResult = ref<SwitchRepo[] | null>(null);
  const worktreeBranches = ref<string[]>([]);
  const branchPopoverVisible = ref(false);

  // 下拉选项 = 本地已有 worktree 分支 + localStorage 保存过的分支,去重后保留 worktree 优先顺序
  const branchOptions = computed(() => {
    const seen = new Set<string>();
    const result: string[] = [];
    for (const b of worktreeBranches.value) {
      if (b && !seen.has(b)) { seen.add(b); result.push(b); }
    }
    for (const b of savedBranches.value) {
      if (b && !seen.has(b)) { seen.add(b); result.push(b); }
    }
    return result;
  });

  const saveWorktreeState = () => {
    localStorage.setItem('agent_worktree_state', JSON.stringify({
      originalWorkspacePath: originalWorkspacePath.value,
      currentBranch: currentBranch.value,
      worktreePath: fs.currentPath.value,
    }));
  };

  const clearWorktreeState = () => {
    localStorage.removeItem('agent_worktree_state');
  };

  const loadWorktreeBranches = async () => {
    const wsPath = originalWorkspacePath.value || fs.currentPath.value;
    if (!wsPath) { worktreeBranches.value = []; return; }
    try {
      const data = await fetch('/api/worktree/list?workspacePath=' + encodeURIComponent(wsPath)).then((r) => r.json());
      worktreeBranches.value = Array.isArray(data) ? data.map((w: { branch?: string }) => w.branch).filter(Boolean) as string[] : [];
    } catch (e) {
      worktreeBranches.value = [];
    }
  };

  const switchBranch = async () => {
    const trimmedBranch = (selectedBranch.value || '').trim();
    if (!trimmedBranch || !fs.currentPath.value) return;
    selectedBranch.value = trimmedBranch;
    switchingBranch.value = true;
    switchResult.value = null;
    updateResult.value = null;
    try {
      if (!originalWorkspacePath.value) {
        originalWorkspacePath.value = fs.currentPath.value;
      }
      const wsPath = originalWorkspacePath.value || fs.currentPath.value;
      const res = await fetch('/api/worktree/switch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workspacePath: wsPath, branch: trimmedBranch }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text);
      }
      const data: SwitchData = await res.json();
      if (!savedBranches.value.includes(trimmedBranch)) {
        savedBranches.value.push(trimmedBranch);
        localStorage.setItem('agent_saved_branches', JSON.stringify(savedBranches.value));
      }
      currentBranch.value = trimmedBranch;
      switchResult.value = data.repos;
      fs.currentPath.value = data.worktreePath;
      await fs.loadList(data.worktreePath);
      saveWorktreeState();
      loadWorktreeBranches();
      const switched = data.repos.filter((r) => r.created && r.actualBranch === trimmedBranch).length;
      ElMessage.success('已切换到 ' + trimmedBranch + '，' + switched + ' 个服务');
    } catch (e) {
      ElMessage.error('切换分支失败: ' + (e as Error).message);
    } finally {
      switchingBranch.value = false;
    }
  };

  const updateBranch = async () => {
    if (!currentBranch.value) return;
    const wsPath = originalWorkspacePath.value || fs.currentPath.value;
    if (!wsPath) return;
    updatingBranch.value = true;
    updateResult.value = null;
    switchResult.value = null;
    try {
      const res = await fetch('/api/worktree/update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ workspacePath: wsPath, branch: currentBranch.value }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(text);
      }
      const data: SwitchData = await res.json();
      updateResult.value = data.repos;
      const ok = data.repos.filter((r) => r.updated).length;
      const failed = data.repos.filter((r) => !r.updated && !r.skipped).length;
      if (failed === 0) {
        ElMessage.success('已更新 ' + ok + ' 个服务');
      } else {
        ElMessage.warning('成功 ' + ok + '，失败 ' + failed);
      }
    } catch (e) {
      ElMessage.error('更新失败: ' + (e as Error).message);
    } finally {
      updatingBranch.value = false;
    }
  };

  const clearBranch = () => {
    if (originalWorkspacePath.value) {
      fs.currentPath.value = originalWorkspacePath.value;
      fs.loadList(originalWorkspacePath.value);
    }
    currentBranch.value = '';
    selectedBranch.value = '';
    switchResult.value = null;
    updateResult.value = null;
    originalWorkspacePath.value = '';
    clearWorktreeState();
  };

  const removeSavedBranch = async (branch: string) => {
    removingBranch.value = branch;
    const wsPath = originalWorkspacePath.value || fs.currentPath.value;
    try {
      await fetch('/api/worktree/remove?workspacePath=' + encodeURIComponent(wsPath)
        + '&branch=' + encodeURIComponent(branch), { method: 'DELETE' });
      ElMessage.success('已清理分支 ' + branch + ' 的 worktree');
    } catch (e) {
      ElMessage.warning('清理 worktree 失败，已移除标签');
    } finally {
      removingBranch.value = '';
    }
    savedBranches.value = savedBranches.value.filter((b) => b !== branch);
    localStorage.setItem('agent_saved_branches', JSON.stringify(savedBranches.value));
    if (currentBranch.value === branch) {
      clearBranch();
    } else if (selectedBranch.value === branch) {
      selectedBranch.value = '';
    }
    loadWorktreeBranches();
  };

  // init 恢复: 从 localStorage agent_worktree_state 恢复 worktree 工作目录。
  // 返回 true=已恢复(调用方不再 setDefaultRoot), false=未恢复(调用方走 setDefaultRoot)。
  const restoreWorktreeState = async (fsRoots: string[]): Promise<boolean> => {
    const saved = JSON.parse(localStorage.getItem('agent_worktree_state') || 'null');
    if (saved && saved.worktreePath && saved.currentBranch) {
      const check = await fetch('/api/fs/list?path=' + encodeURIComponent(saved.worktreePath));
      if (check.ok) {
        const wsRoot = fsRoots.find((root) => saved.originalWorkspacePath.startsWith(root));
        fs.selectedRoot.value = wsRoot || fsRoots[0];
        originalWorkspacePath.value = saved.originalWorkspacePath;
        currentBranch.value = saved.currentBranch;
        selectedBranch.value = saved.currentBranch;
        fs.currentPath.value = saved.worktreePath;
        await fs.loadList(saved.worktreePath);
        return true;
      }
      clearWorktreeState();
    }
    return false;
  };

  return {
    selectedBranch, currentBranch, switchingBranch, savedBranches, switchResult,
    removingBranch, originalWorkspacePath, updatingBranch, updateResult,
    worktreeBranches, branchPopoverVisible, branchOptions,
    saveWorktreeState, clearWorktreeState, loadWorktreeBranches,
    switchBranch, updateBranch, clearBranch, removeSavedBranch,
    restoreWorktreeState,
  };
}