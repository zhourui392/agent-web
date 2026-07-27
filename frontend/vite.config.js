import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve, dirname } from 'node:path';
import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// 多入口 MPA: 收集 frontend/ 及 frontend/admin/ 下所有 HTML 作为入口
// vite.config.js 在 frontend/ 下，root 即当前目录
const root = dirname(fileURLToPath(import.meta.url));

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
  // 应用固定挂在域名根路径, asset URL 用根绝对路径。
  // (曾为支持 /qa 子路径部署用相对 './', 挂载前缀机制废弃后不再需要 --
  //  根绝对路径下 /admin/x.html 与 /index.html 引用同一份 /assets/ 产物, 不受页面深度影响。)
  base: '/',
  // publicDir 现在只剩 css/ (由 HTML <link> 直接引用, 带 ?v= 手工版本号, 不进 bundle)。
  // js/ 已移到 root 下 (frontend/js), 由 Vite 打包 -- 放在 publicDir 里的文件是原样复制的,
  // bare import ('vue' / 'marked') 不会被解析, 浏览器直接报错。
  publicDir: resolve(root, 'public'),
  plugins: [vue()],
  // Vue: 所有页面已迁至 SFC (.vue), 模板由 @vitejs/plugin-vue 预编译,
  // 不再需要运行时模板编译器 -> 使用 runtime-only 构建, 配合 CSP 去掉 unsafe-eval。
  resolve: {
    alias: {
      vue: 'vue/dist/vue.runtime.esm-bundler.js',
    },
  },
  build: {
    outDir: resolve(root, 'dist'),
    emptyOutDir: true,
    rollupOptions: {
      input: collectHtmlEntries(),
    },
  },
  // E2E 时 vite preview 提供前端静态文件，/api 代理到后端 Spring Boot。
  // 生产环境由 Caddy file_server + reverse_proxy /api/* 实现同域分流。
  preview: {
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:18092',
        changeOrigin: true,
      },
    },
  },
});
