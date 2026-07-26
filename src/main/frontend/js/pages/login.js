/**
 * 登录页 entry。挂载 Login.vue 组件到 #app。
 *
 * @author zhourui(V33215020)
 */
import { createApp } from 'vue';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import Login from './Login.vue';

const app = createApp(Login);
setupElementPlus(app);
app.mount('#app');
