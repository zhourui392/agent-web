/**
 * TD-07 Handoff Drawer 可见交互与安全边界。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), "utf8");
}

describe("WorkbenchHandoffDrawer source contract", () => {
  it("renders all five editable sections plus conflict and upstream reception actions", async () => {
    const drawer = await source(
      "frontend/js/components/WorkbenchHandoffDrawer.vue",
    );

    expect(drawer).toContain('data-test="handoff-summary"');
    expect(drawer).toContain('data-test="handoff-decisions"');
    expect(drawer).toContain('data-test="handoff-open-questions"');
    expect(drawer).toContain('data-test="handoff-pinned-files"');
    expect(drawer).toContain('data-test="handoff-referenced-runs"');
    expect(drawer).toContain('data-test="handoff-conflict"');
    expect(drawer).toContain('data-test="handoff-source-stale"');
    expect(drawer).toContain("emit('accept-latest')");
    expect(drawer).toContain("emit('keep-current')");
    expect(drawer).toContain("emit('open-document'");
  });

  it("uses plain Vue interpolation and disables save/accept for read-only workbenches", async () => {
    const drawer = await source(
      "frontend/js/components/WorkbenchHandoffDrawer.vue",
    );

    expect(drawer).not.toContain("v-html");
    expect(drawer).toContain(
      ':disabled="readOnly || loading || saving || !dirty"',
    );
    expect(drawer).toContain(':disabled="readOnly || accepting"');
    expect(drawer).toContain("{{ conflict.summary }}");
    expect(drawer).toContain(
      "{{ file.repositoryKey }}/{{ file.relativePath }}",
    );
  });

  it("is presentational and does not reach into Workbench globals or transport code", async () => {
    const drawer = await source(
      "frontend/js/components/WorkbenchHandoffDrawer.vue",
    );
    expect(drawer).not.toContain("createWorkbenchHandoffApiClient");
    expect(drawer).not.toContain("useWorkbenchShell");
    expect(drawer).not.toContain("/api/workbenches");
  });
});

describe("Workbench Handoff page integration", () => {
  it("replaces the future placeholder with a phase-toolbar entry and complete drawer wiring", async () => {
    const page = await source("frontend/js/pages/Workbench.vue");

    expect(page).toContain(
      "import WorkbenchHandoffDrawer from '../components/WorkbenchHandoffDrawer.vue'",
    );
    expect(page).toContain(
      "import { useWorkbenchHandoff } from '../composables/useWorkbenchHandoff.js'",
    );
    expect(page).toContain("WorkbenchHandoffDrawer");
    expect(page).toContain('data-test="open-handoff-drawer"');
    expect(page).toContain('@click="openHandoffDrawer"');
    expect(page).toContain("<workbench-handoff-drawer");
    expect(page).toContain(':phase="selectedPhase"');
    expect(page).toContain('@update:draft="updateHandoffDraft"');
    expect(page).toContain('@save="saveHandoff"');
    expect(page).toContain('@accept-latest="acceptLatestSource"');
    expect(page).toContain('@keep-current="keepCurrentSource"');
    expect(page).toContain('@open-document="openHandoffDocument"');
    expect(page).not.toContain("{ name: '阶段交接'");
  });

  it("derives archived read-only state for the composable while keeping the view entry available", async () => {
    const page = await source("frontend/js/pages/Workbench.vue");

    expect(page).toContain(
      "const archived = computed(() => shell.detail.value?.status === 'ARCHIVED')",
    );
    expect(page).toMatch(
      /useWorkbenchHandoff\(\{[\s\S]*?workbenchId,[\s\S]*?phase: shell\.selectedPhase,[\s\S]*?archived,/,
    );
    expect(page).toContain(':read-only="handoffReadOnly"');
    expect(page).not.toMatch(
      /data-test="open-handoff-drawer"[\s\S]{0,180}:disabled="[^\"]*ARCHIVED/,
    );
  });
});
