import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'node:path';
import { readdirSync } from 'node:fs';

// 多入口 MPA: 收集 frontend/ 及 frontend/admin/ 下所有 HTML 作为入口
const root = resolve('src/main/frontend');

function collectHtmlEntries() {
  const entries = {};
  for (const f of readdirSync(root).filter(f => f.endsWith('.html'))) {
    entries[f.replace(/\.html$/, '')] = resolve(root, f);
  }
  const adminDir = resolve(root, 'admin');
  for (const f of readdirSync(adminDir).filter(f => f.endsWith('.html'))) {
    entries['admin-' + f.replace(/\.html$/, '')] = resolve(adminDir, f);
  }
  return entries;
}

export default defineConfig({
  root,
  // 相对路径 base: 支持 /qa 等子路径部署, asset URL 用相对路径
  base: './',
  // publicDir: vendor/js/css 原样复制到产物根 (ES module 改造前过渡)
  publicDir: resolve(root, 'public'),
  plugins: [vue()],
  build: {
    outDir: resolve('src/main/resources/static'),
    emptyOutDir: true,
    rollupOptions: {
      input: collectHtmlEntries(),
    },
  },
});
