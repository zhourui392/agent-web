import { describe, expect, it } from 'vitest';
import {
  agentLabel,
  isAgentUsable,
  resolveNewConversationAgent,
  selectableAgents,
  shouldApplyCatalogDefault,
  type AgentCatalog,
} from '../../frontend/js/lib/agent-catalog.js';

const catalog: AgentCatalog = {
  defaultAgent: 'CODEX',
  defaultVersion: 7,
  agents: [
    {
      type: 'CODEX', displayName: 'Codex', purpose: 'GENERAL', available: true,
      userSelectable: true, defaultEligible: true, allEnvironments: true,
      supportedEnvironments: [],
    },
    {
      type: 'CLAUDE', displayName: 'Claude', purpose: 'GENERAL', available: true,
      userSelectable: true, defaultEligible: true, allEnvironments: true,
      supportedEnvironments: [],
    },
    {
      type: 'NATIVE', displayName: '诊断 Agent', purpose: 'DIAGNOSIS', available: true,
      userSelectable: true, defaultEligible: false, allEnvironments: false,
      supportedEnvironments: ['test'],
    },
  ],
};

describe('agent catalog selection', () => {
  it('only exposes NATIVE in its bound environment', () => {
    expect(selectableAgents(catalog, 'test').map((agent) => agent.type))
      .toEqual(['CODEX', 'CLAUDE', 'NATIVE']);
    expect(selectableAgents(catalog, 'prod').map((agent) => agent.type))
      .toEqual(['CODEX', 'CLAUDE']);
    expect(isAgentUsable(catalog, 'NATIVE', 'prod')).toBe(false);
  });

  it('falls back from stale local selection to the server default', () => {
    expect(resolveNewConversationAgent(catalog, 'NATIVE', 'prod')).toBe('CODEX');
    expect(resolveNewConversationAgent(catalog, 'NATIVE', 'test')).toBe('NATIVE');
    expect(resolveNewConversationAgent(catalog, 'UNKNOWN', 'test')).toBe('CODEX');
  });

  it('applies a default version change only to an unbound new conversation', () => {
    expect(shouldApplyCatalogDefault('6', catalog.defaultVersion, '')).toBe(true);
    expect(shouldApplyCatalogDefault('6', catalog.defaultVersion, 'session-1')).toBe(false);
    expect(shouldApplyCatalogDefault('7', catalog.defaultVersion, '')).toBe(false);
  });

  it('keeps an unavailable historical NATIVE identity visible with a clear label', () => {
    const disabled = { ...catalog, agents: catalog.agents.map((agent) =>
      agent.type === 'NATIVE' ? { ...agent, available: false } : agent) };

    expect(agentLabel(disabled, 'NATIVE', 'test', true)).toBe('诊断 Agent（当前不可用）');
    expect(isAgentUsable(disabled, 'NATIVE', 'test')).toBe(false);
    expect(agentLabel(disabled, 'NATIVE', 'test', false)).toBe('诊断 Agent');
  });
});
