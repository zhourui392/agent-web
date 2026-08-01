/**
 * Workbench Shell 编排：认证、Inspect/Create、列表/详情和人工阶段状态。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ApiError } from '../api/client';
import {
  completeWorkbenchPhase,
  createWorkbench,
  getWorkbench,
  inspectWorkspace,
  listWorkbenches,
  reopenWorkbenchPhase,
  type WorkbenchDetail,
  type WorkbenchListItem,
  type WorkbenchPhaseView,
  type WorkspaceInspection,
} from '../api/workbench';
import { useAuth } from './useAuth';
import {
  parseWorkbenchShellState,
  resolvePhaseNavigation,
  workbenchErrorMessage,
  workbenchShellStorageKey,
  type WorkbenchPhase,
} from '../lib/workbench-state';

interface CreateForm {
  workspaceRoot: string;
  title: string;
  originalGoal: string;
  agentType: string;
  environment: string;
}

interface UseWorkbenchShell {
  username: Ref<string>;
  currentUserId: Ref<string>;
  authEnabled: Ref<boolean>;
  listLoading: Ref<boolean>;
  detailLoading: Ref<boolean>;
  inspectLoading: Ref<boolean>;
  createLoading: Ref<boolean>;
  mutationLoading: Ref<boolean>;
  workbenches: Ref<WorkbenchListItem[]>;
  detail: Ref<WorkbenchDetail | null>;
  selectedPhase: Ref<WorkbenchPhase>;
  selectedPhaseView: ComputedRef<WorkbenchPhaseView | null>;
  errorMessage: Ref<string>;
  createDialogVisible: Ref<boolean>;
  createForm: CreateForm;
  inspection: Ref<WorkspaceInspection | null>;
  selectedRepositories: Ref<string[]>;
  primaryRepository: Ref<string>;
  initialize: () => Promise<void>;
  refreshList: () => Promise<void>;
  selectWorkbench: (workbenchId: string) => Promise<void>;
  selectPhase: (phase: WorkbenchPhase) => void;
  openCreateDialog: () => void;
  runInspection: () => Promise<void>;
  ensurePrimaryRepository: () => void;
  submitCreate: () => Promise<void>;
  completeSelectedPhase: () => Promise<void>;
  reopenSelectedPhase: () => Promise<void>;
  clearError: () => void;
  goHome: () => void;
  doLogout: () => Promise<void>;
}

function readErrorCode(error: unknown): string | null {
  if (!(error instanceof ApiError) || !error.body || typeof error.body !== 'object') {
    return null;
  }
  const body = error.body as { code?: unknown };
  return typeof body.code === 'string' ? body.code : null;
}

function newIdempotencyKey(): string {
  if (typeof globalThis.crypto !== 'undefined' && globalThis.crypto.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  return `workbench-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function useWorkbenchShell(): UseWorkbenchShell {
  const {
    username,
    currentUserId,
    authEnabled,
    initAuth,
    doLogout,
  } = useAuth();
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const inspectLoading = ref(false);
  const createLoading = ref(false);
  const mutationLoading = ref(false);
  const workbenches = ref<WorkbenchListItem[]>([]);
  const detail = ref<WorkbenchDetail | null>(null);
  const selectedPhase = ref<WorkbenchPhase>('REQUIREMENT_ANALYSIS');
  const errorMessage = ref('');
  const createDialogVisible = ref(false);
  const inspection = ref<WorkspaceInspection | null>(null);
  const selectedRepositories = ref<string[]>([]);
  const primaryRepository = ref('');
  const createForm = reactive<CreateForm>({
    workspaceRoot: '',
    title: '',
    originalGoal: '',
    agentType: 'CODEX',
    environment: 'test',
  });
  let creationIdempotencyKey = '';

  const selectedPhaseView = computed<WorkbenchPhaseView | null>(() => {
    if (!detail.value) return null;
    return detail.value.phases.find((item) => item.phase === selectedPhase.value) || null;
  });

  function storageIdentity(): string {
    return currentUserId.value || username.value || 'anonymous';
  }

  function clearError(): void {
    errorMessage.value = '';
  }

  function reportError(error: unknown): void {
    errorMessage.value = workbenchErrorMessage(readErrorCode(error));
  }

  function persistSelectedPhase(): void {
    if (!detail.value) return;
    const key = workbenchShellStorageKey(storageIdentity(), detail.value.id);
    localStorage.setItem(key, JSON.stringify({ selectedPhase: selectedPhase.value }));
  }

  function restoreSelectedPhase(workbench: WorkbenchDetail): void {
    const key = workbenchShellStorageKey(storageIdentity(), workbench.id);
    const stored = parseWorkbenchShellState(localStorage.getItem(key));
    selectedPhase.value = workbench.phases.some((item) => item.phase === stored.selectedPhase)
      ? stored.selectedPhase
      : 'REQUIREMENT_ANALYSIS';
  }

  function updateLocation(workbenchId: string | null): void {
    const target = workbenchId
      ? `/workbench.html?id=${encodeURIComponent(workbenchId)}`
      : '/workbench.html';
    window.history.replaceState(null, '', target);
  }

  async function refreshList(): Promise<void> {
    listLoading.value = true;
    try {
      const page = await listWorkbenches({ limit: 50 });
      workbenches.value = page.items;
    } catch (error) {
      reportError(error);
    } finally {
      listLoading.value = false;
    }
  }

  async function loadWorkbench(workbenchId: string, changeLocation: boolean): Promise<void> {
    detailLoading.value = true;
    try {
      const loaded = await getWorkbench(workbenchId);
      detail.value = loaded;
      restoreSelectedPhase(loaded);
      if (changeLocation) updateLocation(loaded.id);
    } catch (error) {
      reportError(error);
      if (error instanceof ApiError && error.status === 404) {
        detail.value = null;
        updateLocation(null);
      }
    } finally {
      detailLoading.value = false;
    }
  }

  async function selectWorkbench(workbenchId: string): Promise<void> {
    clearError();
    await loadWorkbench(workbenchId, true);
  }

  function selectPhase(phase: WorkbenchPhase): void {
    selectedPhase.value = resolvePhaseNavigation(selectedPhase.value, phase);
    persistSelectedPhase();
  }

  function resetCreateForm(): void {
    inspection.value = null;
    selectedRepositories.value = [];
    primaryRepository.value = '';
    createForm.workspaceRoot = '';
    createForm.title = '';
    createForm.originalGoal = '';
    createForm.agentType = 'CODEX';
    createForm.environment = 'test';
    creationIdempotencyKey = newIdempotencyKey();
  }

  function openCreateDialog(): void {
    clearError();
    resetCreateForm();
    createDialogVisible.value = true;
  }

  async function runInspection(): Promise<void> {
    clearError();
    if (!createForm.workspaceRoot.trim()) {
      errorMessage.value = '请先输入 Workspace Root';
      return;
    }
    inspectLoading.value = true;
    try {
      const result = await inspectWorkspace(createForm.workspaceRoot.trim());
      inspection.value = result;
      selectedRepositories.value = result.repositories
        .filter((repository) => repository.selectedByDefault)
        .map((repository) => repository.repositoryKey);
      const suggested = result.repositories.find((repository) =>
        repository.primarySuggested
        && selectedRepositories.value.includes(repository.repositoryKey));
      primaryRepository.value = suggested?.repositoryKey
        || selectedRepositories.value[0]
        || '';
    } catch (error) {
      inspection.value = null;
      selectedRepositories.value = [];
      primaryRepository.value = '';
      reportError(error);
    } finally {
      inspectLoading.value = false;
    }
  }

  function ensurePrimaryRepository(): void {
    if (!selectedRepositories.value.includes(primaryRepository.value)) {
      primaryRepository.value = selectedRepositories.value[0] || '';
    }
  }

  async function submitCreate(): Promise<void> {
    clearError();
    ensurePrimaryRepository();
    if (!inspection.value) {
      errorMessage.value = '请先检查 Workspace Root';
      return;
    }
    if (!createForm.title.trim() || !createForm.originalGoal.trim()) {
      errorMessage.value = '请填写工作台标题和原始目标';
      return;
    }
    if (!selectedRepositories.value.length || !primaryRepository.value) {
      errorMessage.value = workbenchErrorMessage('WORKSPACE_SELECTION_INVALID');
      return;
    }

    createLoading.value = true;
    try {
      const created = await createWorkbench({
        title: createForm.title.trim(),
        originalGoal: createForm.originalGoal.trim(),
        agentType: createForm.agentType,
        environment: createForm.environment.trim(),
        workspaceRoot: createForm.workspaceRoot.trim(),
        primaryRepository: primaryRepository.value,
        repositories: selectedRepositories.value.slice(),
      }, creationIdempotencyKey);
      createDialogVisible.value = false;
      await refreshList();
      await loadWorkbench(created.workbenchId, true);
    } catch (error) {
      reportError(error);
    } finally {
      createLoading.value = false;
    }
  }

  function applyPhaseMutation(
    phase: WorkbenchPhase,
    phaseStatus: WorkbenchPhaseView['status'],
    workbenchVersion: number,
  ): void {
    if (!detail.value) return;
    detail.value = {
      ...detail.value,
      version: workbenchVersion,
      phases: detail.value.phases.map((item) => item.phase === phase
        ? { ...item, status: phaseStatus }
        : item),
    };
  }

  async function mutateSelectedPhase(action: 'complete' | 'reopen'): Promise<void> {
    if (!detail.value) return;
    clearError();
    mutationLoading.value = true;
    const workbenchId = detail.value.id;
    const phase = selectedPhase.value;
    const version = detail.value.version;
    try {
      const result = action === 'complete'
        ? await completeWorkbenchPhase(workbenchId, phase, version)
        : await reopenWorkbenchPhase(workbenchId, phase, version);
      applyPhaseMutation(result.phase, result.phaseStatus, result.workbenchVersion);
      await refreshList();
    } catch (error) {
      reportError(error);
      if (error instanceof ApiError && error.status === 409) {
        await loadWorkbench(workbenchId, false);
      }
    } finally {
      mutationLoading.value = false;
    }
  }

  function completeSelectedPhase(): Promise<void> {
    return mutateSelectedPhase('complete');
  }

  function reopenSelectedPhase(): Promise<void> {
    return mutateSelectedPhase('reopen');
  }

  async function initialize(): Promise<void> {
    const authenticated = await initAuth();
    if (!authenticated) return;
    await refreshList();
    const workbenchId = new URLSearchParams(window.location.search).get('id');
    if (workbenchId) {
      await loadWorkbench(workbenchId, false);
    }
  }

  function goHome(): void {
    window.location.href = '/index.html';
  }

  return {
    username,
    currentUserId,
    authEnabled,
    listLoading,
    detailLoading,
    inspectLoading,
    createLoading,
    mutationLoading,
    workbenches,
    detail,
    selectedPhase,
    selectedPhaseView,
    errorMessage,
    createDialogVisible,
    createForm,
    inspection,
    selectedRepositories,
    primaryRepository,
    initialize,
    refreshList,
    selectWorkbench,
    selectPhase,
    openCreateDialog,
    runInspection,
    ensurePrimaryRepository,
    submitCreate,
    completeSelectedPhase,
    reopenSelectedPhase,
    clearError,
    goHome,
    doLogout,
  };
}
