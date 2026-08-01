/**
 * TD-05 Workbench Capability Profile / Override API 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  WorkbenchCapabilityApiError,
  createWorkbenchCapabilityApiClient,
  type WorkbenchCapabilityFetch,
} from '../../frontend/js/api/workbench-capability.js';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function effectiveProfileBody(): Record<string, unknown> {
  return {
    phase: 'IMPLEMENT_TEST',
    status: 'AVAILABLE',
    profileId: 'workbench-implement-test',
    profileVersion: '1.0.0',
    profileHash: 'a'.repeat(64),
    rules: [{
      id: 'platform/workbench-safety',
      required: true,
      selected: true,
      source: 'PHASE_PROFILE',
      summary: '平台安全规则',
      access: null,
    }],
    skills: [{
      id: 'java-tdd',
      required: false,
      selected: true,
      source: 'PHASE_PROFILE',
      summary: 'Java TDD',
      access: null,
    }],
    mcpServers: [{
      id: 'repository-query',
      required: false,
      selected: true,
      source: 'MCP_CATALOG',
      summary: '仓库写入工具',
      access: 'WRITE',
    }],
    optionalSkillIds: ['java-tdd'],
    optionalMcpServerIds: ['repository-query'],
    additionalRule: '',
    overrideVersion: 3,
    warnings: ['local-test-runner 当前不可用'],
    effectiveFrom: 'NEXT_RUN',
    activeRunSnapshotHash: 'b'.repeat(64),
  };
}

describe('workbench capability API client', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads the owner-scoped effective phase profile and validates its projection', async () => {
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(200, effectiveProfileBody()));
    const client = createWorkbenchCapabilityApiClient(fetcher);

    const profile = await client.getEffectiveProfile('wb/一 二', 'IMPLEMENT_TEST');

    expect(fetcher).toHaveBeenCalledWith(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/phases/IMPLEMENT_TEST/capability-profile',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      }),
    );
    expect(profile).toMatchObject({
      phase: 'IMPLEMENT_TEST',
      status: 'AVAILABLE',
      profileVersion: '1.0.0',
      optionalSkillIds: ['java-tdd'],
      overrideVersion: 3,
      activeRunSnapshotHash: 'b'.repeat(64),
      rules: [expect.objectContaining({ access: null })],
      skills: [expect.objectContaining({ access: null })],
      mcpServers: [expect.objectContaining({ access: 'WRITE' })],
    });
  });

  it('accepts null MCP access only for unselected or explicitly unavailable items', async () => {
    const unselected = effectiveProfileBody();
    unselected.mcpServers = [{
      id: 'optional-reader',
      required: false,
      selected: false,
      source: 'PHASE_PROFILE',
      summary: null,
      access: null,
    }];
    unselected.optionalMcpServerIds = [];
    const unavailable = effectiveProfileBody();
    unavailable.status = 'DEGRADED';
    unavailable.mcpServers = [{
      id: 'offline-writer',
      required: false,
      selected: true,
      source: 'UNAVAILABLE',
      summary: null,
      access: null,
    }];
    unavailable.optionalMcpServerIds = ['offline-writer'];
    const readable = effectiveProfileBody();
    readable.mcpServers = [{
      id: 'repository-reader',
      required: false,
      selected: true,
      source: 'MCP_CATALOG',
      summary: '只读仓库查询',
      access: 'READ',
    }];
    readable.optionalMcpServerIds = ['repository-reader'];
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValueOnce(jsonResponse(200, unselected))
      .mockResolvedValueOnce(jsonResponse(200, unavailable))
      .mockResolvedValueOnce(jsonResponse(200, readable));
    const client = createWorkbenchCapabilityApiClient(fetcher);

    await expect(client.getEffectiveProfile('wb-1', 'IMPLEMENT_TEST'))
      .resolves.toMatchObject({ mcpServers: [expect.objectContaining({ access: null })] });
    await expect(client.getEffectiveProfile('wb-1', 'IMPLEMENT_TEST'))
      .resolves.toMatchObject({ mcpServers: [expect.objectContaining({
        source: 'UNAVAILABLE', access: null,
      })] });
    await expect(client.getEffectiveProfile('wb-1', 'IMPLEMENT_TEST'))
      .resolves.toMatchObject({ mcpServers: [expect.objectContaining({ access: 'READ' })] });
  });

  it.each([
    {
      name: 'rule access is not null',
      mutate(body: Record<string, unknown>) {
        body.rules = [{
          id: 'platform/workbench-safety', required: true, selected: true,
          source: 'PHASE_PROFILE', summary: null, access: 'READ',
        }];
      },
    },
    {
      name: 'rule access is missing',
      mutate(body: Record<string, unknown>) {
        body.rules = [{
          id: 'platform/workbench-safety', required: true, selected: true,
          source: 'PHASE_PROFILE', summary: null,
        }];
      },
    },
    {
      name: 'skill access is not null',
      mutate(body: Record<string, unknown>) {
        body.skills = [{
          id: 'java-tdd', required: false, selected: true,
          source: 'PHASE_PROFILE', summary: null, access: 'WRITE',
        }];
      },
    },
    {
      name: 'MCP access is missing',
      mutate(body: Record<string, unknown>) {
        body.mcpServers = [{
          id: 'repository-query', required: false, selected: true,
          source: 'MCP_CATALOG', summary: null,
        }];
      },
    },
    {
      name: 'MCP access is unknown',
      mutate(body: Record<string, unknown>) {
        body.mcpServers = [{
          id: 'repository-query', required: false, selected: true,
          source: 'MCP_CATALOG', summary: null, access: 'ADMIN',
        }];
      },
    },
    {
      name: 'selected available MCP has null access',
      mutate(body: Record<string, unknown>) {
        body.mcpServers = [{
          id: 'repository-query', required: false, selected: true,
          source: 'MCP_CATALOG', summary: null, access: null,
        }];
      },
    },
    {
      name: 'unselected MCP claims WRITE access',
      mutate(body: Record<string, unknown>) {
        body.mcpServers = [{
          id: 'repository-query', required: false, selected: false,
          source: 'PHASE_PROFILE', summary: null, access: 'WRITE',
        }];
      },
    },
    {
      name: 'unavailable MCP claims WRITE access',
      mutate(body: Record<string, unknown>) {
        body.mcpServers = [{
          id: 'repository-query', required: false, selected: true,
          source: 'UNAVAILABLE', summary: null, access: 'WRITE',
        }];
      },
    },
  ])('rejects untrusted or contradictory capability access: $name', async ({ mutate }) => {
    const body = effectiveProfileBody();
    mutate(body);
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(200, body));

    await expect(createWorkbenchCapabilityApiClient(fetcher)
      .getEffectiveProfile('wb-1', 'IMPLEMENT_TEST'))
      .rejects.toMatchObject({ code: 'WORKBENCH_CAPABILITY_RESPONSE_INVALID' });
  });

  it('loads an override and treats the stable not-found code as default configuration', async () => {
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValueOnce(jsonResponse(200, {
        version: 4,
        optionalSkillIds: ['java-tdd'],
        optionalMcpServerIds: ['repository-query'],
        additionalRule: '优先复用现有组件',
        updatedAt: 1_722_528_000_000,
      }))
      .mockResolvedValueOnce(jsonResponse(404, {
        code: 'WORKBENCH_CAPABILITY_OVERRIDE_NOT_FOUND',
      }));
    const client = createWorkbenchCapabilityApiClient(fetcher);

    await expect(client.getOverride('wb-1', 'IMPLEMENT_TEST')).resolves.toMatchObject({
      version: 4,
      additionalRule: '优先复用现有组件',
    });
    await expect(client.getOverride('wb-1', 'IMPLEMENT_TEST')).resolves.toBeNull();
  });

  it('puts only the TD-05 three-field override and carries the optimistic If-Match version', async () => {
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(200, {
        version: 5,
        effectiveFrom: 'NEXT_RUN',
        activeRunSnapshotHash: 'c'.repeat(64),
      }));
    const client = createWorkbenchCapabilityApiClient(fetcher);
    const override = {
      optionalSkillIds: ['java-tdd'],
      optionalMcpServerIds: ['repository-query'],
      additionalRule: '保持公开 API 兼容',
    };

    const result = await client.putOverride('wb-1', 'SOLUTION_DESIGN', 4, override);

    expect(result).toEqual({
      version: 5,
      effectiveFrom: 'NEXT_RUN',
      activeRunSnapshotHash: 'c'.repeat(64),
    });
    const init = fetcher.mock.calls[0]?.[1] as RequestInit;
    expect(init).toEqual(expect.objectContaining({
      method: 'PUT',
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'If-Match': '4',
      },
      body: JSON.stringify(override),
    }));
    expect(init.body).not.toContain('addedOptionalSkillIds');
    expect(init.body).not.toContain('removedOptionalSkillIds');
    expect(init.body).not.toContain('selectedOptionalRuleIds');
  });

  it('deletes the current override with If-Match and preserves next-run evidence', async () => {
    const fetcher = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(200, {
        version: 0,
        effectiveFrom: 'NEXT_RUN',
        activeRunSnapshotHash: null,
      }));
    const client = createWorkbenchCapabilityApiClient(fetcher);

    await expect(client.deleteOverride('wb-1', 'REVIEW_REFACTOR', 7))
      .resolves.toMatchObject({ effectiveFrom: 'NEXT_RUN' });

    expect(fetcher.mock.calls[0]).toEqual([
      '/api/workbenches/wb-1/phases/REVIEW_REFACTOR/capability-override',
      expect.objectContaining({
        method: 'DELETE',
        headers: { Accept: 'application/json', 'If-Match': '7' },
      }),
    ]);
  });

  it('fails closed on malformed success payloads and sanitizes server errors', async () => {
    const malformed = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(200, {
        ...effectiveProfileBody(),
        activeRunSnapshotHash: '/home/user/.codex/auth.json',
      }));
    const failed = vi.fn<WorkbenchCapabilityFetch>()
      .mockResolvedValue(jsonResponse(409, {
        code: 'WORKBENCH_CAPABILITY_VERSION_CONFLICT',
        message: 'sensitive backend detail',
        path: '/home/user/project',
      }));

    await expect(createWorkbenchCapabilityApiClient(malformed)
      .getEffectiveProfile('wb-1', 'IMPLEMENT_TEST'))
      .rejects.toMatchObject({ code: 'WORKBENCH_CAPABILITY_RESPONSE_INVALID' });

    let thrown: unknown;
    try {
      await createWorkbenchCapabilityApiClient(failed).putOverride(
        'wb-1',
        'IMPLEMENT_TEST',
        2,
        { optionalSkillIds: [], optionalMcpServerIds: [], additionalRule: '' },
      );
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(WorkbenchCapabilityApiError);
    expect(thrown).toMatchObject({
      status: 409,
      code: 'WORKBENCH_CAPABILITY_VERSION_CONFLICT',
      message: 'Workbench capability request failed',
    });
    expect(JSON.stringify(thrown)).not.toContain('sensitive backend detail');
    expect(JSON.stringify(thrown)).not.toContain('/home/user/project');
  });
});
