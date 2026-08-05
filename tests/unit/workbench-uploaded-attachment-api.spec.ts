/**
 * Workbench 浏览器上传附件的 Owner-scoped transport contract.
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchUploadedAttachmentApiError,
  createWorkbenchUploadedAttachmentApiClient,
  type WorkbenchUploadedAttachmentFetch,
} from '../../frontend/js/api/workbench-uploaded-attachment.js';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function uploadedAttachment(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    attachmentId: 'attachment-1',
    displayName: 'architecture.png',
    mediaType: 'image/png',
    size: 128,
    sha256: 'a'.repeat(64),
    expiresAt: '2026-08-02T00:00:00Z',
    ...overrides,
  };
}

describe('workbench uploaded attachment API client', () => {
  it('uploads multipart bytes to the exact logical Workbench Stage generation scope', async () => {
    const fetcher = vi.fn<WorkbenchUploadedAttachmentFetch>()
      .mockResolvedValue(jsonResponse(201, uploadedAttachment()));
    const client = createWorkbenchUploadedAttachmentApiClient(fetcher);
    const file = new File(['png-body'], 'architecture.png', { type: 'image/png' });

    await expect(client.upload('wb/一 二', 'stage-design', 3, file))
      .resolves.toEqual(uploadedAttachment());

    expect(fetcher).toHaveBeenCalledTimes(1);
    const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C/stages/stage-design/attachments'
        + '?conversationGeneration=3',
    );
    expect(init).toEqual(expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    }));
    expect(init.headers).not.toHaveProperty('Content-Type');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBe(file);
  });

  it('releases only the exact logical attachment identity and accepts only 204', async () => {
    const fetcher = vi.fn<WorkbenchUploadedAttachmentFetch>()
      .mockResolvedValue(new Response(null, { status: 204 }));
    const client = createWorkbenchUploadedAttachmentApiClient(fetcher);

    await expect(client.release(
      'wb-1', 'stage-implementation', 7, 'attachment/opaque',
    )).resolves.toBeUndefined();

    expect(fetcher).toHaveBeenCalledWith(
      '/api/workbenches/wb-1/stages/stage-implementation/attachments/attachment%2Fopaque'
        + '?conversationGeneration=7',
      {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      },
    );
  });

  it('fails closed on leaked storage/path fields or malformed successful projections', async () => {
    const fetcher = vi.fn<WorkbenchUploadedAttachmentFetch>()
      .mockResolvedValueOnce(jsonResponse(201, uploadedAttachment({ storageKey: 'opaque-storage' })))
      .mockResolvedValueOnce(jsonResponse(201, uploadedAttachment({ absolutePath: '/secret/path' })))
      .mockResolvedValueOnce(jsonResponse(201, uploadedAttachment({ sha256: 'A'.repeat(64) })))
      .mockResolvedValueOnce(jsonResponse(201, uploadedAttachment({ expiresAt: 'tomorrow' })))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const client = createWorkbenchUploadedAttachmentApiClient(fetcher);
    const file = new File(['body'], 'notes.txt', { type: 'text/plain' });

    for (let index = 0; index < 4; index += 1) {
      await expect(client.upload('wb-1', 'stage-analysis', 0, file))
        .rejects.toMatchObject({ code: 'WORKBENCH_ATTACHMENT_RESPONSE_INVALID' });
    }
    await expect(client.release('wb-1', 'stage-analysis', 0, 'attachment-1'))
      .rejects.toMatchObject({ code: 'WORKBENCH_ATTACHMENT_RESPONSE_INVALID' });
  });

  it('preserves only fixed safe attachment error codes without exposing response bodies', async () => {
    const fetcher = vi.fn<WorkbenchUploadedAttachmentFetch>()
      .mockResolvedValueOnce(jsonResponse(413, {
        code: 'WORKBENCH_ATTACHMENT_TOO_LARGE',
        message: '/home/alex/private should never be exposed',
      }))
      .mockResolvedValueOnce(jsonResponse(422, {
        code: 'INTERNAL_STORAGE_FAILURE',
        message: 'storage key and stack trace',
      }));
    const client = createWorkbenchUploadedAttachmentApiClient(fetcher);
    const file = new File(['body'], 'notes.txt', { type: 'text/plain' });

    const tooLarge = await client.upload('wb-1', 'stage-analysis', 0, file)
      .catch(error => error) as WorkbenchUploadedAttachmentApiError;
    expect(tooLarge).toMatchObject({
      status: 413,
      code: 'WORKBENCH_ATTACHMENT_TOO_LARGE',
      message: 'Uploaded attachment exceeds the configured size limit',
    });
    expect(String(tooLarge)).not.toContain('/home/alex/private');

    const generic = await client.upload('wb-1', 'stage-analysis', 0, file)
      .catch(error => error) as WorkbenchUploadedAttachmentApiError;
    expect(generic).toMatchObject({
      status: 422,
      code: 'WORKBENCH_ATTACHMENT_INVALID',
      message: 'Uploaded attachment request is invalid',
    });
    expect(String(generic)).not.toContain('storage key');
  });

  it('rejects invalid identity, Stage, generation and empty files before fetch', async () => {
    const fetcher = vi.fn<WorkbenchUploadedAttachmentFetch>();
    const client = createWorkbenchUploadedAttachmentApiClient(fetcher);
    const valid = new File(['body'], 'notes.txt', { type: 'text/plain' });

    await expect(client.upload('', 'stage-analysis', 0, valid)).rejects.toThrow();
    await expect(client.upload('wb-1', '../stage', 0, valid)).rejects.toThrow();
    await expect(client.upload('wb-1', 'stage-analysis', -1, valid)).rejects.toThrow();
    await expect(client.upload(
      'wb-1', 'stage-analysis', 0,
      new File([], 'empty.txt', { type: 'text/plain' }),
    )).rejects.toThrow();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
