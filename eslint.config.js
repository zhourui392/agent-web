import pluginVue from 'eslint-plugin-vue';
import prettier from 'eslint-config-prettier';

// 注: typescript-eslint 尚不支持 TS 7.0(见 typescript-eslint#10940),
// TS 类型检查由 tsc --noEmit(typecheck)覆盖,ESLint 仅做 Vue 模板规则 + Prettier。
// 待 typescript-eslint 支持 TS 7.0 后加回 @vue/eslint-config-typescript。
export default [
  {
    ignores: [
      'dist/**',
      'src/main/resources/**',
      'tests/**',
      'node_modules/**',
      'target/**',
      'data/**',
      // SFC 子组件用 <script setup lang="ts">,typescript-eslint 尚不支持 TS 7.0
      // (tsc --noEmit 已覆盖类型检查,待 typescript-eslint 支持 TS 7.0 后移除)
      'src/main/frontend/js/components/MessageItem.vue',
      'src/main/frontend/js/components/PendingImageList.vue',
      'src/main/frontend/js/components/RecallCard.vue',
      'src/main/frontend/js/components/ToolBlock.vue',
      'src/main/frontend/js/components/CommandPopup.vue',
      'src/main/frontend/js/admin/pages/Chat.vue',
      'src/main/frontend/js/admin/pages/Dashboard.vue',
      'src/main/frontend/js/admin/pages/Users.vue',
      'src/main/frontend/js/admin/pages/Settings.vue',
      'src/main/frontend/js/admin/pages/Conversations.vue',
      'src/main/frontend/js/admin/pages/Refinery.vue',
      'src/main/frontend/js/admin/pages/Workflows.vue',
      'src/main/frontend/js/admin/pages/Recall.vue',
      'src/main/frontend/js/admin/composables/useHarness.ts',
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
