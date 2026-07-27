/**
 * useScheduledTask composable: app.js 主页 定时任务切片(FE-R3.4)。
 *
 * 从 app.js setup 抽出: 任务状态(taskList/taskDialogVisible/taskEditing/taskForm reactive/
 * taskLoading/taskManagerVisible) + loadTasks/openTaskDialog/saveTask/deleteTask/toggleTask/
 * runTask/setCronPreset。
 *
 * 与外部耦合: openTaskDialog 新建态默认 workingDir 用 currentPath(useFileSystem),故以参数注入。
 *
 * 行为照搬 app.js 原内联实现,零逻辑变更。依赖 ElMessage/ElMessageBox。
 */
import { ref, reactive, type Ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

interface TaskItem {
  id: string;
  name: string;
  cronExpr: string;
  prompt: string;
  workingDir: string;
  [key: string]: unknown;
}

interface FsIntegration {
  currentPath: Ref<string>;
}

export function useScheduledTask(fs: FsIntegration): {
  taskList: Ref<TaskItem[]>;
  taskDialogVisible: Ref<boolean>;
  taskEditing: Ref<string | null>;
  taskForm: { name: string; cronExpr: string; prompt: string; workingDir: string };
  taskLoading: Ref<boolean>;
  taskManagerVisible: Ref<boolean>;
  loadTasks: () => Promise<void>;
  openTaskDialog: (task?: TaskItem | null) => void;
  saveTask: () => Promise<void>;
  deleteTask: (id: string) => Promise<void>;
  toggleTask: (id: string) => Promise<void>;
  runTask: (id: string) => Promise<void>;
  setCronPreset: (expr: string) => void;
} {
  const taskList = ref<TaskItem[]>([]);
  const taskDialogVisible = ref(false);
  const taskEditing = ref<string | null>(null);
  const taskForm = reactive({
    name: '',
    cronExpr: '',
    prompt: '',
    workingDir: '',
  });
  const taskLoading = ref(false);
  const taskManagerVisible = ref(false);

  const loadTasks = async () => {
    try {
      const data: TaskItem[] = await fetch('/api/tasks').then((r) => r.json());
      taskList.value = data;
    } catch (e) {
      ElMessage.error('加载定时任务失败');
    }
  };

  const openTaskDialog = (task?: TaskItem | null) => {
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
      taskForm.workingDir = fs.currentPath.value;
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
      ElMessage.error('保存失败: ' + ((e as Error).message || '未知错误'));
    } finally {
      taskLoading.value = false;
    }
  };

  const deleteTask = async (id: string) => {
    try {
      await ElMessageBox.confirm('确定删除该定时任务？', '确认删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
      });
      await fetch('/api/tasks/' + id, { method: 'DELETE' });
      ElMessage.success('已删除');
      await loadTasks();
    } catch (e) { /* cancelled or failed */ }
  };

  const toggleTask = async (id: string) => {
    try {
      await fetch('/api/tasks/' + id + '/toggle', { method: 'POST' });
      await loadTasks();
    } catch (e) {
      ElMessage.error('切换失败');
    }
  };

  const runTask = async (id: string) => {
    try {
      await fetch('/api/tasks/' + id + '/run', { method: 'POST' });
      ElMessage.success('任务已触发，结果将在历史对话中查看');
    } catch (e) {
      ElMessage.error('触发失败');
    }
  };

  const setCronPreset = (expr: string) => {
    taskForm.cronExpr = expr;
  };

  return {
    taskList, taskDialogVisible, taskEditing, taskForm, taskLoading, taskManagerVisible,
    loadTasks, openTaskDialog, saveTask, deleteTask, toggleTask, runTask, setCronPreset,
  };
}