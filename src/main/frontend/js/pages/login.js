/**
 * 登录页 entry。原先是 login.html 里的 inline module 脚本,依赖 Vue / ElementPlus /
 * window.AppBase 全局;npm 化后全局不再存在,故抽成独立模块,依赖一律 ES import。
 *
 * @author zhourui(V33215020)
 */
import { createApp, ref, onMounted } from 'vue';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import { withBase, sanitizeRedirect, APP_BASE } from '../base.js';

const app = createApp({
  setup() {
    const username = ref('');
    const password = ref('');
    const submitting = ref(false);
    const errorMessage = ref('');

    // 取登录后跳回地址。URL 上 ?redirect=... 优先;无则默认首页。
    // sanitizeRedirect 兜底: /api/ 路径(如脏链接里的 /api/auth/logout, GET 过去 405
    // ErrorPage)与站外地址一律回退首页。
    function getRedirect() {
      const params = new URLSearchParams(window.location.search);
      return sanitizeRedirect(params.get('redirect'), APP_BASE);
    }

    // 只记住用户名，密码永不写入浏览器存储。
    const LOGIN_MEMORY_KEY = 'agentweb.loginUsername';
    function saveLoginMemory() {
      try {
        localStorage.setItem(LOGIN_MEMORY_KEY, username.value.trim());
      } catch (e) { /* 隐私模式 / 禁用 localStorage 时静默 */ }
    }
    function restoreLoginMemory() {
      try {
        username.value = localStorage.getItem(LOGIN_MEMORY_KEY) || '';
      } catch (e) { /* 读取失败按空表单处理 */ }
    }

    async function onLogin() {
      if (!username.value.trim() || !password.value) {
        errorMessage.value = '用户名与密码都不能为空';
        return;
      }
      submitting.value = true;
      errorMessage.value = '';
      try {
        const resp = await fetch('/api/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: username.value.trim(),
            password: password.value
          })
        });
        if (!resp.ok) {
          errorMessage.value = resp.status === 401 ? '用户名或密码错误' : '登录失败，请稍后重试';
          return;
        }
        saveLoginMemory();
        password.value = '';
        window.location.href = withBase(getRedirect());
      } catch (e) {
        errorMessage.value = '网络错误: ' + e.message;
      } finally {
        submitting.value = false;
      }
    }

    onMounted(() => {
      restoreLoginMemory();
    });

    return { username, password, submitting, errorMessage, onLogin };
  }
});
setupElementPlus(app);
app.mount('#app');
