import pluginVue from 'eslint-plugin-vue';
import prettier from 'eslint-config-prettier';

// 注: typescript-eslint 尚不支持 TS 7.0(见 typescript-eslint#10940),
// TS 类型检查由 tsc --noEmit(typecheck)覆盖,ESLint 仅做 Vue 模板规则 + Prettier。
// 待 typescript-eslint 支持 TS 7.0 后加回 @vue/eslint-config-typescript。
export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      // SFC 子组件用 <script setup lang="ts">,typescript-eslint 尚不支持 TS 7.0
      // (tsc --noEmit 已覆盖类型检查,待 typescript-eslint 支持 TS 7.0 后移除)
      'js/components/MessageItem.vue',
      'js/components/PendingImageList.vue',
      'js/components/RecallCard.vue',
      'js/components/ToolBlock.vue',
      'js/components/CommandPopup.vue',
      'js/admin/pages/Chat.vue',
      'js/admin/pages/Dashboard.vue',
      'js/admin/pages/Users.vue',
      'js/admin/pages/Settings.vue',
      'js/admin/pages/Conversations.vue',
      'js/admin/pages/ToolInvocationAnalytics.vue',
      'js/admin/pages/Refinery.vue',
      'js/admin/pages/Workflows.vue',
      'js/admin/pages/Recall.vue',
      'js/admin/pages/Workbenches.vue',
      'js/admin/pages/Capabilities.vue',
      'js/admin/components/CapabilitySourceSettings.vue',
      'js/admin/components/StageCatalogSettings.vue',
      'js/admin/components/ToolInvocationDetailDrawer.vue',
      'js/components/conversation/ConversationAttachmentList.vue',
      'js/components/conversation/ConversationComposer.vue',
      'js/components/conversation/ConversationMessage.vue',
      'js/components/conversation/ConversationTimeline.vue',
    ],
  },
  ...pluginVue.configs['flat/recommended'],
  prettier,
  {
    rules: {
      // 项目既有代码风格,渐进收紧不阻塞
      'vue/multi-word-component-names': 'off',
      'vue/no-v-html': 'off',
      'vue/no-mutating-props': 'off',
      'vue/require-default-prop': 'off',
      'vue/attribute-hyphenation': 'off',
      'vue/v-on-event-hyphenation': 'off',
    },
  },
];
