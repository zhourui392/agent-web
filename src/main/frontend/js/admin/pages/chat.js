/**
 * 管理后台「对话」页 entry。挂载 Chat.vue 组件到 #app。
 *
 * @author zhourui(V33215020)
 */
import ChatPanel from '../../components/chat-panel.vue';
import { bootstrapAdminApp } from '../shell.js';
import Page from './Chat.vue';

bootstrapAdminApp(Page, { chatPanel: ChatPanel });
