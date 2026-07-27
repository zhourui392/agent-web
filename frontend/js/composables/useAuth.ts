/**
 * useAuth composable: app.js 主页 auth 切片(FE-R3.2)。
 *
 * 从 app.js setup 抽出: auth 状态(username/currentUserId/authEnabled/canUseScheduledTask)
 * + initAuth(/api/auth/status 检查 + 未登录跳转) + doLogout。
 *
 * 401 全局拦截不在此(它是模块级副作用,见 lib/auth-interceptor.ts)。
 * 行为照搬 app.js 原内联实现,零逻辑变更。
 */
import { ref, computed, type Ref, type ComputedRef } from 'vue';

interface AuthStatus {
  authEnabled?: boolean;
  authenticated?: boolean;
  loginUrl?: string;
  username?: string;
  userId?: string;
  role?: string;
}

export function useAuth(): {
  authEnabled: Ref<boolean>;
  username: Ref<string>;
  currentUserId: Ref<string>;
  canUseScheduledTask: ComputedRef<boolean>;
  isAdmin: ComputedRef<boolean>;
  initAuth: () => Promise<boolean>;
  doLogout: () => Promise<void>;
} {
  const authEnabled = ref(true);
  const username = ref('admin');
  const currentUserId = ref('');
  const role = ref('');
  const canUseScheduledTask = computed(() => Boolean(currentUserId.value));
  const isAdmin = computed(() => role.value === 'ADMIN');

  async function initAuth(): Promise<boolean> {
    try {
      const authStatus: AuthStatus = await fetch('/api/auth/status').then((r) => r.json());
      authEnabled.value = !!authStatus.authEnabled;
      if (!authStatus.authenticated && authStatus.loginUrl) {
        window.location.href = authStatus.loginUrl;
        return false;
      }
      if (authStatus.username) {
        username.value = authStatus.username;
      }
      if (authStatus.userId) {
        currentUserId.value = authStatus.userId;
      }
      if (authStatus.role) {
        role.value = authStatus.role;
      }
      return true;
    } catch (e) {
      // 忽略,保留默认值,继续后续 init
      return true;
    }
  }

  async function doLogout(): Promise<void> {
    // 登出后跳本站 /login.html(loginUrl 由后端返回,已带 ?redirect);拿不到则退回首页重新鉴权。
    let loginUrl = '/';
    try {
      const r: any = await fetch('/api/auth/logout', { method: 'POST' }).then((x) => x.json());
      if (r && r.loginUrl) {
        loginUrl = r.loginUrl;
      }
    } catch (e) {
      // ignore
    }
    window.location.href = loginUrl;
  }

  return { authEnabled, username, currentUserId, canUseScheduledTask, isAdmin, initAuth, doLogout };
}