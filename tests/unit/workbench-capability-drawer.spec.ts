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
    expect(production).not.toMatch(/\/api\/(?:chat|fs)(?:\/|['"`?])/);
    expect(production).not.toContain('addedOptionalSkillIds');
    expect(production).not.toContain('removedOptionalSkillIds');
    expect(production).not.toContain('selectedOptionalRuleIds');
  });

  it('shows trusted MCP access with an explicit WRITE danger warning and never guesses from identity', async () => {
    const drawer = await source('frontend/js/components/WorkbenchCapabilityDrawer.vue');
    const mcpStart = drawer.indexOf('data-test="capability-mcp-servers"');
    const mcpEnd = drawer.indexOf('data-test="capability-additional-rule"', mcpStart);
    const mcpSection = drawer.slice(mcpStart, mcpEnd);

    expect(mcpStart).toBeGreaterThan(0);
    expect(mcpEnd).toBeGreaterThan(mcpStart);
    expect(mcpSection).toContain('data-test="capability-mcp-access"');
    expect(mcpSection).toContain("server.access === 'WRITE'");
    expect(drawer).toContain("access === 'WRITE' ? 'danger'");
    expect(drawer).toContain("server.access === 'WRITE' ? 'dark' : 'plain'");
    expect(drawer).toContain("return 'WRITE · 可修改外部状态'");
    expect(drawer).toContain("return 'READ · 只读访问'");
    expect(drawer).toContain("return server.source === 'UNAVAILABLE'");
    expect(drawer).toContain("? '不可用 · 未授权' : '未授权'");
    expect(mcpSection).toContain("server.source === 'UNAVAILABLE'");
    expect(mcpSection).toContain('!draft.optionalMcpServerIds.includes(server.id)');
    expect(drawer).not.toMatch(/(?:id|summary|source).*includes\([^\n]*(?:write|写入)/i);
    expect(drawer).not.toMatch(/(?:write|写入).*includes\([^\n]*(?:id|summary|source)/i);
  });

  it('is reachable from the Workbench and not a future-feature placeholder', async () => {
    const page = await source('frontend/js/pages/Workbench.vue');

    expect(page).toContain('<workbench-capability-drawer');
    expect(page).toContain('await capability.openCapabilityDrawer()');
    expect(page).not.toContain("{ name: '阶段能力'");
  });
});
