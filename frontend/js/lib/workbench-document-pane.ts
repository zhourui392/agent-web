/**
 * Workbench Document Pane 的纯几何计算。
 *
 * @author alex
 * @since 2026-08-01
 */
import {
  createWorkbenchDocumentStateStore,
  enterWorkbenchMobileDrawer,
  exitWorkbenchMobileDrawer,
  type StorageLike,
  type WorkbenchDocumentSessionState,
  type WorkbenchDocumentStateStore,
  type WorkbenchDocumentStorageIdentity,
} from './workbench-document-state';

export interface WorkbenchSplitBounds {
  left: number;
  width: number;
}

export interface RestoredWorkbenchDocumentPaneSession {
  store: WorkbenchDocumentStateStore;
  state: WorkbenchDocumentSessionState;
}

export function restoreWorkbenchDocumentPaneSession(
  storage: StorageLike,
  identity: WorkbenchDocumentStorageIdentity,
  mobile: boolean,
): RestoredWorkbenchDocumentPaneSession {
  const store = createWorkbenchDocumentStateStore(storage, identity);
  const loaded = store.load();
  return {
    store,
    state: {
      ...loaded,
      layout: mobile
        ? enterWorkbenchMobileDrawer(loaded.layout)
        : exitWorkbenchMobileDrawer(loaded.layout),
    },
  };
}

/**
 * 文档 Pane 位于 Split Pane 右侧，因此宽度从容器右边界反向计算。
 * 允许结果暂时越界，最终由 document layout 状态机统一执行 25%～70% 截断。
 */
export function workbenchDocumentWidthFromPointer(
  bounds: WorkbenchSplitBounds,
  clientX: number,
): number | null {
  if (!bounds
    || !Number.isFinite(bounds.left)
    || !Number.isFinite(bounds.width)
    || bounds.width <= 0
    || !Number.isFinite(clientX)) {
    return null;
  }
  return ((bounds.left + bounds.width - clientX) / bounds.width) * 100;
}
