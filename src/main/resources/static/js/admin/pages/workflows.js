/**
 * 管理后台「工作流」页。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const workflows = ref([]);
    const executions = ref([]);
    const selectedWorkflow = ref(null);
    const loading = ref(false);
    const executionLoading = ref(false);
    const editOpen = ref(false);
    const saving = ref(false);
    const runOpen = ref(false);
    const running = ref(false);
    const runTarget = ref(null);
    const runInputs = ref('{\n  "branch": "main"\n}');
    const detailOpen = ref(false);
    const detail = ref(null);
    const isMobile = ref(window.innerWidth <= 768);
    const editForm = reactive(emptyForm());

    window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768; });

    function emptyForm() {
      return {
        id: '',
        name: '',
        description: '',
        agentType: 'CODEX',
        workingDir: '',
        enabled: true,
        steps: [emptyStep()]
      };
    }

    function emptyStep() {
      return { name: 'review', promptTemplate: '', timeoutSeconds: 1800 };
    }

    async function loadAll() {
      await loadWorkflows();
      if (selectedWorkflow.value) {
        await loadExecutions(selectedWorkflow.value.id);
      }
    }

    async function loadWorkflows() {
      loading.value = true;
      try {
        const data = await fetch('/api/admin-workflows').then(r => r.json());
        workflows.value = Array.isArray(data) ? data : [];
        if (!selectedWorkflow.value && workflows.value.length > 0) {
          await selectWorkflow(workflows.value[0]);
        }
      } catch (e) {
        ElementPlus.ElMessage.error('加载工作流失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    async function selectWorkflow(row) {
      selectedWorkflow.value = row;
      await loadExecutions(row.id);
    }

    async function loadExecutions(workflowId) {
      executionLoading.value = true;
      try {
        const params = new URLSearchParams({ workflowId, page: '1', size: '50' });
        const data = await fetch('/api/admin-workflow-executions?' + params.toString()).then(r => r.json());
        executions.value = Array.isArray(data) ? data : [];
      } catch (e) {
        ElementPlus.ElMessage.error('加载执行历史失败: ' + e);
      } finally {
        executionLoading.value = false;
      }
    }

    function openCreate() {
      Object.assign(editForm, emptyForm());
      editOpen.value = true;
    }

    function openEdit(row) {
      Object.assign(editForm, {
        id: row.id,
        name: row.name,
        description: row.description || '',
        agentType: row.agentType || 'CODEX',
        workingDir: row.workingDir || '',
        enabled: row.enabled !== false,
        steps: (row.steps || []).map(s => ({
          name: s.name,
          promptTemplate: s.promptTemplate,
          timeoutSeconds: s.timeoutSeconds || 0
        }))
      });
      if (editForm.steps.length === 0) {
        editForm.steps.push(emptyStep());
      }
      editOpen.value = true;
    }

    function addStep() {
      editForm.steps.push(emptyStep());
    }

    function removeStep(index) {
      editForm.steps.splice(index, 1);
    }

    async function saveWorkflow() {
      saving.value = true;
      try {
        const payload = {
          name: editForm.name,
          description: editForm.description,
          agentType: editForm.agentType,
          workingDir: editForm.workingDir,
          enabled: editForm.enabled,
          steps: editForm.steps
        };
        const url = editForm.id ? '/api/admin-workflows/' + encodeURIComponent(editForm.id) : '/api/admin-workflows';
        const res = await fetch(url, {
          method: editForm.id ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        if (!res.ok) {
          throw new Error(await res.text());
        }
        const saved = await res.json();
        ElementPlus.ElMessage.success('已保存');
        editOpen.value = false;
        selectedWorkflow.value = saved;
        await loadWorkflows();
        await loadExecutions(saved.id);
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + (e.message || e));
      } finally {
        saving.value = false;
      }
    }

    function openRun(row) {
      runTarget.value = row;
      runInputs.value = '{\n  "branch": "main"\n}';
      runOpen.value = true;
    }

    async function submitRun() {
      running.value = true;
      try {
        const parsed = JSON.parse(runInputs.value || '{}');
        const res = await fetch('/api/admin-workflows/' + encodeURIComponent(runTarget.value.id) + '/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ inputs: parsed })
        });
        if (!res.ok) {
          throw new Error(await res.text());
        }
        const data = await res.json();
        ElementPlus.ElMessage.success('已触发执行');
        runOpen.value = false;
        selectedWorkflow.value = runTarget.value;
        await loadExecutions(runTarget.value.id);
        if (data.executionId) {
          await openExecution({ id: data.executionId });
        }
      } catch (e) {
        ElementPlus.ElMessage.error('运行失败: ' + (e.message || e));
      } finally {
        running.value = false;
      }
    }

    async function openExecution(row) {
      try {
        detail.value = await fetch('/api/admin-workflow-executions/' + encodeURIComponent(row.id)).then(r => r.json());
        detailOpen.value = true;
      } catch (e) {
        ElementPlus.ElMessage.error('加载执行详情失败: ' + e);
      }
    }

    async function deleteWorkflow(row) {
      try {
        await ElementPlus.ElMessageBox.confirm('删除后执行历史仍保留。', '删除工作流', { type: 'warning' });
        const res = await fetch('/api/admin-workflows/' + encodeURIComponent(row.id), { method: 'DELETE' });
        if (!res.ok) {
          throw new Error(await res.text());
        }
        if (selectedWorkflow.value && selectedWorkflow.value.id === row.id) {
          selectedWorkflow.value = null;
          executions.value = [];
        }
        ElementPlus.ElMessage.success('已删除');
        await loadWorkflows();
      } catch (e) {
        if (e !== 'cancel') {
          ElementPlus.ElMessage.error('删除失败: ' + (e.message || e));
        }
      }
    }

    function fmtTime(iso) {
      if (!iso) {
        return '-';
      }
      return String(iso).replace('T', ' ').replace(/\..*$/, '').replace('Z', '').slice(0, 19);
    }

    function statusType(status) {
      return { RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' }[status] || 'info';
    }

    return {
      workflows,
      executions,
      loading,
      executionLoading,
      editOpen,
      saving,
      runOpen,
      running,
      runTarget,
      runInputs,
      detailOpen,
      detail,
      isMobile,
      editForm,
      loadAll,
      openCreate,
      openEdit,
      addStep,
      removeStep,
      saveWorkflow,
      openRun,
      submitRun,
      openExecution,
      deleteWorkflow,
      selectWorkflow,
      fmtTime,
      statusType
    };
  }
});
