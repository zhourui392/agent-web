/**
 * 管理后台外壳组件 AdminShell + 启动工具 bootstrapAdminApp (ES module)。
 *
 * AdminShell 承载所有横切 chrome:ADMIN 角色门、顶栏(标题 / 退出 + #header-actions 插槽)、
 * 侧栏菜单(含 backfill badge),内容区用默认 <slot>。鉴权通过后 emit('ready') 通知宿主拉数。
 * 单页(admin.html)与未来 MPA 各页共享同一份实现。
 *
 * 组件用字符串模板(Vue 运行时编译器,经 vite alias vue -> esm-bundler),
 * 不受 in-DOM 自闭合限制。Vue / ElementPlus / 图标 / withBase 全部经 ES import (npm),
 * 无任何 window 全局依赖;element-plus 样式也在此 import, 由 Vite 抽成共享 CSS chunk。
 *
 * @author zhourui(V33215020)
 */
import { ref, onMounted, createApp } from 'vue';
import { setupElementPlus } from '../element-plus-setup.js';
import 'element-plus/dist/index.css';
import { withBase } from '../base.js';

export const AdminShell = {
  name: 'AdminShell',
  props: {
    // 当前菜单 key,侧栏高亮(各页静态传入,如 active="dashboard")
    active: { type: String, default: 'dashboard' }
  },
  emits: ['ready', 'logout'],
  template: `
    <div>
      <div v-if="!authed" class="login-wrap">
        <el-card class="login-card" v-loading="checking">
          <template #header><div style="font-weight:700;">管理后台 · 管理员登录</div></template>
          <div style="color:#606266; font-size:14px;">请使用 ADMIN 账户登录后访问。</div>
          <div v-if="loginError" style="color:#f56c6c; font-size:12px; margin-top:8px;">{{ loginError }}</div>
          <el-button type="primary" size="large" style="width:100%; margin-top:16px;"
                     @click="login">前往登录</el-button>
        </el-card>
      </div>

      <template v-else>
        <div class="admin-header">
          <span class="title">Agent Q&A · 管理后台</span>
          <span class="spacer"></span>
          <slot name="header-actions"></slot>
          <el-button text type="danger" @click="logout">退出</el-button>
        </div>

        <el-container class="admin-layout">
          <el-aside width="200px" class="admin-aside">
            <el-menu :default-active="active" @select="onMenuSelect">
              <el-menu-item index="dashboard"><span>大盘</span></el-menu-item>
              <el-menu-item index="conversations"><span>对话记录</span></el-menu-item>
              <el-menu-item index="users"><span>用户管理</span></el-menu-item>
              <el-menu-item index="workflows"><span>工作流</span></el-menu-item>
              <el-menu-item v-if="harnessEnabled" index="harness"><span>Harness</span></el-menu-item>
              <el-menu-item index="recall"><span>召回观测</span></el-menu-item>
              <el-menu-item v-if="ragEnabled" index="refinery"><span>召回历史</span></el-menu-item>
              <el-menu-item index="chat"><span>对话</span></el-menu-item>
              <el-menu-item index="settings"><span>系统设置</span></el-menu-item>
            </el-menu>
          </el-aside>

          <el-main class="admin-main">
            <slot></slot>
          </el-main>
        </el-container>
      </template>
    </div>
  `,
  setup(props, { emit }) {
    const authed = ref(false);
    const checking = ref(true);
    const loginError = ref('');
    // chat-rag(Knowledge Refinery)是否启用:enabled=false 时 controller 不装配,
    // /chunks 返回 404 → 隐藏「召回历史」菜单。口径对齐主控制台 app.js 的探测。
    const ragEnabled = ref(false);
    const harnessEnabled = ref(false);

    // 点菜单 = 整页跳到对应页(MPA);当前页不跳。各页是 /admin/<key>.html 真实静态文件。
    function onMenuSelect(index) {
      if (index === props.active) {
        return;
      }
      location.href = withBase('/admin/' + index + '.html');
    }

    // 探测 chat-rag 是否启用,决定是否展示「召回历史」菜单。失败/未装配按关闭处理。
    async function probeRefinery() {
      try {
        const res = await fetch('/api/refinery/chunks?page=1&size=1');
        ragEnabled.value = res.ok;
      } catch (e) {
        // 静默:探测不到按未启用处理,不影响其余菜单
      }
    }

    async function probeHarness() {
      try {
        const res = await fetch('/api/harness/runs/__admin_probe__');
        if (res.status !== 404) {
          harnessEnabled.value = true;
          return;
        }
        const body = await res.json();
        harnessEnabled.value = body && body.code === 'HARNESS_RUN_NOT_FOUND';
      } catch (e) {
        // 静默：未装配或探测失败时隐藏入口，不影响其他管理功能
      }
    }

    async function checkStatus() {
      checking.value = true;
      try {
        const status = await (await fetch('/api/auth/status')).json();
        authed.value = !!status.authenticated && status.role === 'ADMIN';
        if (authed.value) {
          emit('ready');
          probeRefinery();
          probeHarness();
        } else if (status.authenticated) {
          loginError.value = '当前账户无管理员权限';
        }
      } catch (e) {
        // 状态接口异常按未登录处理,展示登录框
      } finally {
        checking.value = false;
      }
    }

    function login() {
      const redirect = location.pathname + location.search + location.hash;
      location.href = withBase('/login.html?redirect=' + encodeURIComponent(redirect));
    }

    async function logout() {
      try {
        await fetch('/api/auth/logout', { method: 'POST' });
      } catch (e) {
        // 忽略:本地状态照常清空
      }
      authed.value = false;
      location.href = withBase('/login.html');
      emit('logout');
    }

    onMounted(checkStatus);

    return { authed, checking, loginError, ragEnabled, harnessEnabled, onMenuSelect, login, logout };
  }
};

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
