/**
 * Git 设置页 entry。挂载 GitSettings.vue 组件到 #app。
 *
 * @author zhourui(V33215020)
 */
import { createApp } from 'vue';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import GitSettings from './GitSettings.vue';

const app = createApp(GitSettings);
setupElementPlus(app);
app.mount('#app');
