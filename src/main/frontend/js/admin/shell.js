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
  const options = opts || {};
  const app = createApp(rootOptions);
  setupElementPlus(app);
  app.component('admin-shell', AdminShell);
  if (options.chatPanel) {
    app.component('chat-panel', options.chatPanel);
  }
  app.mount('#app');
  return app;
}
