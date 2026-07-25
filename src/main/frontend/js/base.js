/**
 * 前端上下文前缀的单一真相源 (ES module)。
 *
 * 「共享域名 + /qa 路径前缀」部署下,页面在 https://host/qa/ 加载;本模块从自身
 * URL (import.meta.url) 解析出 /qa 前缀,并:
 *   1. export withBase(path):给 root-absolute 路径(/api、/login.html 等)幂等补前缀;
 *   2. 包 window.fetch / window.EventSource:所有 string URL 自动补前缀,业务代码无需改;
 *   3. 必须最先执行(早于任何请求)。打包后本模块是各 entry 的首个 import,
 *      ES module 依赖先于模块体求值, 故包裹一定在业务代码跑之前完成。
 *
 * 切到独立域名时:页面在根 / 加载 -> 推导出空前缀 -> 全部透传,本文件与业务代码零改动。
 *
 * 纯函数 deriveBase / makeWithBase / sanitizeRedirect 经 ES module export 暴露给 Vitest。
 *
 * @author zhourui(V33215020)
 */
var SELF_MARKER = '/js/base.js';
// Vite 打包后本模块变成 /assets/<name>-<hash>.js 的共享 chunk, 原文件名 marker 失效,
// 退而用 /assets/ 目录 marker 推导前缀 (build.assetsDir 默认 assets, 与 vite.config.js 一致)。
var BUNDLED_MARKER = '/assets/';

/**
 * 从本模块自身脚本 URL 推导上下文前缀。
 *
 * 两种形态都要认: 未打包 (…/qa/js/base.js) 与 Vite 打包产物 (…/qa/assets/base-a1b2c3.js)。
 * marker 用 lastIndexOf: 部署前缀本身含 /assets/ 时 (如挂在 /assets/app/) 仍取最后一段。
 *
 * @param {string} scriptSrc 形如 https://host/qa/js/base.js、/js/base.js?v=1 或 /qa/assets/base-a1b2c3.js
 * @returns {string} "/qa" 或 "" (根域 / 解析失败)
 */
export function deriveBase(scriptSrc) {
  if (!scriptSrc) return '';
  var path = scriptSrc;
  var scheme = scriptSrc.indexOf('://');
  if (scheme > -1) {
    var rest = scriptSrc.substring(scheme + 3);
    var slash = rest.indexOf('/');
    path = slash > -1 ? rest.substring(slash) : '/';
  }
  var q = path.indexOf('?');
  if (q > -1) path = path.substring(0, q);
  var i = path.indexOf(SELF_MARKER);
  if (i > -1) return path.substring(0, i);
  var b = path.lastIndexOf(BUNDLED_MARKER);
  if (b > -1) return path.substring(0, b);
  return '';
}

/**
 * 造一个幂等的前缀器:只对 root-absolute 路径补前缀,已带前缀 / 相对路径 / 完整 URL / 协议相对均原样返回。
 * @param {string} base "/qa" 或 ""
 */
export function makeWithBase(base) {
  return function withBase(path) {
    if (typeof path !== 'string' || base === '') return path;
    // 非 root-absolute(相对、http://、协议相对 //host)一律不动
    if (path.charAt(0) !== '/' || path.charAt(1) === '/') return path;
    // 已带前缀则幂等返回(boundary 用 base+'/' 防 /qa 误命中 /qabc)
    if (path === base || path.indexOf(base + '/') === 0) return path;
    return base + path;
  };
}

/**
 * 清洗登录页 ?redirect= 参数,返回登录成功后可安全跳转的站内页面路径。
 *
 * redirect 语义是「登录前所在页面」,以下情况回退首页 '/':
 *   - 空 / 非 root-absolute / 协议相对 //host (open-redirect 防御);
 *   - 剥掉挂载前缀后是 /api/ 路径 (浏览器 GET 过去只会 405 ErrorPage 或裸 JSON,
 *     如历史脏链接里的 /api/auth/logout)。
 * @param {string|null} redirect URL 上取到的 redirect 原值(可能带 /qa 前缀)
 * @param {string} base 挂载前缀 "/qa" 或 ""
 * @returns {string} 可直接交给 withBase 跳转的路径
 */
export function sanitizeRedirect(redirect, base) {
  if (typeof redirect !== 'string' || redirect.charAt(0) !== '/' || redirect.charAt(1) === '/') {
    return '/';
  }
  var logical = redirect;
  if (base && (redirect === base || redirect.indexOf(base + '/') === 0)) {
    logical = redirect.substring(base.length) || '/';
  }
  if (logical.indexOf('/api/') === 0) {
    return '/';
  }
  return redirect;
}

// 运行时推导 context prefix: 浏览器从 import.meta.url 推导; Node 测试环境无 DOM 时退化为空前缀 (identity)。
// 业务代码一律 import { withBase, APP_BASE }; window 上只留这两个作为部署期可观测契约
// (排障时在 console 里直接看当前前缀, e2e 也据此断言子路径部署是否推导正确)。
var __runtimeBase = (typeof window !== 'undefined' && typeof document !== 'undefined')
  ? deriveBase(import.meta.url)
  : '';
export var withBase = makeWithBase(__runtimeBase);
// 运行时前缀本体 ("/qa" 或 "")。sanitizeRedirect 等需要显式拿到 base 的消费者用它,
// 替代原先的 window.__APP_BASE__ 读法。
export var APP_BASE = __runtimeBase;

// 浏览器引导:无 DOM(Node 测试)直接跳过。ES module 下 document.currentScript 为 null,
// 改用 import.meta.url 取本模块自身 URL。
if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  window.__APP_BASE__ = __runtimeBase;
  window.withBase = withBase;

  // 包 fetch:string URL 自动补前缀(app.js 的 401 拦截器随后再包一层,组合生效)
  if (typeof window.fetch === 'function') {
    var rawFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      return rawFetch(typeof input === 'string' ? withBase(input) : input, init);
    };
  }

  // 包 EventSource:SSE 不走 fetch,同样补前缀;保留静态常量与原型,instanceof/readyState 行为不变
  if (typeof window.EventSource === 'function') {
    var RawEventSource = window.EventSource;
    var WrappedEventSource = function (url, config) {
      return new RawEventSource(typeof url === 'string' ? withBase(url) : url, config);
    };
    WrappedEventSource.prototype = RawEventSource.prototype;
    WrappedEventSource.CONNECTING = RawEventSource.CONNECTING;
    WrappedEventSource.OPEN = RawEventSource.OPEN;
    WrappedEventSource.CLOSED = RawEventSource.CLOSED;
    window.EventSource = WrappedEventSource;
  }
}
