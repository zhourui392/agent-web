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
  // publicDir 现在只剩 css/ (由 HTML <link> 直接引用, 带 ?v= 手工版本号, 不进 bundle)。
  // js/ 已移到 root 下 (frontend/js), 由 Vite 打包 -- 放在 publicDir 里的文件是原样复制的,
  // bare import ('vue' / 'marked') 不会被解析, 浏览器直接报错。
  publicDir: resolve(root, 'public'),
  plugins: [vue()],
  // Vue 运行时模板编译器: 组件用字符串模板 / in-DOM <div id="app"> 模板,
  // npm vue 默认 ESM 入口不含编译器, 必须显式指到 esm-bundler 才能在运行时编译模板。
  resolve: {
    alias: {
      vue: 'vue/dist/vue.esm-bundler.js',
    },
  },
  build: {
    outDir: resolve('src/main/resources/static'),
    emptyOutDir: true,
    rollupOptions: {
      input: collectHtmlEntries(),
    },
  },
});
