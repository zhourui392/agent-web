/**
 * 前端上下文前缀的单一真相源 (UMD-lite)。
 *
 * 「共享域名 + /qa 路径前缀」部署下,页面在 https://host/qa/ 加载;本模块从自身
 * <script src> 解析出 /qa 前缀,并:
 *   1. 暴露 window.withBase(path):给 root-absolute 路径(/api、/login.html 等)幂等补前缀;
 *   2. 包 window.fetch / window.EventSource:所有 string URL 自动补前缀,业务代码无需改;
 *   3. 必须最先加载(早于 app.js 等),才能在任何请求前完成包裹。
 *
 * 切到独立域名时:页面在根 / 加载 → 推导出空前缀 → 全部透传,本文件与业务代码零改动。
 *
 * 纯函数 deriveBase / makeWithBase 经 module.exports 暴露给 Vitest。
 *
 * @author zhourui(V33215020)
 */
(function (root) {
  var SELF_MARKER = '/js/base.js';

  /**
   * 从本模块自身脚本 URL 推导上下文前缀。
   * @param {string} scriptSrc 形如 https://host/qa/js/base.js 或 /js/base.js?v=1
   * @returns {string} "/qa" 或 "" (根域 / 解析失败)
   */
  function deriveBase(scriptSrc) {
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
    if (i < 0) return '';
    return path.substring(0, i);
  }

  /**
   * 造一个幂等的前缀器:只对 root-absolute 路径补前缀,已带前缀 / 相对路径 / 完整 URL / 协议相对均原样返回。
   * @param {string} base "/qa" 或 ""
   */
  function makeWithBase(base) {
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
  function sanitizeRedirect(redirect, base) {
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

  var api = { deriveBase: deriveBase, makeWithBase: makeWithBase, sanitizeRedirect: sanitizeRedirect };

  // Node/Vitest: CommonJS 导出
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }

  // 浏览器引导:无 DOM(Node 测试)直接跳过
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return;
  }

  var self = document.currentScript;
  var base = deriveBase(self ? self.src : '');
  var withBase = makeWithBase(base);

  window.__APP_BASE__ = base;
  window.withBase = withBase;
  window.AppBase = api;

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
})(typeof window !== 'undefined' ? window : globalThis);
