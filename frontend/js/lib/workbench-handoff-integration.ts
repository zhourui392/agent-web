/**
 * Workbench 页面中 Handoff Pinned File 到 Document Pane 的最小编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import type { HandoffDocumentReference } from '../api/workbench-handoff.js';

export type WorkbenchHandoffDocumentDesktopMode = 'NORMAL' | 'COLLAPSED' | 'MAXIMIZED';

export interface WorkbenchHandoffDocumentTarget {
  isMobile: boolean;
  desktopMode: WorkbenchHandoffDocumentDesktopMode;
  closeDrawer(): void;
  openMobileDrawer(): void;
  restoreDocumentPane(): void;
  openDocument(reference: HandoffDocumentReference): Promise<void>;
}

export async function openWorkbenchHandoffDocument(
  reference: HandoffDocumentReference,
  target: WorkbenchHandoffDocumentTarget,
): Promise<void> {
  target.closeDrawer();
  if (target.isMobile) {
    target.openMobileDrawer();
  } else if (target.desktopMode === 'COLLAPSED') {
    target.restoreDocumentPane();
  }
  await target.openDocument({ ...reference });
}
