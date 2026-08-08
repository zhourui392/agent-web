<template>
  <div>
    <div v-if="!authed" class="login-wrap">
      <el-card v-loading="checking" class="login-card">
        <template #header><div style="font-weight:700;">管理后台 · 管理员登录</div></template>
        <div style="color:#606266; font-size:14px;">请使用 ADMIN 账户登录后访问。</div>
        <div v-if="loginError" style="color:#f56c6c; font-size:12px; margin-top:8px;">{{ loginError }}</div>
        <el-button
type="primary" size="large" style="width:100%; margin-top:16px;"
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
            <el-menu-item index="workbenches"><span>Workbench 运维</span></el-menu-item>
            <el-menu-item index="capabilities"><span>阶段能力配置</span></el-menu-item>
            <el-menu-item index="tool-invocation-analytics"><span>工具分析</span></el-menu-item>
            <el-menu-item index="users"><span>用户管理</span></el-menu-item>
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
</template>

<script>
/**
 * 管理后台外壳组件 AdminShell (ES module)。
 *
 * AdminShell 承载所有横切 chrome:ADMIN 角色门、顶栏(标题 / 退出 + #header-actions 插槽)、
 * 侧栏菜单与内容区用默认 <slot>。鉴权通过后 emit('ready') 通知宿主拉数。
 * 单页(admin.html)与未来 MPA 各页共享同一份实现。
 *
 * Vue / ElementPlus / 图标全部经 ES import (npm),
 * 无任何 window 全局依赖;element-plus 样式在 shell.js bootstrap 时 import, 由 Vite 抽成共享 CSS chunk。
 *
 * @author zhourui(V33215020)
 */
import { ref, onMounted } from 'vue';

export default {
  name: 'AdminShell',
  props: {
    // 当前菜单 key,侧栏高亮(各页静态传入,如 active="dashboard")
    active: { type: String, default: 'dashboard' }
  },
  emits: ['ready', 'logout'],
  setup(props, { emit }) {
    const authed = ref(false);
    const checking = ref(true);
    const loginError = ref('');
    // chat-rag(Knowledge Refinery)是否启用:enabled=false 时 controller 不装配,
    // /chunks 返回 404 -> 隐藏「召回历史」菜单。口径对齐主控制台 app.js 的探测。
    const ragEnabled = ref(false);

    // 点菜单 = 整页跳到对应页(MPA);当前页不跳。各页是 /admin/<key>.html 真实静态文件。
    function onMenuSelect(index) {
      if (index === props.active) {
        return;
      }
      location.href = '/admin/' + index + '.html';
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

    async function checkStatus() {
      checking.value = true;
      try {
        const status = await (await fetch('/api/auth/status')).json();
        authed.value = !!status.authenticated && status.role === 'ADMIN';
        if (authed.value) {
          emit('ready');
          probeRefinery();
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
      location.href = '/login.html?redirect=' + encodeURIComponent(redirect);
    }

    async function logout() {
      try {
        await fetch('/api/auth/logout', { method: 'POST' });
      } catch (e) {
        // 忽略:本地状态照常清空
      }
      authed.value = false;
      location.href = '/login.html';
      emit('logout');
    }

    onMounted(checkStatus);

    return { authed, checking, loginError, ragEnabled, onMenuSelect, login, logout };
  }
};
</script>
