/**
 * 管理后台系统设置页纯转换函数 (ES module)。
 *
 * 浏览器挂 window.AdminSettingsUtils (兼容未改的消费者); Node/Vitest 走 ES import。
 *
 * @author zhourui(V33215020)
 */
export function pathsToText(paths) {
  return Array.isArray(paths) ? paths.join('\n') : '';
}

export function textToPaths(text) {
  if (!text) {
    return [];
  }
  return String(text).split(/\r?\n/)
    .map(function (path) { return path.trim(); })
    .filter(function (path) { return path.length > 0; });
}

// 浏览器: 挂全局 window.AdminSettingsUtils (兼容未改的消费者)
if (typeof window !== 'undefined') {
  window.AdminSettingsUtils = {
    pathsToText: pathsToText,
    textToPaths: textToPaths
  };
}