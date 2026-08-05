/**
 * 动态 Stage 管理页的可达性、生命周期和安全交互契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';

async function source(relativePath: string): Promise<string> {
  return readFile(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

describe('Dynamic Stage admin page', () => {
  it('exposes Capability Source and Stage Catalog without retired fixed profiles', async () => {
    const page = await source('frontend/js/admin/pages/Capabilities.vue');

    expect(page).toContain('<capability-source-settings');
    expect(page).toContain('<stage-catalog-settings');
    expect(page).toContain("name=\"sources\"");
    expect(page).toContain("name=\"stages\"");
    expect(page).not.toContain('REQUIREMENT_ANALYSIS');
    expect(page).not.toContain('REVIEW_REFACTOR');
    expect(page).not.toContain('fetchProfiles');
  });

  it('supports directory and MCP JSON validation before atomic source save', async () => {
    const component = await source(
      'frontend/js/admin/components/CapabilitySourceSettings.vue',
    );

    expect(component).toContain('data-test="command-directory-list"');
    expect(component).toContain('data-test="skill-directory-list"');
    expect(component).toContain('data-test="mcp-json-editor"');
    expect(component).toContain('data-test="capability-source-validate"');
    expect(component).toContain('data-test="capability-source-save"');
    expect(component).toContain('api.validate');
    expect(component).toContain('api.update');
    expect(component).toContain('Secret Reference');
    expect(component).not.toContain('v-html');
  });

  it('shows Draft, Published and Disabled independently from hasDraft', async () => {
    const component = await source(
      'frontend/js/admin/components/StageCatalogSettings.vue',
    );

    expect(component).toContain('definition.lifecycleStatus');
    expect(component).toContain('definition.hasDraft');
    expect(component).toContain('data-test="stage-save-draft"');
    expect(component).toContain('data-test="stage-publish"');
    expect(component).toContain('data-test="stage-disable"');
    expect(component).toContain('sequenceNumber');
    expect(component).toContain('stageRules');
    expect(component).toContain('commandReferences');
    expect(component).toContain('skillReferences');
    expect(component).toContain('mcpServerReferences');
    expect(component).not.toMatch(/draggable|sortable|上移|下移/);
    expect(component).not.toContain('v-html');
  });
});
