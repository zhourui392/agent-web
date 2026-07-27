/**
 * 管理后台启动工具 bootstrapAdminApp (ES module)。
 *
 * AdminShell 组件已抽出到 ./AdminShell.vue (SFC, 由 Vite 预编译)。
 * 此处仅保留启动样板:createApp + Element Plus + 图标 + admin-shell(+ 可选 chat-panel),mount('#app')。
 * 各页(及单页 admin.js)统一走此入口,免重复样板。
 *
 * chat-panel 由需要它的页面自己 import 并经 opts.chatPanel 注入 -- 不在本模块 import,
 * 否则 9 个 admin 页会全部打包进这个千行组件 (只有 chat 页真正用得到)。
 *
 * @author zhourui(V33215020)
 */
import { createApp } from 'vue';
import { setupElementPlus } from '../element-plus-setup.js';
import 'element-plus/dist/index.css';
import AdminShell from './AdminShell.vue';
import { installAuthInterceptor } from '../lib/auth-interceptor.js';

export { AdminShell };

/**
 * 启动一个管理后台 Vue app:createApp + Element Plus + 图标 + admin-shell(+ 可选 chat-panel),mount('#app')。
 * 各页(及单页 admin.js)统一走此入口,免重复样板。
 *
 * chat-panel 由需要它的页面自己 import 并经 opts.chatPanel 注入 -- 不在本模块 import,
 * 否则 9 个 admin 页会全部打包进这个千行组件 (只有 chat 页真正用得到)。
 *
 * @param rootOptions 根组件选项(含 setup)
 * @param opts { chatPanel: Component } 传入即注册为 <chat-panel>(对话视图用)
 */
export function bootstrapAdminApp(rootOptions, opts) {
  // 会话过期时把 401 统一导向登录页。此前只有主站 app.js 装,管理台各页(尤其 Harness
  // 的 2s 轮询)会一直弹「未登录」错误却不跳登录。/api/auth/status 恒返 200,
  // AdminShell 的未登录内联卡片与非 ADMIN 的 403 提示都不受影响。
  installAuthInterceptor();
  const options = opts || {};
  const app = createApp(rootOptions);
  setupElementPlus(app);
  app.component('AdminShell', AdminShell);
  if (options.chatPanel) {
    app.component('ChatPanel', options.chatPanel);
  }
  app.mount('#app');
  return app;
}
