/**
 * 统一 Chat/Workbench 入口后，Workbench 写路径全局只读开关。
 * 历史实例保留只读查看；应急恢复可改回 false（需同时开后端 agent.workbench.write-enabled）。
 *
 * @author alex
 * @since 2026-08-20
 */
export const WORKBENCH_READ_ONLY = true;
