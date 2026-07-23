/**
 * 管理后台「系统设置」页。运行时管理默认模型与工作空间目录授权。
 * 对话模型变更经版本号驱动「强制全员跟随」,主对话页下次加载即应用。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const options = ref(['CLAUDE', 'CODEX']);
    const form = reactive({
      chatDefaultAgent: '',
      defaultWorkspace: '',
      workspaceRootsText: '',
      uploadRootsText: ''
    });
    const loading = ref(false);
    const savingAgentModels = ref(false);
    const savingWorkspaces = ref(false);
    const resettingWorkspaces = ref(false);

    async function fetchJson(url, options) {
      const response = await fetch(url, options);
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.message || error.error || ('HTTP ' + response.status));
      }
      return response.json();
    }

    function applyWorkspaceSettings(data) {
      form.defaultWorkspace = data.defaultWorkspace || '';
      form.workspaceRootsText = AdminSettingsUtils.pathsToText(data.workspaceRoots);
      form.uploadRootsText = AdminSettingsUtils.pathsToText(data.uploadRoots);
    }

    async function loadSettings() {
      loading.value = true;
      try {
        const [agentModels, workspaces] = await Promise.all([
          fetchJson('/api/admin-settings/agent-models'),
          fetchJson('/api/admin-settings/workspaces')
        ]);
        if (Array.isArray(agentModels.options) && agentModels.options.length > 0) {
          options.value = agentModels.options;
        }
        form.chatDefaultAgent = agentModels.chatDefaultAgent || '';
        applyWorkspaceSettings(workspaces);
      } catch (e) {
        ElementPlus.ElMessage.error('加载设置失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    async function saveAgentModels() {
      savingAgentModels.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/agent-models', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            chatDefaultAgent: form.chatDefaultAgent
          })
        });
        form.chatDefaultAgent = data.chatDefaultAgent || form.chatDefaultAgent;
        ElementPlus.ElMessage.success('默认模型已保存');
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + e.message);
      } finally {
        savingAgentModels.value = false;
      }
    }

    async function saveWorkspaces() {
      savingWorkspaces.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/workspaces', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            defaultWorkspace: form.defaultWorkspace,
            workspaceRoots: AdminSettingsUtils.textToPaths(form.workspaceRootsText),
            uploadRoots: AdminSettingsUtils.textToPaths(form.uploadRootsText)
          })
        });
        applyWorkspaceSettings(data);
        ElementPlus.ElMessage.success('工作空间配置已保存并生效');
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + e.message);
      } finally {
        savingWorkspaces.value = false;
      }
    }

    async function resetWorkspaces() {
      try {
        await ElementPlus.ElMessageBox.confirm(
          '将删除数据库中的工作空间配置，并恢复为配置文件默认值。是否继续？',
          '恢复默认值',
          { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
        );
      } catch (e) {
        return;
      }
      resettingWorkspaces.value = true;
      try {
        const data = await fetchJson('/api/admin-settings/workspaces', { method: 'DELETE' });
        applyWorkspaceSettings(data);
        ElementPlus.ElMessage.success('已恢复配置文件默认值');
      } catch (e) {
        ElementPlus.ElMessage.error('恢复失败: ' + e.message);
      } finally {
        resettingWorkspaces.value = false;
      }
    }

    return {
      options,
      form,
      loading,
      savingAgentModels,
      savingWorkspaces,
      resettingWorkspaces,
      loadSettings,
      saveAgentModels,
      saveWorkspaces,
      resetWorkspaces
    };
  }
});
