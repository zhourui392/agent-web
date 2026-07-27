/**
 * useHarness composable: Harness M4 四阶段控制台状态与逻辑。
 *
 * 从 Harness.vue setup() 机械抽出:全部 state(refs/reactive/computed)、函数、
 * 轮询定时器、全局事件监听与 onBeforeUnmount 生命周期。行为照搬原内联实现,零逻辑变更。
 *
 * 依赖: vue(ref/reactive/computed/nextTick/onBeforeUnmount)、element-plus(ElMessage/ElMessageBox)、
 * harness-utils.js、lib/formatters.js。
 */
import * as HarnessAdminUtils from '../harness-utils.js';
import { renderMarkdown as renderMarkdownFn, escapeHtml } from '../../lib/formatters.js';
import { useHarnessApi } from './useHarnessApi.js';
import { useHarnessSse } from './useHarnessSse.js';
import { ref, reactive, computed, nextTick, onBeforeUnmount } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyRef = ReturnType<typeof ref<any>>;
type AnyReactive = Record<string, any>;

export function useHarness(): Record<string, any> {
  const stageNames = ['ANALYSIS', 'DESIGN', 'IMPLEMENTATION', 'DEPLOYMENT'];
  const stageLabels: Record<string, string> = {
    ANALYSIS: '需求分析',
    DESIGN: '方案设计',
    IMPLEMENTATION: 'TDD 实现',
    DEPLOYMENT: '部署验证'
  };
  const runSummaries = ref<any[]>([]);
  const selectedRun = ref<any>(null);
  const selectedStageName = ref<string>('ANALYSIS');
  const events = ref<any[]>([]);
  const deployments = ref<any[]>([]);
  const deploymentReadiness = ref<any>(null);
  const snapshot = ref<any>(null);
  const runtime = ref<any>(null);
  // 当前已加载资源的 stage#attempt 键，用于区分"阶段切换"与"同阶段轮询刷新"
  const loadedStageKey = ref<string>('');
  const conversationMessages = ref<any[]>([]);
  const conversationDraft = ref<string>('');
  const conversationNonce = ref<string>('');
  const conversationFeed = ref<HTMLElement | null>(null);
  const conversationLoading = ref<boolean>(false);
  const originalRequirement = ref<string>('');
  const apiAvailable = ref<boolean>(true);
  const loadingRuns = ref<boolean>(false);
  const loadingDetail = ref<boolean>(false);
  const actionLoading = ref<boolean>(false);
  const snapshotLoading = ref<boolean>(false);
  const isMobile = ref<boolean>(window.innerWidth <= 768);
  const createOpen = ref<boolean>(false);
  const approvalOpen = ref<boolean>(false);
  const approvalDecision = ref<string>('approve');
  const questionOpen = ref<boolean>(false);
  const deploymentOpen = ref<boolean>(false);
  const createNonce = ref<string>('');
  const answerDrafts = reactive<AnyReactive>({});

  const createForm = reactive<AnyReactive>({
    title: '',
    workingDir: '',
    originalRequirement: '',
    agentType: 'CODEX',
    environment: 'local',
    definitionVersion: 'harness@1.0.0'
  });
  const capabilityForm = reactive<AnyReactive>({
    explicitSkillIds: '',
    technicalTags: 'java',
    approvedWorkspaceSkillIds: '',
    readableFileRoots: 'workspace',
    writableFileRoots: '',
    executableCommands: '',
    explicitMcpServerIds: '',
    requiredMcpServerIds: '',
    grantedMcpServerIds: '',
    currentInput: ''
  });
  const approvalForm = reactive<AnyReactive>({ reason: '' });
  // 产物 Tab artifact 正文预览
  const previewOpen = ref<boolean>(false);
  const previewContent = ref<string>('');
  const previewContentType = ref<string>('');
  const previewTitle = ref<string>('');
  const previewLoading = ref<boolean>(false);
  // 审批对话框展示待审批 artifact 正文
  const approvalArtifactContent = ref<string>('');
  const approvalArtifactContentType = ref<string>('');
  const approvalArtifactLoading = ref<boolean>(false);
  const questionForm = reactive<AnyReactive>({ questionId: '', question: '', blocking: true });
  const deploymentForm = reactive<AnyReactive>({ templateId: 'local-default' });
  const auditExpanded = ref<boolean>(false);
  const openCapabilityPanel = ref<boolean>(false);
  // Run 列表筛选 / 搜索
  const runFilter = ref<string>('all'); // all / running / waiting / done / failed
  const runFilterOptions = [
    { value: 'all', label: '全部' },
    { value: 'running', label: '进行中' },
    { value: 'waiting', label: '待审批' },
    { value: 'done', label: '已完成' },
    { value: 'failed', label: '失败' }
  ];
  const runSearch = ref<string>('');
  const runSearchOpen = ref<boolean>(false);
  // Artifact 消息卡片折叠态（messageId -> 是否折叠）
  const artifactCollapsed = reactive<AnyReactive>({});

  function onResize() { isMobile.value = window.innerWidth <= 768; }
  window.addEventListener('resize', onResize);

  const selectedStage = computed(() => {
    const stages = selectedRun.value && Array.isArray(selectedRun.value.stages)
      ? selectedRun.value.stages : [];
    return stages.find((item: any) => item.stage === selectedStageName.value) || null;
  });
  const selectedAttempt = computed(() => HarnessAdminUtils.currentAttempt(selectedStage.value));
  const currentGates = computed(() => {
    if (!selectedRun.value || !selectedAttempt.value) {
      return [];
    }
    return (selectedRun.value.gateResults || []).filter((item: any) =>
      item.stage === selectedStageName.value
        && Number(item.attempt) === Number(selectedAttempt.value.number));
  });
  const gateFailures = computed(() => {
    if (!selectedRun.value || !selectedAttempt.value) {
      return [];
    }
    return HarnessAdminUtils.gateFailureSummary(
      selectedRun.value.gateResults, selectedStageName.value, selectedAttempt.value.number);
  });
  const stageArtifacts = computed(() => {
    if (!selectedRun.value) {
      return [];
    }
    return (selectedRun.value.artifacts || [])
      .filter((item: any) => item.stage === selectedStageName.value)
      .slice()
      .sort((left: any, right: any) => right.version - left.version || right.createdAt - left.createdAt);
  });
  const stageApprovals = computed(() => {
    if (!selectedRun.value) {
      return [];
    }
    return (selectedRun.value.approvals || [])
      .filter((item: any) => item.stage === selectedStageName.value)
      .slice()
      .reverse();
  });
  const currentQuestions = computed(() => {
    if (!selectedRun.value || !selectedAttempt.value) {
      return [];
    }
    return (selectedRun.value.questions || []).filter((item: any) =>
      item.stage === selectedStageName.value
        && Number(item.attempt) === Number(selectedAttempt.value.number));
  });
  const unansweredQuestions = computed(() => currentQuestions.value.filter(
    (item: any) => !item.answeredAt));
  const stageConversationMessages = computed(() => conversationMessages.value.filter(
    (item: any) => item.stage === selectedStageName.value));
  // Run 列表：按 updatedAt 倒序 + 状态筛选 + 关键字搜索
  const filteredRuns = computed(() => {
    const keyword = runSearch.value.trim().toLowerCase();
    const filtered = runSummaries.value.filter((run: any) => {
      if (keyword) {
        const hay = ((run.title || '') + ' ' + (run.runId || '')).toLowerCase();
        if (!hay.includes(keyword)) {
          return false;
        }
      }
      if (runFilter.value === 'all') {
        return true;
      }
      return HarnessAdminUtils.runBucket(run) === runFilter.value;
    });
    return filtered.slice().sort((left: any, right: any) => {
      const ta = left.updatedAt ? new Date(left.updatedAt).getTime() : 0;
      const tb = right.updatedAt ? new Date(right.updatedAt).getTime() : 0;
      return tb - ta;
    });
  });
  const workingDirSuggestions = computed(
    () => HarnessAdminUtils.workingDirSuggestions(runSummaries.value));
  const runtimeBusy = computed(() => HarnessAdminUtils.runtimeBusy(runtime.value));
  const canSendConversation = computed(() => HarnessAdminUtils.canSendConversation(
    selectedStage.value, runtime.value));
  const canValidateConversation = computed(() => selectedStage.value
    && selectedStage.value.status === 'RUNNING'
    && runtime.value && runtime.value.status === 'SUCCEEDED');
  const conversationHint = computed(() => {
    if (selectedStage.value && selectedStage.value.status === 'WAITING_INPUT') {
      return '请先回答 Codex 的阻断问题';
    }
    if (runtimeBusy.value) {
      return '当前 Runtime 执行中，完成后可继续修改';
    }
    if (!canSendConversation.value) {
      return '当前 Run 或阶段已不可修改';
    }
    return '系统自动使用阶段默认 Skill 与本机 Codex CLI';
  });
  // 执行中状态文案：按阶段给出更具体的提示，替代单一固定文案
  const runtimeHint = computed(() => {
    const stage = selectedStageName.value;
    const hints: Record<string, string> = {
      ANALYSIS: '正在读取上下文并分析需求、产出可验收标准…',
      DESIGN: '正在设计方案、追踪矩阵与变更点…',
      IMPLEMENTATION: '正在按 TDD 编写代码与测试，留下 RED/GREEN 证据…',
      DEPLOYMENT: '正在执行本机 local 部署并采集证据…'
    };
    return hints[stage] || '正在读取上下文并执行本阶段任务，结果会自动回到这里…';
  });
  const finalReport = computed(() => latestArtifact('FINAL_REPORT'));
  const canStartStage = computed(() => selectedStage.value && selectedStage.value.status === 'PENDING');
  const canRetryStage = computed(() => selectedStage.value
    && ['FAILED', 'PASSED', 'INVALIDATED'].includes(selectedStage.value.status));
  const canOperateRunningStage = computed(() => selectedStage.value
    && selectedStage.value.status === 'RUNNING');
  const canDecideApproval = computed(() => selectedStage.value
    && selectedStage.value.status === 'WAITING_APPROVAL'
    && Boolean(selectedStage.value.artifactBaselineHash));
  const canStartDeployment = computed(() => selectedRun.value
    && HarnessAdminUtils.canStartDeployment(selectedRun.value));

  function csv(value: string | null | undefined): string[] {
    return String(value || '').split(',').map(item => item.trim()).filter(Boolean);
  }

  const { idempotencyKeyCache, runUrl, idempotencyKey, api, optionalApi, post, showError } = useHarnessApi();

  function stageUrl(stage: string): string {
    if (!selectedRun.value) {
      throw new Error('请先选择 Run');
    }
    return runUrl(selectedRun.value.runId) + '/stages/' + encodeURIComponent(stage);
  }

  async function loadRuns(preferredRunId?: string): Promise<void> {
    loadingRuns.value = true;
    try {
      const values = await api('/api/harness/runs');
      apiAvailable.value = true;
      runSummaries.value = Array.isArray(values) ? values : [];
      const targetId = preferredRunId
        || (selectedRun.value && selectedRun.value.runId)
        || (runSummaries.value[0] && runSummaries.value[0].runId);
      if (targetId) {
        await loadRun(targetId);
      } else {
        clearSelection();
      }
    } catch (error: any) {
      if (!HarnessAdminUtils.harnessApiAvailable(error.status, error.body)) {
        apiAvailable.value = false;
        clearSelection();
      } else {
        showError('加载 Harness Run 失败', error);
      }
    } finally {
      loadingRuns.value = false;
    }
  }

  function clearSelection(): void {
    selectedRun.value = null;
    events.value = [];
    deployments.value = [];
    deploymentReadiness.value = null;
    snapshot.value = null;
    runtime.value = null;
    loadedStageKey.value = '';
    conversationMessages.value = [];
    conversationDraft.value = '';
    originalRequirement.value = '';
  }

  async function loadRun(runId: string): Promise<void> {
    loadingDetail.value = true;
    try {
      const base = runUrl(runId);
      const values = await Promise.all([
        api(base),
        api(base + '/events'),
        api(base + '/deployments')
      ]);
      selectedRun.value = values[0];
      // 切换 Run 时重置已加载键，强制 loadStageResources 重新干净加载新 Run 的资源
      loadedStageKey.value = '';
      events.value = Array.isArray(values[1]) ? values[1] : [];
      deployments.value = Array.isArray(values[2]) ? values[2] : [];
      if (!selectedRun.value.stages.some((item: any) => item.stage === selectedStageName.value)) {
        selectedStageName.value = 'ANALYSIS';
      }
      if (!conversationNonce.value) {
        conversationNonce.value = randomToken();
      }
      await Promise.all([loadOriginalRequirement(), loadConversation(), loadStageResources()]);
    } catch (error: any) {
      showError('加载 Run 详情失败', error);
    } finally {
      loadingDetail.value = false;
    }
  }

  async function refreshSelected(): Promise<void> {
    if (selectedRun.value) {
      await loadRun(selectedRun.value.runId);
      const summaries = await api('/api/harness/runs');
      runSummaries.value = Array.isArray(summaries) ? summaries : [];
    } else {
      await loadRuns();
    }
  }

  async function loadOriginalRequirement(): Promise<void> {
    originalRequirement.value = '';
    const artifact = latestArtifact('ORIGINAL_REQUIREMENT');
    if (!artifact || !selectedRun.value) {
      return;
    }
    try {
      const response = await fetch(HarnessAdminUtils.artifactDownloadUrl(
        selectedRun.value.runId, artifact.artifactId));
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      originalRequirement.value = await response.text();
    } catch (error: any) {
      originalRequirement.value = '原始需求读取失败：' + (error.message || error);
    }
  }

  async function selectStage(stage: string): Promise<void> {
    selectedStageName.value = stage;
    conversationNonce.value = randomToken();
    await loadStageResources();
    scrollConversationToEnd();
  }

  async function loadConversation(): Promise<void> {
    if (!selectedRun.value) {
      conversationMessages.value = [];
      return;
    }
    conversationLoading.value = true;
    try {
      const values = await api(runUrl(selectedRun.value.runId) + '/conversation');
      conversationMessages.value = Array.isArray(values) ? values : [];
      scrollConversationToEnd();
    } finally {
      conversationLoading.value = false;
    }
  }

  function scrollConversationToEnd(): void {
    nextTick(() => {
      if (conversationFeed.value) {
        conversationFeed.value.scrollTop = conversationFeed.value.scrollHeight;
      }
    });
  }

  async function loadStageResources(): Promise<void> {
    const run = selectedRun.value;
    const stage = selectedStage.value;
    const attempt = HarnessAdminUtils.currentAttempt(stage);
    if (!run || !stage || !attempt) {
      loadedStageKey.value = '';
      snapshot.value = null;
      runtime.value = null;
      deploymentReadiness.value = null;
      return;
    }
    const stageKey = stage.stage + '#' + attempt.number;
    // 仅在阶段/Attempt 真正切换时清空旧值。轮询每 2s 调一次本函数，
    // 若每次都先清 runtime，会让 runtimeBusy 短暂变 false，导致工作指示器、
    // 输入框可用态、conversationHint 每 2s 闪烁。
    if (loadedStageKey.value !== stageKey) {
      snapshot.value = null;
      runtime.value = null;
      deploymentReadiness.value = null;
    }
    loadedStageKey.value = stageKey;
    const base = stageUrl(stage.stage) + '/attempts/' + attempt.number;
    const selectedStageAtStart = stage.stage;
    const values = await Promise.all([
      optionalApi(base + '/capability-snapshot'),
      optionalApi(base + '/execution'),
      stage.stage === 'DEPLOYMENT' && stage.status === 'RUNNING'
        ? optionalApi(stageUrl('DEPLOYMENT') + '/deployment-readiness')
        : Promise.resolve(null)
    ]);
    if (selectedStageName.value === selectedStageAtStart) {
      snapshot.value = values[0];
      runtime.value = values[1];
      deploymentReadiness.value = values[2];
    }
  }

  function latestArtifact(type: string): any {
    if (!selectedRun.value) {
      return null;
    }
    return (selectedRun.value.artifacts || [])
      .filter((item: any) => item.artifactType === type)
      .sort((left: any, right: any) => right.version - left.version)[0] || null;
  }

  function openCreate(): void {
    Object.assign(createForm, {
      title: '',
      workingDir: '',
      originalRequirement: '',
      agentType: 'CODEX',
      environment: 'local',
      definitionVersion: 'harness@1.0.0'
    });
    createNonce.value = randomToken();
    createOpen.value = true;
  }

  async function createRun(): Promise<void> {
    if (!createForm.title.trim() || !createForm.workingDir.trim()
        || !createForm.originalRequirement.trim()) {
      ElMessage.warning('标题、工作目录和原始需求不能为空');
      return;
    }
    actionLoading.value = true;
    try {
      const result = await post('/api/harness/runs', {
        title: createForm.title.trim(),
        workingDir: createForm.workingDir.trim(),
        originalRequirement: createForm.originalRequirement,
        agentType: 'CODEX',
        environment: 'local',
        definitionVersion: createForm.definitionVersion
      }, 'create:' + createNonce.value);
      createOpen.value = false;
      ElMessage.success('Harness Run 已创建');
      await loadRuns(result.runId);
      scrollSelectedRunIntoView();
    } catch (error: any) {
      showError('创建 Run 失败', error);
    } finally {
      actionLoading.value = false;
    }
  }

  async function startStage(): Promise<void> {
    await runAction('启动阶段', async () => {
      const stage = selectedStageName.value;
      await post(stageUrl(stage) + '/start', undefined,
        'start:' + selectedRun.value.runId + ':' + stage);
    });
  }

  async function retryStage(): Promise<void> {
    try {
      await ElMessageBox.confirm(
        '重试会创建新的不可变 Attempt，并使当前阶段及下游旧结果失效。',
        '确认重试', { type: 'warning' });
    } catch (error) {
      return;
    }
    await runAction('重试阶段', async () => {
      const stage = selectedStageName.value;
      const attempt = selectedAttempt.value;
      await post(stageUrl(stage) + '/retry', undefined,
        'retry:' + selectedRun.value.runId + ':' + stage + ':' + (attempt ? attempt.number : 0));
    });
  }

  async function cancelRun(): Promise<void> {
    let prompt: any;
    try {
      prompt = await ElMessageBox.prompt(
        '取消只停止当前 Run/Runtime；不会自动 rollback 或重放外部动作。',
        '取消 Run', { inputPlaceholder: '请输入取消原因', inputValidator: (value: string) => Boolean(value && value.trim()) });
    } catch (error) {
      return;
    }
    await runAction('取消 Run', () => post(runUrl(selectedRun.value.runId) + '/cancel', {
      reason: prompt.value.trim()
    }));
  }

  async function resolveSnapshot(): Promise<void> {
    // 兼容旧入口（已迁移到对话框 confirmResolveSnapshot）
    await confirmResolveSnapshot();
  }

  async function confirmResolveSnapshot(): Promise<void> {
    if (!capabilityForm.currentInput.trim()) {
      ElMessage.warning('当前阶段输入不能为空');
      return;
    }
    snapshotLoading.value = true;
    try {
      snapshot.value = await post(stageUrl(selectedStageName.value) + '/capability-snapshot', {
        explicitSkillIds: csv(capabilityForm.explicitSkillIds),
        technicalTags: csv(capabilityForm.technicalTags),
        approvedWorkspaceSkillIds: csv(capabilityForm.approvedWorkspaceSkillIds),
        readableFileRoots: csv(capabilityForm.readableFileRoots),
        writableFileRoots: csv(capabilityForm.writableFileRoots),
        executableCommands: csv(capabilityForm.executableCommands),
        explicitMcpServerIds: csv(capabilityForm.explicitMcpServerIds),
        requiredMcpServerIds: csv(capabilityForm.requiredMcpServerIds),
        grantedMcpServerIds: csv(capabilityForm.grantedMcpServerIds),
        currentInput: capabilityForm.currentInput
      });
      openCapabilityPanel.value = false;
      ElMessage.success('Capability Snapshot 已固化');
    } catch (error: any) {
      showError('固化 Snapshot 失败', error);
    } finally {
      snapshotLoading.value = false;
    }
  }

  async function launchRuntime(): Promise<void> {
    await runAction('启动 Runtime', async () => {
      const stage = selectedStageName.value;
      const attempt = selectedAttempt.value;
      await post(stageUrl(stage) + '/executions', undefined,
        'runtime:' + selectedRun.value.runId + ':' + stage + ':' + attempt.number);
    });
  }

  async function sendConversation(): Promise<void> {
    const message = conversationDraft.value.trim();
    if (!message || !canSendConversation.value || !selectedRun.value) {
      return;
    }
    const runId = selectedRun.value.runId;
    const stage = selectedStageName.value;
    const nonce = conversationNonce.value || randomToken();
    conversationNonce.value = nonce;
    actionLoading.value = true;
    try {
      await post(stageUrl(stage) + '/conversation', { message },
        'conversation:' + runId + ':' + stage + ':' + nonce);
      conversationDraft.value = '';
      conversationNonce.value = randomToken();
      ElMessage.success('修改意见已发送，Codex Runtime 已启动');
      await loadRun(runId);
    } catch (error: any) {
      showError('发送修改意见失败', error);
    } finally {
      actionLoading.value = false;
    }
  }

  async function runGates(): Promise<void> {
    const rules = selectedStage.value ? selectedStage.value.deterministicGates || [] : [];
    if (rules.length === 0) {
      ElMessage.warning('当前阶段没有确定性 Gate');
      return;
    }
    await runAction('执行 Gate', async () => {
      for (const rule of rules) {
        await post(stageUrl(selectedStageName.value) + '/gates', { rule });
      }
    });
  }

  async function requestApproval(): Promise<void> {
    await runAction('请求阶段批准', () =>
      post(stageUrl(selectedStageName.value) + '/request-approval'));
  }

  async function validateAndRequestApproval(): Promise<void> {
    if (!canValidateConversation.value || !selectedStage.value) {
      return;
    }
    actionLoading.value = true;
    try {
      const rules = selectedStage.value.deterministicGates || [];
      for (const rule of rules) {
        await post(stageUrl(selectedStageName.value) + '/gates', { rule });
      }
      await post(stageUrl(selectedStageName.value) + '/request-approval');
      ElMessage.success('确定性校验通过，已进入待批准状态');
      await refreshSelected();
    } catch (error: any) {
      showError('校验并请求批准失败', error);
      await refreshSelected();
    } finally {
      actionLoading.value = false;
    }
  }

  function openApproval(decision: string): void {
    if (!selectedStage.value || !selectedStage.value.artifactBaselineHash) {
      ElMessage.warning('当前阶段尚未生成待审批 Artifact 基线');
      return;
    }
    approvalDecision.value = decision;
    approvalForm.reason = '';
    approvalOpen.value = true;
    loadApprovalArtifact();
  }

  // 加载当前阶段最新 artifact 正文,供审批对话框展示
  async function loadApprovalArtifact(): Promise<void> {
    approvalArtifactContent.value = '';
    approvalArtifactContentType.value = '';
    const artifact = stageArtifacts.value[0];
    if (!artifact || !selectedRun.value) return;
    approvalArtifactLoading.value = true;
    try {
      const response = await fetch(artifactUrl(artifact));
      if (!response.ok) throw new Error('HTTP ' + response.status);
      approvalArtifactContent.value = await response.text();
      approvalArtifactContentType.value = artifact.contentType || '';
    } catch (error: any) {
      approvalArtifactContent.value = '待审批 Artifact 读取失败：' + (error.message || error);
      approvalArtifactContentType.value = 'text/plain';
    } finally {
      approvalArtifactLoading.value = false;
    }
  }

  // 产物 Tab 点"查看"弹出 artifact 正文预览
  async function previewArtifact(artifact: any): Promise<void> {
    if (!artifact || !selectedRun.value) return;
    previewOpen.value = true;
    previewLoading.value = true;
    previewContent.value = '';
    previewContentType.value = artifact.contentType || '';
    previewTitle.value = (artifact.artifactType || 'Artifact') + ' v' + (artifact.version || '?');
    try {
      const response = await fetch(artifactUrl(artifact));
      if (!response.ok) throw new Error('HTTP ' + response.status);
      previewContent.value = await response.text();
    } catch (error: any) {
      previewContent.value = '读取失败：' + (error.message || error);
      previewContentType.value = 'text/plain';
    } finally {
      previewLoading.value = false;
    }
  }

  async function submitApproval(): Promise<void> {
    if (!approvalForm.reason.trim()) {
      ElMessage.warning('审批理由不能为空');
      return;
    }
    const decision = approvalDecision.value;
    await runAction(decision === 'approve' ? '批准阶段' : '拒绝阶段', async () => {
      const stage = selectedStageName.value;
      const hash = selectedStage.value.artifactBaselineHash;
      await post(stageUrl(stage) + '/' + decision, {
        artifactBaselineHash: hash,
        reason: approvalForm.reason.trim()
      }, decision + ':' + selectedRun.value.runId + ':' + stage + ':' + hash);
      approvalOpen.value = false;
    });
  }

  function openQuestion(): void {
    Object.assign(questionForm, {
      questionId: 'question-' + Date.now(),
      question: '',
      blocking: true
    });
    questionOpen.value = true;
  }

  async function submitQuestion(): Promise<void> {
    if (!questionForm.questionId.trim() || !questionForm.question.trim()) {
      ElMessage.warning('问题 ID 和问题内容不能为空');
      return;
    }
    await runAction('登记补充问题', async () => {
      await post(stageUrl(selectedStageName.value) + '/questions', {
        questionId: questionForm.questionId.trim(),
        question: questionForm.question.trim(),
        blocking: questionForm.blocking
      });
      questionOpen.value = false;
    });
  }

  async function answerQuestion(question: any): Promise<void> {
    const answer = String(answerDrafts[question.questionId] || '').trim();
    if (!answer) {
      ElMessage.warning('回答不能为空');
      return;
    }
    await runAction('回答补充问题', async () => {
      await post(runUrl(selectedRun.value.runId) + '/questions/'
        + encodeURIComponent(question.questionId) + '/answer', { answer });
      answerDrafts[question.questionId] = '';
    });
  }

  async function approveDeployment(): Promise<void> {
    if (!deploymentReadiness.value) {
      ElMessage.warning('当前部署输入基线尚未就绪');
      return;
    }
    let prompt: any;
    try {
      prompt = await ElMessageBox.prompt(
        '该批准仅授权当前 Hash 的一次 local 部署动作，不代表最终交付批准。',
        '批准 local 部署', { inputPlaceholder: '请输入部署批准理由', inputValidator: (value: string) => Boolean(value && value.trim()) });
    } catch (error) {
      return;
    }
    await runAction('批准 local 部署', () => {
      const hash = deploymentReadiness.value.inputBaselineHash;
      return post(stageUrl('DEPLOYMENT') + '/deployment-approval', {
        inputBaselineHash: hash,
        reason: prompt.value.trim()
      }, 'deployment-approval:' + selectedRun.value.runId + ':'
        + deploymentReadiness.value.attemptNumber + ':' + hash);
    });
  }

  function openDeployment(): void {
    deploymentForm.templateId = 'local-default';
    deploymentOpen.value = true;
  }

  async function startDeployment(): Promise<void> {
    if (!deploymentForm.templateId.trim() || !deploymentReadiness.value) {
      ElMessage.warning('部署模板和已批准输入基线不能为空');
      return;
    }
    await runAction('执行 local 部署', async () => {
      const hash = deploymentReadiness.value.inputBaselineHash;
      await post(stageUrl('DEPLOYMENT') + '/deployments', {
        templateId: deploymentForm.templateId.trim(),
        approvedInputBaselineHash: hash
      }, 'deployment:' + selectedRun.value.runId + ':'
        + deploymentReadiness.value.attemptNumber + ':' + hash + ':' + deploymentForm.templateId.trim());
      deploymentOpen.value = false;
    });
  }

  async function reconcileDeployment(execution: any): Promise<void> {
    let prompt: any;
    try {
      prompt = await ElMessageBox.prompt(
        '只在人工确认部署未成功后执行；该动作会将不确定执行对账为失败。',
        '人工对账', { inputPlaceholder: '请输入人工核查证据/原因', inputValidator: (value: string) => Boolean(value && value.trim()) });
    } catch (error) {
      return;
    }
    await runAction('部署人工对账', () =>
      post(runUrl(selectedRun.value.runId) + '/deployments/'
        + encodeURIComponent(execution.executionId) + '/reconcile', {
        reason: prompt.value.trim()
      }));
  }

  async function runAction(label: string, operation: () => Promise<void>): Promise<void> {
    actionLoading.value = true;
    try {
      await operation();
      ElMessage.success(label + '已受理');
      await refreshSelected();
    } catch (error: any) {
      showError(label + '失败', error);
    } finally {
      actionLoading.value = false;
    }
  }

  function stageMeta(status: string): any {
    return HarnessAdminUtils.stageStatusMeta(status);
  }

  // Markdown 渲染（复用主站 formatters.js：marked + DOMPurify 净化）
  function renderMarkdown(text: string): string {
    return renderMarkdownFn(text || '');
  }

  // Artifact 正文按 contentType 渲染:JSON pretty print、纯文本 pre、Markdown 渲染
  function renderArtifactContent(content: string, contentType: string): string {
    const text = String(content || '');
    const escape = escapeHtml;
    if (contentType === 'application/json') {
      try {
        return '<pre class="harness-artifact-json">' + escape(JSON.stringify(JSON.parse(text), null, 2)) + '</pre>';
      } catch (e) {
        return '<pre class="harness-artifact-plain">' + escape(text) + '</pre>';
      }
    }
    if (contentType === 'text/plain') {
      return '<pre class="harness-artifact-plain">' + escape(text) + '</pre>';
    }
    return renderMarkdown(text);
  }

  // Artifact 消息的类型文案与是否渲染为卡片
  const artifactCardTypes = ['REQUIREMENT', 'DESIGN_DOC', 'IMPLEMENTATION_SUMMARY',
    'FINAL_REPORT', 'ORIGINAL_REQUIREMENT'];
  const artifactTypeLabels: Record<string, string> = {
    REQUIREMENT: '需求基线',
    DESIGN_DOC: '方案设计',
    IMPLEMENTATION_SUMMARY: '实现总结',
    FINAL_REPORT: '最终报告',
    ORIGINAL_REQUIREMENT: '原始需求'
  };
  function isArtifactMessage(message: any): boolean {
    return Boolean(message && artifactCardTypes.includes(message.artifactType));
  }
  function artifactTypeLabel(message: any): string {
    return artifactTypeLabels[message && message.artifactType] || (message && message.artifactType) || '产物';
  }
  // 消息只带 artifactType 标签；从当前 Run 的 artifacts 里按 stage+attempt+type 反查完整产物（hash/version/下载）
  function messageArtifact(message: any): any {
    if (!selectedRun.value || !message || !message.artifactType) {
      return null;
    }
    return (selectedRun.value.artifacts || [])
      .filter((a: any) => a.stage === message.stage
        && Number(a.attempt) === Number(message.attemptNumber)
        && a.artifactType === message.artifactType)
      .sort((left: any, right: any) => right.version - left.version)[0] || null;
  }

  function stageLabel(stage: string): string {
    return stageLabels[stage] || stage;
  }

  // Stepper 状态图标（用 SVG/Unicode 字符，避免额外依赖）
  const stageStatusIcons: Record<string, string> = {
    PASSED: '<svg viewBox="0 0 24 24" width="16" height="16"><polyline points="5,12 10,17 19,7" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    RUNNING: '<span class="harness-stepper-dot"></span>',
    WAITING_APPROVAL: '<svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 2 L22 20 L2 20 Z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><line x1="12" y1="10" x2="12" y2="14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="17" r="1" fill="currentColor"/></svg>',
    WAITING_INPUT: '<svg viewBox="0 0 24 24" width="16" height="16"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/><text x="12" y="16" text-anchor="middle" font-size="12" font-weight="700" fill="currentColor">?</text></svg>',
    FAILED: '<svg viewBox="0 0 24 24" width="16" height="16"><line x1="6" y1="6" x2="18" y2="18" stroke="currentColor" stroke-width="3" stroke-linecap="round"/><line x1="18" y1="6" x2="6" y2="18" stroke="currentColor" stroke-width="3" stroke-linecap="round"/></svg>',
    INVALIDATED: '<svg viewBox="0 0 24 24" width="16" height="16"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="4 3"/><line x1="7" y1="7" x2="17" y2="17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    PENDING: '',
    CANCELLED: '<svg viewBox="0 0 24 24" width="14" height="14"><rect x="5" y="5" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"/></svg>',
    CANCELLING: '<span class="harness-stepper-dot"></span>'
  };

  function stageStatusIcon(status: string): string {
    return stageStatusIcons[status] || '';
  }

  function stageAttemptNumber(stage: any): number | null {
    if (!stage || !Array.isArray(stage.attempts) || stage.attempts.length === 0) {
      return null;
    }
    return stage.attempts[stage.attempts.length - 1].number;
  }

  // Stepper 连接线状态：当前阶段及之前为实线激活
  function isConnectorActive(index: number): boolean {
    const stages = selectedRun.value ? selectedRun.value.stages : [];
    if (!stages.length) return false;
    const currentIdx = stages.findIndex((s: any) => s.stage === selectedStageName.value);
    // 索引小于当前选中阶段的连接线为激活
    return index < currentIdx;
  }

  // Stepper 连接线失效：下游阶段 INVALIDATED 时连接线变虚线
  function isConnectorInvalidated(index: number): boolean {
    const stages = selectedRun.value ? selectedRun.value.stages : [];
    if (!stages.length || index + 1 >= stages.length) return false;
    const nextStage = stages[index + 1];
    return nextStage.status === 'INVALIDATED';
  }

  // 阶段产物摘要：从该阶段最新已批准 Artifact 中提取关键指标
  function stageSummary(stage: any): string {
    if (!stage || !selectedRun.value) return '';
    const artifacts = (selectedRun.value.artifacts || [])
      .filter((a: any) => a.stage === stage.stage && a.classification === 'APPROVED');
    if (artifacts.length === 0) return '';
    // 优先取主产物做摘要
    const summaryArtifact = artifacts.find((a: any) =>
      a.artifactType === 'REQUIREMENT'
      || a.artifactType === 'DESIGN_DOC'
      || a.artifactType === 'IMPLEMENTATION_SUMMARY');
    if (!summaryArtifact) return '';
    // 从 contentSize 或 version 推断信息密度（MVP 阶段先用版本号+类型提示）
    const typeLabel: Record<string, string> = {
      REQUIREMENT: '需求基线',
      DESIGN_DOC: '方案基线',
      IMPLEMENTATION_SUMMARY: '实现基线'
    };
    return (typeLabel[summaryArtifact.artifactType] || summaryArtifact.artifactType) + ' v' + summaryArtifact.version;
  }

  function fmtTime(value: any): string {
    if (!value) {
      return '-';
    }
    const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
  }

  // 相对时间（"2 小时前"），hover 由 title 显示绝对时间
  function fmtRelative(value: any): string {
    if (!value) {
      return '-';
    }
    const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
    if (Number.isNaN(date.getTime())) {
      return String(value);
    }
    const diff = Date.now() - date.getTime();
    if (diff < 0) {
      return fmtTime(value);
    }
    const sec = Math.floor(diff / 1000);
    if (sec < 60) {
      return '刚刚';
    }
    const min = Math.floor(sec / 60);
    if (min < 60) {
      return min + ' 分钟前';
    }
    const hr = Math.floor(min / 60);
    if (hr < 24) {
      return hr + ' 小时前';
    }
    const day = Math.floor(hr / 24);
    if (day < 30) {
      return day + ' 天前';
    }
    return fmtTime(value);
  }

  // Run 归类到筛选桶与状态点颜色统一由 harness-utils 的 Run 级状态表驱动：
  // 列表接口只返回 Run summary（无 stages），从 stages 推导会静默退化成空筛选。

  // 新建 Run 原始需求结构化模板
  const requirementTemplate = '## 背景\n\n<描述问题背景与动机>\n\n## 目标\n\n<本 Run 要达成的可观测目标>\n\n## 验收标准\n\n- AC-1: <可验证的验收条件>\n- AC-2: <可验证的验收条件>\n\n## 范围\n\n- 包含: <本 Run 范围内的事项>\n- 不包含: <明确排除的事项>';
  function insertRequirementTemplate(): void {
    createForm.originalRequirement = requirementTemplate;
  }

  // 工作目录自动联想（从历史 Run 的工作目录中过滤）
  function queryWorkingDir(queryString: string, callback: (results: { value: string }[]) => void): void {
    const suggestions = workingDirSuggestions.value;
    const keyword = (queryString || '').toLowerCase();
    const results = keyword
      ? suggestions.filter(value => value.toLowerCase().includes(keyword))
      : suggestions;
    callback(results.map(value => ({ value })));
  }

  // 新建 Run 后自动滚动到左侧选中项
  function scrollSelectedRunIntoView(): void {
    nextTick(() => {
      const el = document.querySelector('.harness-run-item.selected');
      if (el && el.scrollIntoView) {
        el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
    });
  }

  // 对话输入框：Ctrl/Cmd+Enter 发送，普通 Enter 换行
  function onComposerEnter(event: KeyboardEvent): void {
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      sendConversation();
    }
  }

  // 全局数字键 1/2/3/4 切换阶段（焦点在输入框时不响应）
  function onGlobalKeydown(event: KeyboardEvent): void {
    if (!selectedRun.value) {
      return;
    }
    const target = event.target as HTMLElement | null;
    const tag = target && target.tagName;
    if (tag && ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || (target && target.isContentEditable)) {
      return;
    }
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const map: Record<string, string> = { '1': 'ANALYSIS', '2': 'DESIGN', '3': 'IMPLEMENTATION', '4': 'DEPLOYMENT' };
    const stage = map[event.key];
    if (!stage) {
      return;
    }
    const stages = selectedRun.value.stages || [];
    if (stages.some((s: any) => s.stage === stage)) {
      event.preventDefault();
      selectStage(stage);
    }
  }
  window.addEventListener('keydown', onGlobalKeydown);

  function deploymentStatusType(status: string): string {
    return {
      PREPARED: 'info',
      RUNNING: 'warning',
      SUCCEEDED: 'success',
      FAILED: 'danger',
      RECONCILIATION_REQUIRED: 'warning'
    }[status] || 'info';
  }

  function runtimeStatusType(status: string): string {
    return {
      SUCCEEDED: 'success',
      FAILED: 'danger',
      TIMED_OUT: 'danger',
      LOST: 'danger',
      CANCELLED: 'info'
    }[status] || 'warning';
  }

  function artifactUrl(artifact: any): string {
    return selectedRun.value
      ? HarnessAdminUtils.artifactDownloadUrl(selectedRun.value.runId, artifact.artifactId) : '#';
  }

  function reportUrl(): string {
    return selectedRun.value ? runUrl(selectedRun.value.runId) + '/report' : '#';
  }

  useHarnessSse({
    selectedRun,
    conversationMessages,
    runUrl,
    api,
    loadStageResources,
    scrollConversationToEnd,
    showError,
  });

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onGlobalKeydown);
    window.removeEventListener('resize', onResize);
    idempotencyKeyCache.clear();
  });

  return {
    stageNames,
    stageLabels,
    runSummaries,
    selectedRun,
    selectedStageName,
    selectedStage,
    selectedAttempt,
    events,
    deployments,
    deploymentReadiness,
    snapshot,
    runtime,
    conversationMessages,
    conversationDraft,
    conversationFeed,
    conversationLoading,
    originalRequirement,
    apiAvailable,
    loadingRuns,
    loadingDetail,
    actionLoading,
    snapshotLoading,
    isMobile,
    createOpen,
    approvalOpen,
    approvalDecision,
    questionOpen,
    deploymentOpen,
    createForm,
    capabilityForm,
    approvalForm,
    previewOpen,
    previewContent,
    previewContentType,
    previewTitle,
    previewLoading,
    approvalArtifactContent,
    approvalArtifactContentType,
    approvalArtifactLoading,
    questionForm,
    deploymentForm,
    auditExpanded,
    openCapabilityPanel,
    answerDrafts,
    runFilter,
    runFilterOptions,
    runSearch,
    runSearchOpen,
    artifactCollapsed,
    filteredRuns,
    workingDirSuggestions,
    currentGates,
    gateFailures,
    stageArtifacts,
    stageApprovals,
    currentQuestions,
    unansweredQuestions,
    stageConversationMessages,
    runtimeBusy,
    canSendConversation,
    canValidateConversation,
    conversationHint,
    runtimeHint,
    finalReport,
    canStartStage,
    canRetryStage,
    canOperateRunningStage,
    canDecideApproval,
    canStartDeployment,
    loadRuns,
    loadRun,
    refreshSelected,
    selectStage,
    openCreate,
    createRun,
    startStage,
    retryStage,
    cancelRun,
    resolveSnapshot,
    confirmResolveSnapshot,
    launchRuntime,
    sendConversation,
    runGates,
    requestApproval,
    validateAndRequestApproval,
    openApproval,
    previewArtifact,
    submitApproval,
    openQuestion,
    submitQuestion,
    answerQuestion,
    approveDeployment,
    openDeployment,
    startDeployment,
    reconcileDeployment,
    stageMeta,
    stageLabel,
    stageStatusIcon,
    stageAttemptNumber,
    isConnectorActive,
    isConnectorInvalidated,
    stageSummary,
    fmtTime,
    fmtRelative,
    renderMarkdown,
    renderArtifactContent,
    isArtifactMessage,
    artifactTypeLabel,
    messageArtifact,
    runStageDotClass: HarnessAdminUtils.runStageDotClass,
    runStatusMeta: HarnessAdminUtils.runStatusMeta,
    insertRequirementTemplate,
    queryWorkingDir,
    onComposerEnter,
    deploymentStatusType,
    runtimeStatusType,
    artifactUrl,
    reportUrl,
    selectionReasonLabel: HarnessAdminUtils.selectionReasonLabel,
    rejectionReasonLabel: HarnessAdminUtils.rejectionReasonLabel,
    capabilityDecisionLabel: HarnessAdminUtils.capabilityDecisionLabel,
    shortHash: HarnessAdminUtils.shortHash,
    reconciliationMessage: HarnessAdminUtils.reconciliationMessage
  };
}