/**
 * Workbench Shell 的纯前端状态约束。
 *
 * @author alex
 * @since 2026-08-01
 */

export type WorkbenchPhase =
  | 'REQUIREMENT_ANALYSIS'
  | 'SOLUTION_DESIGN'
  | 'IMPLEMENT_TEST'
  | 'REVIEW_REFACTOR';

export type WorkbenchPhaseStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'HUMAN_COMPLETED';

export type WorkbenchShellFeature =
  | 'conversation'
  | 'documents'
  | 'capabilities'
  | 'handoff'
  | 'operations'
  | 'review';

export interface WorkbenchShellState {
  selectedPhase: WorkbenchPhase;
}

export const WORKBENCH_PHASES: ReadonlyArray<{
  phase: WorkbenchPhase;
  label: string;
}> = [
  { phase: 'REQUIREMENT_ANALYSIS', label: '需求分析' },
  { phase: 'SOLUTION_DESIGN', label: '技术方案设计' },
  { phase: 'IMPLEMENT_TEST', label: '开发部署测试' },
  { phase: 'REVIEW_REFACTOR', label: '人工 Review、重构与测试' },
];

const PHASES = new Set<WorkbenchPhase>(WORKBENCH_PHASES.map((item) => item.phase));

const PHASE_STATUS_LABELS: Record<WorkbenchPhaseStatus, string> = {
  NOT_STARTED: '未开始',
  IN_PROGRESS: '进行中',
  HUMAN_COMPLETED: '人工已完成',
};

const ERROR_MESSAGES: Record<string, string> = {
  WORKBENCH_REQUEST_INVALID: '请检查工作台输入后重试',
  WORKBENCH_NOT_FOUND: '工作台不存在或你无权访问',
  WORKBENCH_VERSION_CONFLICT: '工作台已被更新，请刷新后重试',
  WORKBENCH_ARCHIVED: '工作台已归档，不能继续修改',
  WORKBENCH_PHASE_RUN_ACTIVE: '当前阶段仍有运行中的任务',
  WORKBENCH_WRITE_RUN_ACTIVE: '工作台仍有写入任务在运行',
  WORKBENCH_PHASE_TRANSITION_INVALID: '当前阶段状态不允许该操作',
  WORKBENCH_REPOSITORY_SCOPE_INVALID: '请重新选择仓库和主仓库',
  WORKBENCH_IDEMPOTENCY_CONFLICT: '创建请求与已有请求冲突，请重新打开创建窗口',
  WORKSPACE_SELECTION_INVALID: '请选择至少一个仓库，并指定主仓库',
  WORKSPACE_PATH_FORBIDDEN: '当前工作空间不在允许范围内',
  WORKSPACE_TOPOLOGY_CHANGED: '仓库状态已变化，请重新检查工作空间',
  WORKSPACE_CAPTURE_UNSTABLE: '仓库状态不稳定，请稍后重新检查',
  WORKSPACE_GIT_UNAVAILABLE: '暂时无法读取 Git 状态',
};

function encodeStoragePart(value: string | number): string {
  return encodeURIComponent(String(value));
}

export function isWorkbenchPhase(value: unknown): value is WorkbenchPhase {
  return typeof value === 'string' && PHASES.has(value as WorkbenchPhase);
}

export function phaseStatusLabel(status: WorkbenchPhaseStatus): string {
  return PHASE_STATUS_LABELS[status] || String(status);
}

export function resolvePhaseNavigation(
  current: WorkbenchPhase,
  target: WorkbenchPhase,
): WorkbenchPhase {
  return isWorkbenchPhase(target) ? target : current;
}

export function workbenchShellStorageKey(userId: string, workbenchId: string): string {
  return [
    'agent-web:workbench-shell',
    encodeStoragePart(userId || 'anonymous'),
    encodeStoragePart(workbenchId),
  ].join(':');
}

export function workbenchPhaseStorageKey(
  userId: string,
  workbenchId: string,
  phase: WorkbenchPhase,
  conversationGeneration: number,
): string {
  return [
    'agent-web:workbench-phase',
    encodeStoragePart(userId || 'anonymous'),
    encodeStoragePart(workbenchId),
    encodeStoragePart(phase),
    encodeStoragePart(conversationGeneration),
  ].join(':');
}

export function parseWorkbenchShellState(raw: string | null): WorkbenchShellState {
  try {
    const parsed = raw ? JSON.parse(raw) as { selectedPhase?: unknown } : null;
    if (parsed && isWorkbenchPhase(parsed.selectedPhase)) {
      return { selectedPhase: parsed.selectedPhase };
    }
  } catch {
    // 损坏的本地状态不阻断页面恢复。
  }
  return { selectedPhase: 'REQUIREMENT_ANALYSIS' };
}

export function workbenchErrorMessage(code: string | null | undefined): string {
  return code && ERROR_MESSAGES[code]
    ? ERROR_MESSAGES[code]
    : '操作失败，请稍后重试';
}

export function isWorkbenchShellFeatureAvailable(
  _feature: WorkbenchShellFeature,
): boolean {
  return false;
}
