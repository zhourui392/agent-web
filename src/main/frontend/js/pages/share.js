/**
 * 分享页 entry。挂载 Share.vue 组件到 #app。
 *
 * @author zhourui(V33215020)
 */
import { createApp } from 'vue';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import Share from './Share.vue';

const app = createApp(Share);
setupElementPlus(app);
app.mount('#app');
