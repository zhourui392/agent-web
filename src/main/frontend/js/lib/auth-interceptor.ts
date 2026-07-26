/**
 * 全局 401 拦截器: 任何 API 响应 401 且 body 带 loginUrl 时,自动跳本站 /login.html。
 * loginUrl 由后端 SessionAuthFilter / AuthController 提供,已带 ?redirect=<原路径>。
 * 覆盖所有经 window.fetch 的调用;SSE(EventSource) 不走 fetch,单独在错误处理里兜底。
 *
 * 从 app.js 顶部 IIFE 抽出(FE-R3.2),独立模块可单测。原 IIFE 行为照搬,零逻辑变更。
 */

export function installAuthInterceptor(): void {
  const rawFetch = window.fetch.bind(window);
  let redirecting = false;
  window.fetch = async function (...args: Parameters<typeof fetch>): Promise<Response> {
    const res = await rawFetch(...args);
    if (res.status === 401 && !redirecting) {
      try {
        const data: any = await res.clone().json();
        if (data && data.loginUrl) {
          redirecting = true;
          window.location.href = data.loginUrl;
        } else {
          // 401 但响应没带 loginUrl(老接口/非 JSON 兜底): 直接跳本站登录页, 带回当前路径。
          redirecting = true;
          const redirect = encodeURIComponent(window.location.pathname + window.location.search);
          window.location.href = '/login.html?redirect=' + redirect;
        }
      } catch (e) {
        redirecting = true;
        const redirect = encodeURIComponent(window.location.pathname + window.location.search);
        window.location.href = '/login.html?redirect=' + redirect;
      }
    }
    return res;
  };
}