/**
 * TD-05 Capability Drawer 可见语义与安全边界。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('WorkbenchCapabilityDrawer source contract', () => {
  it('shows profile provenance, all three public override fields, and next-run evidence', async () => {
    const drawer = await source('frontend/js/components/WorkbenchCapabilityDrawer.vue');

    expect(drawer).toContain('profile.profileId');
    expect(drawer).toContain('profile.profileVersion');
    expect(drawer).toContain('profile.profileHash');
    expect(drawer).toContain('rule.source');
    expect(drawer).toContain('draft.optionalSkillIds');
    expect(drawer).toContain('draft.optionalMcpServerIds');
    expect(drawer).toContain('draft.additionalRule');
    expect(drawer).toContain('profile.activeRunSnapshotHash');
    expect(drawer).toContain('data-test="capability-next-run-notice"');
    expect(drawer).toContain('data-test="capability-restore-defaults"');
    expect(drawer).toContain('data-test="capability-save"');
    expect(drawer).toContain('只影响下一轮');
  });

  it('does not render untrusted HTML or reference APIs outside the Workbench boundary', async () => {
    const [drawer, capabilityApi] = await Promise.all([
      source('frontend/js/components/WorkbenchCapabilityDrawer.vue'),
      source('frontend/js/api/workbench-capability.ts'),
    ]);
    const production = `${drawer}\n${capabilityApi}`;

    expect(production).not.toContain('v-html');
    expect(production).not.toMatch(/\/api\/(?:chat|fs|harness)(?:\/|['"`?])/);
    expect(production).not.toContain('addedOptionalSkillIds');
    expect(production).not.toContain('removedOptionalSkillIds');
    expect(production).not.toContain('selectedOptionalRuleIds');
  });

  it('is reachable from the phase toolbar instead of the future-feature placeholder', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('data-test="open-capability-drawer"');
    expect(page).toContain('<workbench-capability-drawer');
    expect(page).toContain('@click="openCapabilityDrawer"');
    expect(page).not.toContain("{ name: '阶段能力'");
  });
});
