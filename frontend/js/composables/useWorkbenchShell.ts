/**
 * Workbench Shell 编排：认证、Inspect/Create、列表/详情和人工阶段状态。
 *
 * @author alex
 * @since 2026-08-01
 */
import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue';
import { ApiError } from '../api/client';
import {
  completeWorkbenchStage,
  createWorkbench,
  getSelectableWorkbenchStages,
  getWorkbench,
  inspectWorkspace,
  listWorkbenches,
  reopenWorkbenchStage,
  type WorkbenchDetail,
  type WorkbenchListItem,
  type WorkbenchStageView,
  type SelectableWorkbenchStageCatalog,
  type WorkspaceInspection,
} from '../api/workbench';
import { useAuth } from './useAuth';
import {
  parseWorkbenchStageShellState,
  resolveStageNavigation,
  workbenchErrorMessage,
  workbenchShellStorageKey,
} from '../lib/workbench-state';
import {
  defaultSelectedStageIdentifiers,
  orderedSelectedStageIdentifiers,
} from '../lib/workbench-stage-selection';

interface CreateForm {
  workspaceRoot: string;
  title: string;
  originalGoal: string;
  agentType: string;
  environment: string;
  useWorktree: boolean;
}

interface UseWorkbenchShell {
  username: Ref<string>;
  currentUserId: Ref<string>;
  authEnabled: Ref<boolean>;
  sidebarCollapsed: Ref<boolean>;
  listLoading: Ref<boolean>;
  detailLoading: Ref<boolean>;
  inspectLoading: Ref<boolean>;
  createLoading: Ref<boolean>;
  stageCatalogLoading: Ref<boolean>;
  mutationLoading: Ref<boolean>;
  workbenches: Ref<WorkbenchListItem[]>;
  detail: Ref<WorkbenchDetail | null>;
  selectedStageInstanceIdentifier: Ref<string | null>;
  selectedStageView: ComputedRef<WorkbenchStageView | null>;
  errorMessage: Ref<string>;
  createDialogVisible: Ref<boolean>;
  createForm: CreateForm;
  inspection: Ref<WorkspaceInspection | null>;
  selectedRepositories: Ref<string[]>;
  primaryRepository: Ref<string>;
  selectableStageCatalog: Ref<SelectableWorkbenchStageCatalog | null>;
  selectedStageDefinitionIdentifiers: Ref<string[]>;
  initialize: () => Promise<void>;
  refreshList: () => Promise<void>;
  selectWorkbench: (workbenchId: string) => Promise<void>;
  selectStage: (stageInstanceIdentifier: string) => void;
  openCreateDialog: () => Promise<void>;
  runInspection: () => Promise<void>;
  ensurePrimaryRepository: () => void;
  submitCreate: () => Promise<void>;
  completeSelectedStage: () => Promise<void>;
  reopenSelectedStage: () => Promise<void>;
  clearError: () => void;
  goHome: () => void;
  doLogout: () => Promise<void>;
  toggleSidebar: () => void;
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
  const sidebarCollapsed = ref(false);
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const inspectLoading = ref(false);
  const createLoading = ref(false);
  const stageCatalogLoading = ref(false);
  const mutationLoading = ref(false);
  const workbenches = ref<WorkbenchListItem[]>([]);
  const detail = ref<WorkbenchDetail | null>(null);
  const selectedStageInstanceIdentifier = ref<string | null>(null);
  const errorMessage = ref('');
  const createDialogVisible = ref(false);
  const inspection = ref<WorkspaceInspection | null>(null);
  const selectedRepositories = ref<string[]>([]);
  const primaryRepository = ref('');
  const selectableStageCatalog = ref<SelectableWorkbenchStageCatalog | null>(null);
  const selectedStageDefinitionIdentifiers = ref<string[]>([]);
  const createForm = reactive<CreateForm>({
    workspaceRoot: '',
    title: '',
    originalGoal: '',
    agentType: 'CODEX',
    environment: 'test',
    useWorktree: false,
  });
  let creationIdempotencyKey = '';

  const selectedStageView = computed<WorkbenchStageView | null>(() => {
    if (!detail.value || !selectedStageInstanceIdentifier.value) return null;
    return detail.value.stages.find((stage) => (
      stage.stageInstanceIdentifier === selectedStageInstanceIdentifier.value
    )) || null;
  });

  function storageIdentity(): string {
    return currentUserId.value || username.value || 'anonymous';
  }

  function sidebarStorageKey(): string {
    return `workbench:sidebar:${storageIdentity()}`;
  }

  function persistSidebarCollapsed(): void {
    try {
      localStorage.setItem(
        sidebarStorageKey(),
        JSON.stringify({ collapsed: sidebarCollapsed.value }),
      );
    } catch {
      // localStorage 禁用时仍允许内存态切换
    }
  }

  function restoreSidebarCollapsed(): void {
    try {
      const stored = localStorage.getItem(sidebarStorageKey());
      if (stored) {
        const parsed = JSON.parse(stored);
        if (parsed && typeof parsed.collapsed === 'boolean') {
          sidebarCollapsed.value = parsed.collapsed;
        }
      }
    } catch {
      // 损坏的存储键忽略，默认展开
    }
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value;
    persistSidebarCollapsed();
  }

  function clearError(): void {
    errorMessage.value = '';
  }

  function reportError(error: unknown): void {
    errorMessage.value = workbenchErrorMessage(readErrorCode(error));
  }

  function persistSelectedStage(): void {
    if (!detail.value || !selectedStageInstanceIdentifier.value) return;
    const key = workbenchShellStorageKey(storageIdentity(), detail.value.id);
    localStorage.setItem(key, JSON.stringify({
      selectedStageInstanceIdentifier:
        selectedStageInstanceIdentifier.value,
    }));
  }

  function restoreSelectedStage(workbench: WorkbenchDetail): void {
    const key = workbenchShellStorageKey(storageIdentity(), workbench.id);
    const stored = parseWorkbenchStageShellState(localStorage.getItem(key));
    selectedStageInstanceIdentifier.value = resolveStageNavigation(
      workbench.stages,
      null,
      stored.selectedStageInstanceIdentifier || '',
    );
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
      restoreSelectedStage(loaded);
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

  function selectStage(stageInstanceIdentifier: string): void {
    selectedStageInstanceIdentifier.value = resolveStageNavigation(
      detail.value?.stages || [],
      selectedStageInstanceIdentifier.value,
      stageInstanceIdentifier,
    );
    persistSelectedStage();
  }

  function resetCreateForm(): void {
    inspection.value = null;
    selectedRepositories.value = [];
    primaryRepository.value = '';
    selectableStageCatalog.value = null;
    selectedStageDefinitionIdentifiers.value = [];
    createForm.workspaceRoot = '';
    createForm.title = '';
    createForm.originalGoal = '';
    createForm.agentType = 'CODEX';
    createForm.environment = 'test';
    createForm.useWorktree = false;
    creationIdempotencyKey = newIdempotencyKey();
  }

  async function openCreateDialog(): Promise<void> {
    clearError();
    resetCreateForm();
    createDialogVisible.value = true;
    await loadSelectableStageCatalog();
  }

  async function loadSelectableStageCatalog(): Promise<void> {
    stageCatalogLoading.value = true;
    try {
      const catalog = await getSelectableWorkbenchStages();
      selectableStageCatalog.value = catalog;
      selectedStageDefinitionIdentifiers.value =
        defaultSelectedStageIdentifiers(catalog.stages);
    } catch (error) {
      selectableStageCatalog.value = null;
      selectedStageDefinitionIdentifiers.value = [];
      reportError(error);
    } finally {
      stageCatalogLoading.value = false;
    }
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
    if (!selectableStageCatalog.value) {
      errorMessage.value = '可选阶段加载失败，请关闭窗口后重试';
      return;
    }
    const orderedStageIdentifiers = orderedSelectedStageIdentifiers(
      selectableStageCatalog.value.stages,
      selectedStageDefinitionIdentifiers.value,
    );
    if (!orderedStageIdentifiers.length) {
      errorMessage.value = workbenchErrorMessage('WORKBENCH_STAGE_SELECTION_EMPTY');
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
        stageDefinitionIdentifiers: orderedStageIdentifiers,
        expectedStageCatalogVersion: selectableStageCatalog.value.stageCatalogVersion,
        useWorktree: createForm.useWorktree,
      }, creationIdempotencyKey);
      createDialogVisible.value = false;
      await refreshList();
      await loadWorkbench(created.workbenchId, true);
    } catch (error) {
      reportError(error);
      if (readErrorCode(error) === 'WORKBENCH_STAGE_CATALOG_CHANGED') {
        await loadSelectableStageCatalog();
      }
    } finally {
      createLoading.value = false;
    }
  }

  function applyStageMutation(
    stageInstanceIdentifier: string,
    stageStatus: WorkbenchStageView['status'],
    conversationId: string | null,
    conversationGeneration: number,
    workbenchVersion: number,
  ): void {
    if (!detail.value) return;
    detail.value = {
      ...detail.value,
      version: workbenchVersion,
      stages: detail.value.stages.map((stage) => (
        stage.stageInstanceIdentifier === stageInstanceIdentifier
          ? {
              ...stage,
              status: stageStatus,
              conversationGeneration,
              currentConversation: conversationId
                ? { sessionId: conversationId, generation: conversationGeneration }
                : null,
            }
          : stage
      )),
    };
  }

  async function mutateSelectedStage(action: 'complete' | 'reopen'): Promise<void> {
    if (!detail.value || !selectedStageInstanceIdentifier.value) return;
    clearError();
    mutationLoading.value = true;
    const workbenchId = detail.value.id;
    const stageInstanceIdentifier = selectedStageInstanceIdentifier.value;
    const version = detail.value.version;
    try {
      const result = action === 'complete'
        ? await completeWorkbenchStage(
            workbenchId, stageInstanceIdentifier, version,
          )
        : await reopenWorkbenchStage(
            workbenchId, stageInstanceIdentifier, version,
          );
      applyStageMutation(
        result.stageInstanceIdentifier,
        result.stageStatus,
        result.conversationId,
        result.conversationGeneration,
        result.workbenchVersion,
      );
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

  function completeSelectedStage(): Promise<void> {
    return mutateSelectedStage('complete');
  }

  function reopenSelectedStage(): Promise<void> {
    return mutateSelectedStage('reopen');
  }

  async function initialize(): Promise<void> {
    const authenticated = await initAuth();
    if (!authenticated) return;
    restoreSidebarCollapsed();
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
    sidebarCollapsed,
    listLoading,
    detailLoading,
    inspectLoading,
    createLoading,
    stageCatalogLoading,
    mutationLoading,
    workbenches,
    detail,
    selectedStageInstanceIdentifier,
    selectedStageView,
    errorMessage,
    createDialogVisible,
    createForm,
    inspection,
    selectedRepositories,
    primaryRepository,
    selectableStageCatalog,
    selectedStageDefinitionIdentifiers,
    initialize,
    refreshList,
    selectWorkbench,
    selectStage,
    openCreateDialog,
    runInspection,
    ensurePrimaryRepository,
    submitCreate,
    completeSelectedStage,
    reopenSelectedStage,
    clearError,
    goHome,
    doLogout,
    toggleSidebar,
  };
}
