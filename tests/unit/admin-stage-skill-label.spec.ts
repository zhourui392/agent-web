/**
 * Stage Catalog 能力选择项的显示契约。
 *
 * @author alex
 * @since 2026-08-06
 */
import { describe, expect, it } from 'vitest';
import { capabilitySelectionLabel } from '../../frontend/js/lib/capability-selection-label';

const capability = {
  identifier: 'domain-modeling-audit',
  version: 'sha256-content-version',
  displayName: '一段很长的 Skill 描述，不应出现在 Stage Catalog 编辑器中',
};

describe('Stage Catalog capability selection label', () => {
  it('shows only the Skill identifier as its name', () => {
    expect(capabilitySelectionLabel('skills', capability))
      .toBe('domain-modeling-audit');
  });

  it('keeps the detailed label for Command and MCP selections', () => {
    const detailedLabel = '一段很长的 Skill 描述，不应出现在 Stage Catalog 编辑器中'
      + ' · domain-modeling-audit@sha256-content-version';

    expect(capabilitySelectionLabel('commands', capability)).toBe(detailedLabel);
    expect(capabilitySelectionLabel('mcpServers', capability)).toBe(detailedLabel);
  });
});
