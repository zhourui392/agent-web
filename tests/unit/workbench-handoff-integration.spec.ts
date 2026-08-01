/**
 * Workbench 页面串联 Handoff Pinned File 与 Document Pane 的纯编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from "vitest";
import { openWorkbenchHandoffDocument } from "../../frontend/js/lib/workbench-handoff-integration.js";

const REFERENCE = {
  repositoryKey: "agent-web",
  relativePath: "docs/design.md",
} as const;

describe("openWorkbenchHandoffDocument", () => {
  it("closes Handoff, restores a collapsed desktop document pane, then opens the scoped reference", async () => {
    const calls: string[] = [];
    const closeDrawer = vi.fn(() => calls.push("close"));
    const restoreDocumentPane = vi.fn(() => calls.push("restore"));
    const openDocument = vi.fn(async () => {
      calls.push("open");
    });

    await openWorkbenchHandoffDocument(REFERENCE, {
      isMobile: false,
      desktopMode: "COLLAPSED",
      closeDrawer,
      openMobileDrawer: vi.fn(),
      restoreDocumentPane,
      openDocument,
    });

    expect(calls).toEqual(["close", "restore", "open"]);
    expect(openDocument).toHaveBeenCalledWith({ ...REFERENCE });
  });

  it("opens the mobile document drawer before resolving the pinned file", async () => {
    const calls: string[] = [];
    const openMobileDrawer = vi.fn(() => calls.push("mobile"));
    const openDocument = vi.fn(async () => {
      calls.push("open");
    });

    await openWorkbenchHandoffDocument(REFERENCE, {
      isMobile: true,
      desktopMode: "COLLAPSED",
      closeDrawer: vi.fn(() => calls.push("close")),
      openMobileDrawer,
      restoreDocumentPane: vi.fn(),
      openDocument,
    });

    expect(calls).toEqual(["close", "mobile", "open"]);
  });

  it("does not change an already visible desktop document layout", async () => {
    const restoreDocumentPane = vi.fn();
    const openMobileDrawer = vi.fn();
    const openDocument = vi.fn().mockResolvedValue(undefined);

    await openWorkbenchHandoffDocument(REFERENCE, {
      isMobile: false,
      desktopMode: "NORMAL",
      closeDrawer: vi.fn(),
      openMobileDrawer,
      restoreDocumentPane,
      openDocument,
    });

    expect(restoreDocumentPane).not.toHaveBeenCalled();
    expect(openMobileDrawer).not.toHaveBeenCalled();
    expect(openDocument).toHaveBeenCalledOnce();
  });
});
