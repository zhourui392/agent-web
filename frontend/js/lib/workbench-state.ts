/**
 * Workbench Stage Shell 的纯前端状态约束。
 *
 * @author alex
 * @since 2026-08-05
 */

export type WorkbenchStageStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'HUMAN_COMPLETED';

export interface WorkbenchStageShellState {
  selectedStageInstanceIdentifier: string | null;
}

export interface WorkbenchStageNavigationItem {
  stageInstanceIdentifier: string;
}

const STAGE_INSTANCE_IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;

const STAGE_STATUS_LABELS: Record<WorkbenchStageStatus, string> = {
  NOT_STARTED: '未开始',
  IN_PROGRESS: '进行中',
  HUMAN_COMPLETED: '人工已完成',
};

const ERROR_MESSAGES: Record<string, string> = {
  WORKBENCH_REQUEST_INVALID: '请检查工作台输入后重试',
  WORKBENCH_NOT_FOUND: '工作台不存在或你无权访问',
  WORKBENCH_VERSION_CONFLICT: '工作台已被更新，请刷新后重试',
  WORKBENCH_ARCHIVED: '工作台已归档，不能继续修改',
  WORKBENCH_STAGE_RUN_ACTIVE: '当前阶段仍有运行中的任务',
  WORKBENCH_WRITE_RUN_ACTIVE: '工作台仍有写入任务在运行',
  WORKBENCH_STAGE_TRANSITION_INVALID: '当前阶段状态不允许该操作',
  WORKBENCH_REPOSITORY_SCOPE_INVALID: '请重新选择仓库和主仓库',
  WORKBENCH_IDEMPOTENCY_CONFLICT: '创建请求与已有请求冲突，请重新打开创建窗口',
  WORKBENCH_STAGE_SELECTION_EMPTY: '请至少选择一个阶段',
  WORKBENCH_STAGE_SELECTION_DUPLICATED: '阶段选择重复，请重新确认',
  WORKBENCH_STAGE_NOT_SELECTABLE: '所选阶段已停用或尚未发布，请重新选择',
  WORKBENCH_STAGE_CATALOG_CHANGED: '可选阶段配置已更新，请重新确认阶段后创建',
  WORKSPACE_SELECTION_INVALID: '请选择至少一个仓库，并指定主仓库',
  WORKSPACE_PATH_FORBIDDEN: '当前工作空间不在允许范围内',
  WORKSPACE_TOPOLOGY_CHANGED: '仓库目录或状态已变化；请恢复原目录，或创建新的 Workbench',
  WORKSPACE_REPOSITORY_NOT_FOUND: '仓库目录已移动或不存在；请恢复原目录，或创建新的 Workbench',
  WORKSPACE_CAPTURE_UNSTABLE: '仓库状态不稳定，请稍后重新检查',
  WORKSPACE_GIT_UNAVAILABLE: '暂时无法读取 Git 状态',
};

function encodeStoragePart(value: string | number): string {
  return encodeURIComponent(String(value));
}

export function isWorkbenchStageInstanceIdentifier(value: unknown): value is string {
  return typeof value === 'string' && STAGE_INSTANCE_IDENTIFIER.test(value);
}

export function stageStatusLabel(status: WorkbenchStageStatus): string {
  return STAGE_STATUS_LABELS[status] || String(status);
}

export function resolveStageNavigation(
  stages: readonly WorkbenchStageNavigationItem[],
  currentStageInstanceIdentifier: string | null,
  targetStageInstanceIdentifier: string,
): string | null {
  if (stages.some(stage => (
    stage.stageInstanceIdentifier === targetStageInstanceIdentifier
  ))) {
    return targetStageInstanceIdentifier;
  }
  if (stages.some(stage => (
    stage.stageInstanceIdentifier === currentStageInstanceIdentifier
  ))) {
    return currentStageInstanceIdentifier;
  }
  return stages[0]?.stageInstanceIdentifier ?? null;
}

export function workbenchShellStorageKey(userId: string, workbenchId: string): string {
  return [
    'agent-web:workbench-stage-shell',
    encodeStoragePart(userId || 'anonymous'),
    encodeStoragePart(workbenchId),
  ].join(':');
}

export function workbenchStageStorageKey(
  userId: string,
  workbenchId: string,
  stageInstanceIdentifier: string,
  conversationGeneration: number,
): string {
  return [
    'agent-web:workbench-stage',
    encodeStoragePart(userId || 'anonymous'),
    encodeStoragePart(workbenchId),
    encodeStoragePart(stageInstanceIdentifier),
    encodeStoragePart(conversationGeneration),
  ].join(':');
}

export function parseWorkbenchStageShellState(
  raw: string | null,
): WorkbenchStageShellState {
  try {
    const parsed = raw ? JSON.parse(raw) as {
      selectedStageInstanceIdentifier?: unknown;
    } : null;
    if (parsed
      && isWorkbenchStageInstanceIdentifier(
        parsed.selectedStageInstanceIdentifier,
      )) {
      return {
        selectedStageInstanceIdentifier:
          parsed.selectedStageInstanceIdentifier,
      };
    }
  } catch {
    // 损坏的本地状态不阻断 Stage 恢复。
  }
  return { selectedStageInstanceIdentifier: null };
}

export function workbenchErrorMessage(code: string | null | undefined): string {
  return code && ERROR_MESSAGES[code]
    ? ERROR_MESSAGES[code]
    : '操作失败，请稍后重试';
}
