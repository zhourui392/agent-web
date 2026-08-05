/**
 * Workbench Capability Source 管理 API 契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { describe, expect, it, vi } from 'vitest';
import {
  createCapabilitySourceApiClient,
  type CapabilitySourceFetch,
} from '../../frontend/js/admin/api/capability-sources.js';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const candidate = {
  commandCatalogDirectories: [{
    directoryIdentifier: 'commands',
    absoluteDirectory: '/opt/agent/commands',
    enabled: true,
  }],
  skillCatalogDirectories: [{
    directoryIdentifier: 'skills',
    absoluteDirectory: '/opt/agent/skills',
    trustSource: 'PLATFORM',
    enabled: true,
  }],
  mcpConfiguration: {
    schema: 'workbench-mcp-catalog@1',
    servers: [],
  },
};

describe('Capability Source admin API', () => {
  it('validates without a version and updates atomically with If-Match', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { warnings: [] }))
      .mockResolvedValueOnce(jsonResponse(200, { ...candidate, version: 4 }));
    const client = createCapabilitySourceApiClient(
      fetchMock as CapabilitySourceFetch,
    );

    await client.validate(candidate);
    await client.update(candidate, 3);

    expect(fetchMock.mock.calls[0]).toEqual([
      '/api/admin-settings/workbench/capability-sources/validation',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(candidate),
      }),
    ]);
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/admin-settings/workbench/capability-sources',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'If-Match': '3' }),
        body: JSON.stringify(candidate),
      }),
    ]);
    const parsedBody = JSON.parse(String(fetchMock.mock.calls[1][1]?.body));
    expect(parsedBody.mcpConfiguration).toEqual(candidate.mcpConfiguration);
    expect(typeof parsedBody.mcpConfiguration).toBe('object');
  });

  it('surfaces the stable server error code', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(409, {
      code: 'WORKBENCH_CAPABILITY_SOURCE_VERSION_CONFLICT',
      message: 'configuration changed',
    }));
    const client = createCapabilitySourceApiClient(
      fetchMock as CapabilitySourceFetch,
    );

    await expect(client.update(candidate, 2)).rejects.toMatchObject({
      status: 409,
      code: 'WORKBENCH_CAPABILITY_SOURCE_VERSION_CONFLICT',
    });
  });
});
