/**
 * 管理后台系统设置页纯转换函数。
 *
 * @author zhourui(V33215020)
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  root.AdminSettingsUtils = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  function pathsToText(paths) {
    return Array.isArray(paths) ? paths.join('\n') : '';
  }

  function textToPaths(text) {
    if (!text) {
      return [];
    }
    return String(text).split(/\r?\n/)
      .map(function (path) { return path.trim(); })
      .filter(function (path) { return path.length > 0; });
  }

  return {
    pathsToText,
    textToPaths
  };
});
