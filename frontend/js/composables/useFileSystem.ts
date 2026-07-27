/**
 * useFileSystem composable: app.js 主页 file-system 切片(FE-R3.2)。
 *
 * 从 app.js setup 抽出: fs 状态(roots/selectedRoot/workspaceCandidatePath/currentPath/
 * folderList/workspaceDialogVisible/preview*) + loadList/handleRootChange/openWorkspaceDialog/
 * confirmWorkspace/handleFileCommand/closePreview/isMarkdown/onUploadSuccess/onUploadError。
 *
 * initFileSystem 只做 fetch /api/fs/roots + 设 roots.value + 返回 data;
 * worktree 路径恢复(localStorage agent_worktree_state)留 app.js init,FE-R3.3 拆 useWorktree 时再迁。
 *
 * 行为照搬 app.js 原内联实现,零逻辑变更。依赖 renderMarkdown(formatters) + ElMessage/ElMessageBox。
 */
import { ref, type Ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { renderMarkdown } from '../lib/formatters.js';

interface FsItem {
  name: string;
  path: string;
  dir?: boolean;
  size?: number;
}

export function useFileSystem(): {
  roots: Ref<string[]>;
  selectedRoot: Ref<string>;
  workspaceCandidatePath: Ref<string>;
  currentPath: Ref<string>;
  folderList: Ref<FsItem[]>;
  workspaceDialogVisible: Ref<boolean>;
  previewVisible: Ref<boolean>;
  previewTitle: Ref<string>;
  previewHtml: Ref<string>;
  previewLoading: Ref<boolean>;
  loadList: (path?: string) => Promise<void>;
  handleRootChange: () => Promise<void>;
  openWorkspaceDialog: () => Promise<void>;
  confirmWorkspace: () => void;
  handleFileCommand: (command: string, item: FsItem) => void;
  closePreview: () => void;
  isMarkdown: (name: string) => boolean;
  onUploadSuccess: () => void;
  onUploadError: () => void;
  initFileSystem: () => Promise<string[]>;
  setDefaultRoot: () => Promise<void>;
} {
  const roots = ref<string[]>([]);
  const selectedRoot = ref('');
  const workspaceCandidatePath = ref('');
  const currentPath = ref('');
  const folderList = ref<FsItem[]>([]);
  const workspaceDialogVisible = ref(false);
  const previewVisible = ref(false);
  const previewTitle = ref('');
  const previewHtml = ref('');
  const previewLoading = ref(false);

  const loadList = async (path?: string) => {
    if (path) workspaceCandidatePath.value = path;
    if (!workspaceCandidatePath.value) return;
    try {
      const data: FsItem[] = await fetch('/api/fs/list?path=' + encodeURIComponent(workspaceCandidatePath.value))
        .then((r) => { if (!r.ok) throw new Error('加载失败'); return r.json(); });
      folderList.value = data;
    } catch (error) {
      ElMessage.error('加载目录失败: ' + (error as Error).message);
    }
  };

  const handleRootChange = async () => {
    await loadList(selectedRoot.value);
  };

  const openWorkspaceDialog = async () => {
    workspaceCandidatePath.value = currentPath.value || selectedRoot.value;
    workspaceDialogVisible.value = true;
    await loadList(workspaceCandidatePath.value);
  };

  const confirmWorkspace = () => {
    if (!workspaceCandidatePath.value) return;
    currentPath.value = workspaceCandidatePath.value;
    workspaceDialogVisible.value = false;
  };

  // 仅 .md / .markdown 文件显示预览命令(不区分大小写)
  const isMarkdown = (name: string): boolean => /\.(md|markdown)$/i.test(name || '');

  const handleFileCommand = (command: string, item: FsItem) => {
    if (command === 'preview') {
      // 1MB 大小闸:超过则提示下载后查看,避免拉超大文件渲染卡顿
      if (item.size && item.size > 1048576) {
        ElMessage.warning('文件过大，建议下载后查看');
        return;
      }
      previewLoading.value = true;
      previewVisible.value = true;
      previewTitle.value = item.name;
      previewHtml.value = '';
      fetch('/api/fs/download?path=' + encodeURIComponent(item.path))
        .then((r) => { if (!r.ok) throw new Error('加载失败'); return r.text(); })
        .then((text) => { previewHtml.value = renderMarkdown(text); })
        .catch((e) => {
          ElMessage.error('预览失败: ' + (e as Error).message);
          previewVisible.value = false;
        })
        .finally(() => { previewLoading.value = false; });
    } else if (command === 'download') {
      window.open('/api/fs/download?path=' + encodeURIComponent(item.path), '_blank');
    } else if (command === 'delete') {
      ElMessageBox.confirm('确定要删除 ' + item.name + ' 吗？', '确认删除', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      }).then(() => {
        fetch('/api/fs/delete?path=' + encodeURIComponent(item.path), { method: 'DELETE' })
          .then((r) => { if (!r.ok) throw new Error('删除失败'); return r.json(); })
          .then(() => {
            ElMessage.success('已删除');
            loadList(workspaceCandidatePath.value);
          })
          .catch((e) => ElMessage.error((e as Error).message));
      }).catch(() => { });
    }
  };

  const closePreview = () => {
    previewVisible.value = false;
    previewHtml.value = '';
    previewTitle.value = '';
  };

  const onUploadSuccess = () => {
    ElMessage.success('上传成功');
    loadList(workspaceCandidatePath.value);
  };

  const onUploadError = () => {
    ElMessage.error('上传失败');
  };

  const initFileSystem = async (): Promise<string[]> => {
    try {
      const data: string[] = await fetch('/api/fs/roots').then((r) => r.json());
      roots.value = data;
      return data;
    } catch (error) {
      ElMessage.error('加载根路径失败');
      return [];
    }
  };

  const setDefaultRoot = async () => {
    const data = roots.value;
    if (data.length > 0) {
      selectedRoot.value = data[0];
      currentPath.value = data[0];
      await loadList(data[0]);
    }
  };

  return {
    roots, selectedRoot, workspaceCandidatePath, currentPath, folderList,
    workspaceDialogVisible, previewVisible, previewTitle, previewHtml, previewLoading,
    loadList, handleRootChange, openWorkspaceDialog, confirmWorkspace,
    handleFileCommand, closePreview, isMarkdown, onUploadSuccess, onUploadError,
    initFileSystem, setDefaultRoot,
  };
}