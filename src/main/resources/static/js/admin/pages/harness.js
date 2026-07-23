/**
 * 管理后台 Harness Capability Snapshot 预览页。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const stages = ['ANALYSIS', 'DESIGN', 'IMPLEMENTATION', 'DEPLOYMENT'];
    const runId = ref('');
    const stage = ref('ANALYSIS');
    const attemptNumber = ref(1);
    const snapshot = ref(null);
    const loading = ref(false);
    const resolving = ref(false);
    const isMobile = ref(window.innerWidth <= 768);
    const form = reactive({
      explicitSkillIds: '',
      technicalTags: 'java',
      approvedWorkspaceSkillIds: '',
      readableFileRoots: 'workspace',
      writableFileRoots: '',
      executableCommands: '',
      upstreamArtifacts: 'No approved upstream Artifact content.',
      currentInput: ''
    });

    window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

    function csv(value) {
      return String(value || '').split(',').map(item => item.trim()).filter(Boolean);
    }

    function baseUrl() {
      if (!runId.value.trim()) {
        throw new Error('请先填写 Run ID');
      }
      return '/api/harness/runs/' + encodeURIComponent(runId.value.trim())
        + '/stages/' + encodeURIComponent(stage.value);
    }

    async function responseJson(response) {
      const text = await response.text();
      const body = text ? JSON.parse(text) : {};
      if (!response.ok) {
        throw new Error(body.message || body.error || ('HTTP ' + response.status));
      }
      return body;
    }

    async function loadSnapshot() {
      loading.value = true;
      try {
        const url = baseUrl() + '/attempts/' + attemptNumber.value + '/capability-snapshot';
        snapshot.value = await responseJson(await fetch(url));
      } catch (error) {
        ElementPlus.ElMessage.error('读取 Snapshot 失败：' + (error.message || error));
      } finally {
        loading.value = false;
      }
    }

    async function resolveSnapshot() {
      resolving.value = true;
      try {
        const payload = {
          explicitSkillIds: csv(form.explicitSkillIds),
          technicalTags: csv(form.technicalTags),
          approvedWorkspaceSkillIds: csv(form.approvedWorkspaceSkillIds),
          readableFileRoots: csv(form.readableFileRoots),
          writableFileRoots: csv(form.writableFileRoots),
          executableCommands: csv(form.executableCommands),
          upstreamArtifacts: form.upstreamArtifacts,
          currentInput: form.currentInput
        };
        snapshot.value = await responseJson(await fetch(baseUrl() + '/capability-snapshot', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        }));
        attemptNumber.value = snapshot.value.attemptNumber;
        ElementPlus.ElMessage.success('Capability Snapshot 已固化');
      } catch (error) {
        ElementPlus.ElMessage.error('固化 Snapshot 失败：' + (error.message || error));
      } finally {
        resolving.value = false;
      }
    }

    return {
      stages,
      runId,
      stage,
      attemptNumber,
      snapshot,
      loading,
      resolving,
      isMobile,
      form,
      loadSnapshot,
      resolveSnapshot,
      selectionReasonLabel: HarnessAdminUtils.selectionReasonLabel,
      rejectionReasonLabel: HarnessAdminUtils.rejectionReasonLabel,
      capabilityDecisionLabel: HarnessAdminUtils.capabilityDecisionLabel,
      shortHash: HarnessAdminUtils.shortHash
    };
  }
});
