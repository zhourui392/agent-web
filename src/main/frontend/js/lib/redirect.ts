/**
 * 登录回跳参数的清洗 (open-redirect 防御)。
 *
 * 原属 base.js。挂载前缀机制(/qa)废弃后 base.js 整个删除,但本函数与前缀无关,
 * 是独立的安全校验,故单独成模块。
 *
 * @author zhourui(V33215020)
 */

/**
 * 清洗登录页 ?redirect= 参数,返回登录成功后可安全跳转的站内页面路径。
 *
 * redirect 语义是「登录前所在页面」,以下情况回退首页 '/':
 *   - 空 / 非 root-absolute: 不是站内路径;
 *   - 协议相对 //host: open-redirect,浏览器会当成跨站绝对 URL;
 *   - /api/ 路径: 浏览器 GET 过去只会拿到 405 ErrorPage 或裸 JSON
 *     (如历史脏链接里的 /api/auth/logout)。
 *
 * @param {string|null} redirect URL 上取到的 redirect 原值
 * @returns {string} 可直接跳转的站内路径
 */
export function sanitizeRedirect(redirect: string | null | undefined): string {
  if (typeof redirect !== 'string' || redirect.charAt(0) !== '/' || redirect.charAt(1) === '/') {
    return '/';
  }
  if (redirect.indexOf('/api/') === 0) {
    return '/';
  }
  return redirect;
}
