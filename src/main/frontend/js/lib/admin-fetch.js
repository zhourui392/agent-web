/**
 * Admin fetch 工具 lib (ES module): 浏览器挂 window.AgentAdminFetch, Node/Vitest 走 ES import。
 *
 * 抽出原因: recall.js / settings.js 各有一份 fetchJson,签名不同(一个只收 url,一个收 url+options)、
 * 错误处理分叉(text vs json)。统一签名 + 健壮错误处理。
 *
 * - fetchJson(url, options): 统一签名,options 默认 undefined。错误响应先尝试 JSON 提取 message/error,
 *   回退 HTTP 状态码。
 * - withLoading(loadingRef, fn): 包装 loading true/false + try/finally,消除每页重复样板。
 *
 * 依赖: 全局 fetch。
 */
export async function fetchJson(url, options) {
  var response = await fetch(url, options);
  if (!response.ok) {
    var error = await response.json().catch(function () { return {}; });
    throw new Error(error.message || error.error || ('HTTP ' + response.status));
  }
  return response.json();
}

export async function withLoading(loadingRef, fn) {
  if (loadingRef && 'value' in loadingRef) loadingRef.value = true;
  try { return await fn(); }
  finally { if (loadingRef && 'value' in loadingRef) loadingRef.value = false; }
}
