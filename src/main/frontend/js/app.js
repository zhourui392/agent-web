import { createApp } from 'vue';
import { setupElementPlus } from './element-plus-setup.js';
import 'element-plus/dist/index.css';
import { installAuthInterceptor } from './lib/auth-interceptor.js';
import ChatPanel from './components/chat-panel.vue';
import App from './App.vue';

// 全局 401 拦截(模块级副作用,从 lib/auth-interceptor.ts 引入,原内联 IIFE 抽出)
installAuthInterceptor();

const app = createApp(App);
setupElementPlus(app);
// 注册可复用的 ChatPanel 组件 (ES import from components/chat-panel.js)
app.component('chat-panel', ChatPanel);
app.mount('#app');
