/**
 * 管理后台 Harness M4 四阶段控制台。
 *
 * @author alex
 */
const { ref, reactive, computed, nextTick, onBeforeUnmount } = Vue;

bootstrapAdminApp({
  setup() {
    const stageNames = ['ANALYSIS', 'DESIGN', 'IMPLEMENTATION', 'DEPLOYMENT'];
    const stageLabels = {
      ANALYSIS: '需求分析',
      DESIGN: '方案设计',
      IMPLEMENTATION: 'TDD 实现',
      DEPLOYMENT: '部署验证'
    };
    const runSummaries = ref([]);
    const selectedRun = ref(null);
    const selectedStageName = ref('ANALYSIS');
    const events = ref([]);
    const deployments = ref([]);
    const deploymentReadiness = ref(null);
    const snapshot = ref(null);
    const runtime = ref(null);
    const conversationMessages = ref([]);
    const conversationDraft = ref('');
    const conversationNonce = ref('');
    const conversationFeed = ref(null);
    const conversationLoading = ref(false);
    const originalRequirement = ref('');
    const apiAvailable = ref(true);
    const loadingRuns = ref(false);
    const loadingDetail = ref(false);
    const actionLoading = ref(false);
    const snapshotLoading = ref(false);
    const isMobile = ref(window.innerWidth <= 768);
    const createOpen = ref(false);
    const approvalOpen = ref(false);
    const approvalDecision = ref('approve');
    const questionOpen = ref(false);
    const deploymentOpen = ref(false);
    const createNonce = ref('');
    const answerDrafts = reactive({});

    const createForm = reactive({
      title: '',
      workingDir: '',
      originalRequirement: '',
      agentType: 'CODEX',
      environment: 'local',
      definitionVersion: 'harness@1.0.0'
    });
    const capabilityForm = reactive({
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
    const approvalForm = reactive({ reason: '' });
    const questionForm = reactive({ questionId: '', question: '', blocking: true });
    const deploymentForm = reactive({ templateId: 'local-default' });

    window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

    const selectedStage = computed(() => {
      const stages = selectedRun.value && Array.isArray(selectedRun.value.stages)
        ? selectedRun.value.stages : [];
      return stages.find(item => item.stage === selectedStageName.value) || null;
    });
    const selectedAttempt = computed(() => HarnessAdminUtils.currentAttempt(selectedStage.value));
    const currentGates = computed(() => {
      if (!selectedRun.value || !selectedAttempt.value) {
        return [];
      }
      return (selectedRun.value.gateResults || []).filter(item =>
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
        .filter(item => item.stage === selectedStageName.value)
        .slice()
        .sort((left, right) => right.version - left.version || right.createdAt - left.createdAt);
    });
    const stageApprovals = computed(() => {
      if (!selectedRun.value) {
        return [];
      }
      return (selectedRun.value.approvals || [])
        .filter(item => item.stage === selectedStageName.value)
        .slice()
        .reverse();
    });
    const currentQuestions = computed(() => {
      if (!selectedRun.value || !selectedAttempt.value) {
        return [];
      }
      return (selectedRun.value.questions || []).filter(item =>
        item.stage === selectedStageName.value
          && Number(item.attempt) === Number(selectedAttempt.value.number));
    });
    const unansweredQuestions = computed(() => currentQuestions.value.filter(
      item => !item.answeredAt));
    const stageConversationMessages = computed(() => conversationMessages.value.filter(
      item => item.stage === selectedStageName.value));
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

    function csv(value) {
      return String(value || '').split(',').map(item => item.trim()).filter(Boolean);
    }

    function runUrl(runId) {
      return '/api/harness/runs/' + encodeURIComponent(runId);
    }

    function stageUrl(stage) {
      if (!selectedRun.value) {
        throw new Error('请先选择 Run');
      }
      return runUrl(selectedRun.value.runId) + '/stages/' + encodeURIComponent(stage);
    }

    function randomToken() {
      if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID();
      }
      return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    }

    function idempotencyKey(identity) {
      const storageKey = 'harness-command:' + identity;
      let value = window.sessionStorage.getItem(storageKey);
      if (!value) {
        value = 'harness-ui-' + randomToken();
        window.sessionStorage.setItem(storageKey, value);
      }
      return value;
    }

    async function api(path, options) {
      const response = await fetch(path, options || {});
      const text = await response.text();
      let body = {};
      if (text) {
        try {
          body = JSON.parse(text);
        } catch (ignored) {
          body = text;
        }
      }
      if (!response.ok) {
        const message = body && typeof body === 'object'
          ? (body.message || body.error || body.code) : body;
        const error = new Error(message || ('HTTP ' + response.status));
        error.status = response.status;
        error.body = body;
        throw error;
      }
      return body;
    }

    async function optionalApi(path) {
      try {
        return await api(path);
      } catch (error) {
        if (error.status === 404) {
          return null;
        }
        throw error;
      }
    }

    async function post(path, payload, identity) {
      const headers = {};
      if (payload !== undefined) {
        headers['Content-Type'] = 'application/json';
      }
      if (identity) {
        headers['Idempotency-Key'] = idempotencyKey(identity);
      }
      return api(path, {
        method: 'POST',
        headers,
        body: payload === undefined ? undefined : JSON.stringify(payload)
      });
    }

    function showError(prefix, error) {
      ElementPlus.ElMessage.error(prefix + '：' + (error.message || error));
    }

    async function loadRuns(preferredRunId) {
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
      } catch (error) {
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

    function clearSelection() {
      selectedRun.value = null;
      events.value = [];
      deployments.value = [];
      deploymentReadiness.value = null;
      snapshot.value = null;
      runtime.value = null;
      conversationMessages.value = [];
      conversationDraft.value = '';
      originalRequirement.value = '';
    }

    async function loadRun(runId) {
      loadingDetail.value = true;
      try {
        const base = runUrl(runId);
        const values = await Promise.all([
          api(base),
          api(base + '/events'),
          api(base + '/deployments')
        ]);
        selectedRun.value = values[0];
        events.value = Array.isArray(values[1]) ? values[1] : [];
        deployments.value = Array.isArray(values[2]) ? values[2] : [];
        if (!selectedRun.value.stages.some(item => item.stage === selectedStageName.value)) {
          selectedStageName.value = 'ANALYSIS';
        }
        if (!conversationNonce.value) {
          conversationNonce.value = randomToken();
        }
        await Promise.all([loadOriginalRequirement(), loadConversation(), loadStageResources()]);
      } catch (error) {
        showError('加载 Run 详情失败', error);
      } finally {
        loadingDetail.value = false;
      }
    }

    async function refreshSelected() {
      if (selectedRun.value) {
        await loadRun(selectedRun.value.runId);
        const summaries = await api('/api/harness/runs');
        runSummaries.value = Array.isArray(summaries) ? summaries : [];
      } else {
        await loadRuns();
      }
    }

    async function loadOriginalRequirement() {
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
      } catch (error) {
        originalRequirement.value = '原始需求读取失败：' + (error.message || error);
      }
    }

    async function selectStage(stage) {
      selectedStageName.value = stage;
      conversationNonce.value = randomToken();
      await loadStageResources();
      scrollConversationToEnd();
    }

    async function loadConversation() {
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

    function scrollConversationToEnd() {
      nextTick(() => {
        if (conversationFeed.value) {
          conversationFeed.value.scrollTop = conversationFeed.value.scrollHeight;
        }
      });
    }

    async function loadStageResources() {
      snapshot.value = null;
      runtime.value = null;
      deploymentReadiness.value = null;
      const run = selectedRun.value;
      const stage = selectedStage.value;
      const attempt = HarnessAdminUtils.currentAttempt(stage);
      if (!run || !stage || !attempt) {
        return;
      }
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

    function latestArtifact(type) {
      if (!selectedRun.value) {
        return null;
      }
      return (selectedRun.value.artifacts || [])
        .filter(item => item.artifactType === type)
        .sort((left, right) => right.version - left.version)[0] || null;
    }

    function openCreate() {
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

    async function createRun() {
      if (!createForm.title.trim() || !createForm.workingDir.trim()
          || !createForm.originalRequirement.trim()) {
        ElementPlus.ElMessage.warning('标题、工作目录和原始需求不能为空');
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
        ElementPlus.ElMessage.success('Harness Run 已创建');
        await loadRuns(result.runId);
      } catch (error) {
        showError('创建 Run 失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    async function startStage() {
      await runAction('启动阶段', async () => {
        const stage = selectedStageName.value;
        await post(stageUrl(stage) + '/start', undefined,
          'start:' + selectedRun.value.runId + ':' + stage);
      });
    }

    async function retryStage() {
      try {
        await ElementPlus.ElMessageBox.confirm(
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

    async function cancelRun() {
      let prompt;
      try {
        prompt = await ElementPlus.ElMessageBox.prompt(
          '取消只停止当前 Run/Runtime；不会自动 rollback 或重放外部动作。',
          '取消 Run', { inputPlaceholder: '请输入取消原因', inputValidator: value => Boolean(value && value.trim()) });
      } catch (error) {
        return;
      }
      await runAction('取消 Run', () => post(runUrl(selectedRun.value.runId) + '/cancel', {
        reason: prompt.value.trim()
      }));
    }

    async function resolveSnapshot() {
      if (!capabilityForm.currentInput.trim()) {
        ElementPlus.ElMessage.warning('当前阶段输入不能为空');
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
        ElementPlus.ElMessage.success('Capability Snapshot 已固化');
      } catch (error) {
        showError('固化 Snapshot 失败', error);
      } finally {
        snapshotLoading.value = false;
      }
    }

    async function launchRuntime() {
      await runAction('启动 Runtime', async () => {
        const stage = selectedStageName.value;
        const attempt = selectedAttempt.value;
        await post(stageUrl(stage) + '/executions', undefined,
          'runtime:' + selectedRun.value.runId + ':' + stage + ':' + attempt.number);
      });
    }

    async function sendConversation() {
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
        ElementPlus.ElMessage.success('修改意见已发送，Codex Runtime 已启动');
        await loadRun(runId);
      } catch (error) {
        showError('发送修改意见失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    async function runGates() {
      const rules = selectedStage.value ? selectedStage.value.deterministicGates || [] : [];
      if (rules.length === 0) {
        ElementPlus.ElMessage.warning('当前阶段没有确定性 Gate');
        return;
      }
      await runAction('执行 Gate', async () => {
        for (const rule of rules) {
          await post(stageUrl(selectedStageName.value) + '/gates', { rule });
        }
      });
    }

    async function requestApproval() {
      await runAction('请求阶段批准', () =>
        post(stageUrl(selectedStageName.value) + '/request-approval'));
    }

    async function validateAndRequestApproval() {
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
        ElementPlus.ElMessage.success('确定性校验通过，已进入待批准状态');
        await refreshSelected();
      } catch (error) {
        showError('校验并请求批准失败', error);
        await refreshSelected();
      } finally {
        actionLoading.value = false;
      }
    }

    function openApproval(decision) {
      if (!selectedStage.value || !selectedStage.value.artifactBaselineHash) {
        ElementPlus.ElMessage.warning('当前阶段尚未生成待审批 Artifact 基线');
        return;
      }
      approvalDecision.value = decision;
      approvalForm.reason = '';
      approvalOpen.value = true;
    }

    async function submitApproval() {
      if (!approvalForm.reason.trim()) {
        ElementPlus.ElMessage.warning('审批理由不能为空');
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

    function openQuestion() {
      Object.assign(questionForm, {
        questionId: 'question-' + Date.now(),
        question: '',
        blocking: true
      });
      questionOpen.value = true;
    }

    async function submitQuestion() {
      if (!questionForm.questionId.trim() || !questionForm.question.trim()) {
        ElementPlus.ElMessage.warning('问题 ID 和问题内容不能为空');
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

    async function answerQuestion(question) {
      const answer = String(answerDrafts[question.questionId] || '').trim();
      if (!answer) {
        ElementPlus.ElMessage.warning('回答不能为空');
        return;
      }
      await runAction('回答补充问题', async () => {
        await post(runUrl(selectedRun.value.runId) + '/questions/'
          + encodeURIComponent(question.questionId) + '/answer', { answer });
        answerDrafts[question.questionId] = '';
      });
    }

    async function approveDeployment() {
      if (!deploymentReadiness.value) {
        ElementPlus.ElMessage.warning('当前部署输入基线尚未就绪');
        return;
      }
      let prompt;
      try {
        prompt = await ElementPlus.ElMessageBox.prompt(
          '该批准仅授权当前 Hash 的一次 local 部署动作，不代表最终交付批准。',
          '批准 local 部署', { inputPlaceholder: '请输入部署批准理由', inputValidator: value => Boolean(value && value.trim()) });
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

    function openDeployment() {
      deploymentForm.templateId = 'local-default';
      deploymentOpen.value = true;
    }

    async function startDeployment() {
      if (!deploymentForm.templateId.trim() || !deploymentReadiness.value) {
        ElementPlus.ElMessage.warning('部署模板和已批准输入基线不能为空');
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

    async function reconcileDeployment(execution) {
      let prompt;
      try {
        prompt = await ElementPlus.ElMessageBox.prompt(
          '只在人工确认部署未成功后执行；该动作会将不确定执行对账为失败。',
          '人工对账', { inputPlaceholder: '请输入人工核查证据/原因', inputValidator: value => Boolean(value && value.trim()) });
      } catch (error) {
        return;
      }
      await runAction('部署人工对账', () =>
        post(runUrl(selectedRun.value.runId) + '/deployments/'
          + encodeURIComponent(execution.executionId) + '/reconcile', {
          reason: prompt.value.trim()
        }));
    }

    async function runAction(label, operation) {
      actionLoading.value = true;
      try {
        await operation();
        ElementPlus.ElMessage.success(label + '已受理');
        await refreshSelected();
      } catch (error) {
        showError(label + '失败', error);
      } finally {
        actionLoading.value = false;
      }
    }

    function stageMeta(status) {
      return HarnessAdminUtils.stageStatusMeta(status);
    }

    function stageLabel(stage) {
      return stageLabels[stage] || stage;
    }

    function fmtTime(value) {
      if (!value) {
        return '-';
      }
      const date = typeof value === 'number' ? new Date(value) : new Date(String(value));
      return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false });
    }

    function deploymentStatusType(status) {
      return {
        PREPARED: 'info',
        RUNNING: 'warning',
        SUCCEEDED: 'success',
        FAILED: 'danger',
        RECONCILIATION_REQUIRED: 'warning'
      }[status] || 'info';
    }

    function runtimeStatusType(status) {
      return {
        SUCCEEDED: 'success',
        FAILED: 'danger',
        TIMED_OUT: 'danger',
        LOST: 'danger',
        CANCELLED: 'info'
      }[status] || 'warning';
    }

    function artifactUrl(artifact) {
      return selectedRun.value
        ? HarnessAdminUtils.artifactDownloadUrl(selectedRun.value.runId, artifact.artifactId) : '#';
    }

    function reportUrl() {
      return selectedRun.value ? runUrl(selectedRun.value.runId) + '/report' : '#';
    }

    let runtimePollInFlight = false;
    const runtimePollTimer = window.setInterval(async () => {
      if (runtimePollInFlight || !runtimeBusy.value || !selectedRun.value) {
        return;
      }
      runtimePollInFlight = true;
      const runId = selectedRun.value.runId;
      try {
        const base = runUrl(runId);
        const values = await Promise.all([api(base), api(base + '/conversation')]);
        if (!selectedRun.value || selectedRun.value.runId !== runId) {
          return;
        }
        selectedRun.value = values[0];
        conversationMessages.value = Array.isArray(values[1]) ? values[1] : [];
        await loadStageResources();
        scrollConversationToEnd();
      } catch (error) {
        showError('刷新 Codex 执行状态失败', error);
      } finally {
        runtimePollInFlight = false;
      }
    }, 2000);
    onBeforeUnmount(() => window.clearInterval(runtimePollTimer));

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
      questionForm,
      deploymentForm,
      answerDrafts,
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
      launchRuntime,
      sendConversation,
      runGates,
      requestApproval,
      validateAndRequestApproval,
      openApproval,
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
      fmtTime,
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
});
