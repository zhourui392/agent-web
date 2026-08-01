/**
 * Workbench MPA 入口。
 *
 * @author alex
 * @since 2026-08-01
 */
import { createApp } from 'vue';
import 'element-plus/dist/index.css';
import { setupElementPlus } from '../element-plus-setup.js';
import { installAuthInterceptor } from '../lib/auth-interceptor.js';
import Workbench from './Workbench.vue';

installAuthInterceptor();

const app = createApp(Workbench);
setupElementPlus(app);
app.mount('#app');
