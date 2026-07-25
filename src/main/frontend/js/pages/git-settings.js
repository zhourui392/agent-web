/**
 * Git 设置页 entry。原先是 git-settings.html 里的 inline module 脚本,依赖 Vue /
 * ElementPlus 全局;npm 化后全局不再存在,故抽成独立模块,依赖一律 ES import。
 *
 * @author zhourui(V33215020)
 */
import { createApp, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';

const app = createApp({
  setup() {
    const loading = ref(true);
    const saving = ref(false);
    const userId = ref('');
    const username = ref('');
    const credentialConfigured = ref(false);
    const form = reactive({ name: '', email: '', credUsername: '', credPassword: '', readOnly: false });

    async function loadStatus() {
      try {
        const res = await fetch('/api/auth/status');
        if (res.ok) {
          const s = await res.json();
          userId.value = s.userId || '';
          username.value = s.username || '';
        }
      } catch (e) { /* 状态拿不到不阻断配置读取 */ }
    }

    async function loadConfig() {
      const res = await fetch('/api/user/git-config');
      if (!res.ok) {
        throw new Error('加载配置失败 (' + res.status + ')');
      }
      const c = await res.json();
      form.name = c.name || '';
      form.email = c.email || '';
      form.readOnly = !!c.readOnly;
      credentialConfigured.value = !!c.credentialConfigured;
    }

    async function init() {
      loading.value = true;
      try {
        await loadStatus();
        await loadConfig();
      } catch (e) {
        ElMessage.error(e.message || '加载失败');
      } finally {
        loading.value = false;
      }
    }

    async function save() {
      if (form.readOnly) { return; }
      saving.value = true;
      try {
        const payload = {
          name: form.name,
          email: form.email,
          credUsername: form.credUsername || null
        };
        // 仅在用户输入了新密码时才提交凭证，留空表示保留既有
        if (form.credPassword && form.credPassword.length > 0) {
          payload.credPassword = form.credPassword;
        }
        const res = await fetch('/api/user/git-config', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        if (res.ok) {
          ElMessage.success('已保存');
          form.credPassword = '';
          await loadConfig();
        } else {
          let msg = '保存失败 (' + res.status + ')';
          try { const b = await res.json(); if (b && b.error) { msg = b.error; } } catch (e) { /* ignore */ }
          ElMessage.error(msg);
        }
      } catch (e) {
        ElMessage.error(e.message || '保存失败');
      } finally {
        saving.value = false;
      }
    }

    function goBack() { window.location.href = 'index.html'; }

    init();
    return { loading, saving, userId, username, credentialConfigured, form, save, goBack };
  }
});
setupElementPlus(app);
app.mount('#app');
