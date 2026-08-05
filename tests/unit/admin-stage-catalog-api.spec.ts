/**
 * 动态 Stage Catalog 管理 API 契约。
 *
 * @author alex
 * @since 2026-08-05
 */
import { describe, expect, it, vi } from 'vitest';
import {
  createStageDefinitionApiClient,
  type StageDefinitionFetch,
} from '../../frontend/js/admin/api/stage-definitions.js';

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(body),
  } as unknown as Response;
}

const draft = {
  sequenceNumber: 20,
  displayName: '技术方案',
  description: '形成可实施方案',
  stageRules: '明确边界与验收。',
  allowedRunModes: ['DISCUSS_READ_ONLY'] as const,
  commandReferences: [{ identifier: 'architecture-review', version: '1.0.0' }],
  skillReferences: [],
  mcpServerReferences: [],
};

describe('Stage Definition admin API', () => {
  it('uses Catalog Version for creation and Definition Version for draft save', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {}))
      .mockResolvedValueOnce(jsonResponse(200, {}));
    const client = createStageDefinitionApiClient(
      fetchMock as StageDefinitionFetch,
    );

    await client.create('solution-design', draft, 8);
    await client.saveDraft('solution/design', draft, 3);

    expect(fetchMock.mock.calls[0]).toEqual([
      '/api/admin-settings/workbench/stage-definitions',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'If-Match': '8' }),
        body: JSON.stringify({
          definitionIdentifier: 'solution-design',
          ...draft,
        }),
      }),
    ]);
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/admin-settings/workbench/stage-definitions/solution%2Fdesign/draft',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'If-Match': '3' }),
        body: JSON.stringify(draft),
      }),
    ]);
  });

  it('publishes and disables with both Definition and Catalog versions', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {}))
      .mockResolvedValueOnce(jsonResponse(200, {}));
    const client = createStageDefinitionApiClient(
      fetchMock as StageDefinitionFetch,
    );

    await client.publish('solution-design', 5, 12);
    await client.disable('solution-design', 6, 13);

    expect(fetchMock.mock.calls[0][1]).toEqual(expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'If-Match': '5' }),
      body: '{"expectedStageCatalogVersion":12}',
    }));
    expect(fetchMock.mock.calls[1][1]).toEqual(expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'If-Match': '6' }),
      body: '{"expectedStageCatalogVersion":13}',
    }));
  });
});
