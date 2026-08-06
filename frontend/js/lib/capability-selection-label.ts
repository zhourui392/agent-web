/**
 * Stage Catalog 能力选择项的显示规则。
 *
 * @author alex
 * @since 2026-08-06
 */

export type CapabilitySelectionKind = 'commands' | 'skills' | 'mcpServers';

export interface CapabilitySelectionLabelSource {
  identifier: string;
  version: string;
  displayName: string;
}

export function capabilitySelectionLabel(
  kind: CapabilitySelectionKind,
  capability: CapabilitySelectionLabelSource,
): string {
  if (kind === 'skills') return capability.identifier;
  return `${capability.displayName} · ${capability.identifier}@${capability.version}`;
}
