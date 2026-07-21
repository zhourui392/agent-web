/**
 * 管理后台「系统设置」页。运行时切换对话默认模型(免重启热生效)。
 * 对话模型变更经版本号驱动「强制全员跟随」,主对话页下次加载即应用。
 *
 * @author zhourui(V33215020)
 */
const { ref, reactive } = Vue;

bootstrapAdminApp({
  setup() {
    const options = ref(['CLAUDE', 'CODEX']);
    const form = reactive({ chatDefaultAgent: '' });
    const loading = ref(false);
    const saving = ref(false);

    async function loadSettings() {
      loading.value = true;
      try {
        const data = await fetch('/api/admin-settings/agent-models').then(r => r.json());
        if (Array.isArray(data.options) && data.options.length > 0) {
          options.value = data.options;
        }
        form.chatDefaultAgent = data.chatDefaultAgent || '';
      } catch (e) {
        ElementPlus.ElMessage.error('加载设置失败: ' + e);
      } finally {
        loading.value = false;
      }
    }

    async function save() {
      saving.value = true;
      try {
        const resp = await fetch('/api/admin-settings/agent-models', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            chatDefaultAgent: form.chatDefaultAgent
          })
        });
        if (!resp.ok) {
          const err = await resp.json().catch(() => ({}));
          throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const data = await resp.json();
        form.chatDefaultAgent = data.chatDefaultAgent || form.chatDefaultAgent;
        ElementPlus.ElMessage.success('已保存');
      } catch (e) {
        ElementPlus.ElMessage.error('保存失败: ' + e.message);
      } finally {
        saving.value = false;
      }
    }

    return { options, form, loading, saving, loadSettings, save };
  }
});
