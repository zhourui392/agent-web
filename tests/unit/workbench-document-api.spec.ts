/**
 * Workbench scoped Document API client 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { readFile } from 'node:fs/promises';
import { describe, expect, it, vi } from 'vitest';
import {
  WORKBENCH_DOCUMENT_API_LIMITS,
  WorkbenchDocumentApiError,
  createWorkbenchDocumentApiClient,
  type WorkbenchDocumentFetch,
} from '../../frontend/js/api/workbench-document.js';

type FetchMock = ReturnType<typeof vi.fn<WorkbenchDocumentFetch>>;

const LOCATOR = {
  workbenchId: 'wb/一 二?',
  repositoryKey: 'service/api',
  relativePath: 'docs/设计 方案?.md',
};

function contentBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    reference: {
      repositoryKey: LOCATOR.repositoryKey,
      relativePath: LOCATOR.relativePath,
    },
    kind: 'MARKDOWN',
    mediaType: 'text/markdown',
    encoding: 'UTF-8',
    size: 12,
    lastModified: 1720000000000,
    contentVersion: 'sha256:v2',
    content: '# 设计',
    truncated: false,
    deleted: false,
    ...overrides,
  };
}

function response(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
  });
}

function fetchMock(resolved: Response): FetchMock {
  return vi.fn<WorkbenchDocumentFetch>().mockResolvedValue(resolved);
}

function requestInit(fetcher: FetchMock, callIndex = 0): RequestInit {
  return fetcher.mock.calls[callIndex][1] as RequestInit;
}

describe('workbench document tree API', () => {
  it('keeps repository keys with slashes in query and sends bounded tree parameters', async () => {
    const fetcher = fetchMock(response(200, {
      repositoryKey: LOCATOR.repositoryKey,
      path: LOCATOR.relativePath,
      entries: [],
      truncated: false,
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await client.listTree({ ...LOCATOR, limit: 37 });

    expect(fetcher).toHaveBeenCalledWith(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F' +
      '/documents/tree' +
      '?repositoryKey=service%2Fapi' +
      '&path=docs%2F%E8%AE%BE%E8%AE%A1%20%E6%96%B9%E6%A1%88%3F.md&limit=37',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: expect.objectContaining({ Accept: 'application/json' }),
      }),
    );
  });

  it('uses an explicit bounded default for the repository root', async () => {
    const fetcher = fetchMock(response(200, {
      repositoryKey: 'agent-web',
      path: '',
      entries: [],
      truncated: false,
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await client.listTree({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: '',
    });

    expect(fetcher.mock.calls[0][0]).toBe(
      '/api/workbenches/wb-1/documents/tree' +
      `?repositoryKey=agent-web&path=&limit=${WORKBENCH_DOCUMENT_API_LIMITS.maximumTreeEntries}`,
    );
  });

  it.each([0, -1, 1.5, 1001])(
    'rejects an out-of-bounds tree limit %s before fetch',
    async (limit) => {
      const fetcher = fetchMock(response(200, {}));
      const client = createWorkbenchDocumentApiClient(fetcher);

      await expect(client.listTree({
        workbenchId: 'wb-1',
        repositoryKey: 'agent-web',
        relativePath: '',
        limit,
      })).rejects.toMatchObject({
        status: 400,
        code: 'WORKBENCH_DOCUMENT_REQUEST_INVALID',
      });
      expect(fetcher).not.toHaveBeenCalled();
    },
  );

  it('projects only bounded tree fields and rejects malformed entries', async () => {
    const fetcher = fetchMock(response(200, {
      repositoryKey: LOCATOR.repositoryKey,
      path: LOCATOR.relativePath,
      entries: [{
        name: 'src',
        relativePath: `${LOCATOR.relativePath}/src`,
        kind: 'DIRECTORY',
        size: null,
        lastModified: 1720000000000,
        absolutePath: '/home/private/project/src',
        token: 'secret-value',
      }],
      truncated: false,
      repositoryRoot: '/home/private/project',
      token: 'secret-value',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.listTree({ ...LOCATOR, limit: 10 });

    expect(result).toEqual({
      repositoryKey: LOCATOR.repositoryKey,
      path: LOCATOR.relativePath,
      entries: [{
        name: 'src',
        relativePath: `${LOCATOR.relativePath}/src`,
        kind: 'DIRECTORY',
        size: null,
        lastModified: 1720000000000,
      }],
      truncated: false,
    });
    expect(JSON.stringify(result)).not.toMatch(/absolutePath|repositoryRoot|home|token|secret/i);

    const invalidFetcher = fetchMock(response(200, {
      repositoryKey: LOCATOR.repositoryKey,
      path: LOCATOR.relativePath,
      entries: [{
        name: 'bad',
        relativePath: `${LOCATOR.relativePath}/bad`,
        kind: 'FILE',
        size: -1,
        lastModified: 0,
      }],
      truncated: false,
    }));
    const invalidClient = createWorkbenchDocumentApiClient(invalidFetcher);
    await expect(invalidClient.listTree({ ...LOCATOR, limit: 10 })).rejects.toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
    });
  });

  it.each([
    { repositoryKey: 'other', path: LOCATOR.relativePath },
    { repositoryKey: LOCATOR.repositoryKey, path: 'docs/other.md' },
  ])('rejects a tree response identity mismatch without exposing it: %j', async (identity) => {
    const fetcher = fetchMock(response(200, {
      ...identity,
      entries: [],
      truncated: false,
      repositoryRoot: '/home/private/project',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.listTree({ ...LOCATOR, limit: 10 })).rejects.toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
      message: 'Workbench document request failed',
    });
  });
});

describe('workbench document content API', () => {
  it('returns a loaded document with the response ETag and optional If-None-Match', async () => {
    const document = contentBody();
    const fetcher = fetchMock(response(200, document, { ETag: '"sha256:v2"' }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.readContent(LOCATOR, '"sha256:v1"');

    expect(fetcher.mock.calls[0][0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F' +
      '/documents/content' +
      '?repositoryKey=service%2Fapi' +
      '&path=docs%2F%E8%AE%BE%E8%AE%A1%20%E6%96%B9%E6%A1%88%3F.md',
    );
    expect(requestInit(fetcher).headers).toEqual(expect.objectContaining({
      Accept: 'application/json',
      'If-None-Match': '"sha256:v1"',
    }));
    expect(result).toEqual({
      status: 'LOADED',
      etag: '"sha256:v2"',
      document,
    });
  });

  it('omits If-None-Match when the caller has no loaded version', async () => {
    const fetcher = fetchMock(response(200, contentBody({
      reference: { repositoryKey: 'agent-web', relativePath: 'README.md' },
    })));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
    });

    expect(requestInit(fetcher).headers).not.toHaveProperty('If-None-Match');
  });

  it('distinguishes 304 not-modified without attempting JSON parsing', async () => {
    const notModified = new Response(null, {
      status: 304,
      headers: { ETag: '"sha256:v1"' },
    });
    const fetcher = fetchMock(notModified);
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
    }, '"sha256:v1"')).resolves.toEqual({
      status: 'NOT_MODIFIED',
      etag: '"sha256:v1"',
    });
  });

  it('maps content 404 to deleted without exposing the response body', async () => {
    const fetcher = fetchMock(response(404, {
      code: 'WORKBENCH_DOCUMENT_NOT_FOUND',
      message: 'deleted /home/private/project/README.md token=secret-value',
      path: '/home/private/project/README.md',
      token: 'secret-value',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
    });

    expect(result).toEqual({ status: 'DELETED' });
    expect(JSON.stringify(result)).not.toMatch(/home|token|secret/i);
  });

  it('does not collapse owner or repository 404 responses into document deletion', async () => {
    const fetcher = fetchMock(response(404, {
      code: 'WORKBENCH_NOT_FOUND',
      message: 'owner mismatch at /home/private token=secret-value',
      path: '/home/private',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
    })).rejects.toMatchObject({
      status: 404,
      code: 'WORKBENCH_NOT_FOUND',
      message: 'Workbench document request failed',
    });
  });

  it('projects only whitelisted content fields and rejects invalid required fields', async () => {
    const fetcher = fetchMock(response(200, contentBody({
      repositoryRoot: '/home/private/project',
      token: 'secret-value',
    }), { ETag: '"sha256:v2"' }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const loaded = await client.readContent(LOCATOR);

    expect(loaded.status).toBe('LOADED');
    expect(JSON.stringify(loaded)).not.toMatch(/repositoryRoot|home|token|secret-value/i);

    const invalidFetcher = fetchMock(response(200, contentBody({ kind: 'SECRET_BINARY' })));
    const invalidClient = createWorkbenchDocumentApiClient(invalidFetcher);
    await expect(invalidClient.readContent(LOCATOR)).rejects.toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
      message: 'Workbench document request failed',
    });
  });

  it('rejects oversized response ETags', async () => {
    const fetcher = fetchMock(response(200, contentBody(), { ETag: 'e'.repeat(1025) }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent(LOCATOR)).rejects.toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
    });
  });

  it.each([
    { repositoryKey: 'other', relativePath: LOCATOR.relativePath },
    { repositoryKey: LOCATOR.repositoryKey, relativePath: 'docs/other.md' },
  ])('rejects a content response identity mismatch without reflecting it: %j', async (reference) => {
    const fetcher = fetchMock(response(200, contentBody({
      reference,
      content: '/home/private token=secret-value',
    })));
    const client = createWorkbenchDocumentApiClient(fetcher);

    let thrown: unknown;
    try {
      await client.readContent(LOCATOR);
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
      message: 'Workbench document request failed',
    });
    expect(String(thrown)).not.toMatch(/home|token|secret/i);
  });

  it('preserves the changed-during-read code without exposing diagnostics', async () => {
    const fetcher = fetchMock(response(409, {
      code: 'WORKBENCH_DOCUMENT_CHANGED_DURING_READ',
      message: 'changed at /home/private token=secret-value',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent(LOCATOR)).rejects.toMatchObject({
      status: 409,
      code: 'WORKBENCH_DOCUMENT_CHANGED_DURING_READ',
      message: 'Workbench document request failed',
    });
  });
});

describe('workbench scoped inline image API', () => {
  it('loads only the encoded Workbench inline-image endpoint with a bounded image Accept header', async () => {
    const image = new Blob(['png-bytes'], { type: 'image/png' });
    const fetcher = fetchMock(new Response(image, {
      status: 200,
      headers: {
        'Content-Type': 'image/png',
        ETag: '"sha256:image-v2"',
      },
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.readInlineImage(LOCATOR, '"sha256:image-v1"');

    expect(fetcher).toHaveBeenCalledWith(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F' +
      '/documents/inline-image' +
      '?repositoryKey=service%2Fapi' +
      '&path=docs%2F%E8%AE%BE%E8%AE%A1%20%E6%96%B9%E6%A1%88%3F.md',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        headers: {
          Accept: 'image/png, image/jpeg, image/gif, image/webp',
          'If-None-Match': '"sha256:image-v1"',
        },
      }),
    );
    expect(result).toEqual(expect.objectContaining({
      status: 'LOADED',
      mediaType: 'image/png',
      size: 9,
      etag: '"sha256:image-v2"',
    }));
    if (result.status === 'LOADED') {
      await expect(result.blob.text()).resolves.toBe('png-bytes');
    }
  });

  it('supports image ETag revalidation and maps only document 404 to deleted', async () => {
    const notModified = fetchMock(new Response(null, {
      status: 304,
      headers: { ETag: '"sha256:image-v1"' },
    }));
    await expect(
      createWorkbenchDocumentApiClient(notModified).readInlineImage(
        LOCATOR,
        '"sha256:image-v1"',
      ),
    ).resolves.toEqual({
      status: 'NOT_MODIFIED',
      etag: '"sha256:image-v1"',
    });

    const deleted = fetchMock(response(404, {
      code: 'WORKBENCH_DOCUMENT_DELETED',
      message: '/home/private/diagram.png token=secret-value',
    }));
    await expect(
      createWorkbenchDocumentApiClient(deleted).readInlineImage(LOCATOR),
    ).resolves.toEqual({ status: 'DELETED' });
  });

  it.each([
    'image/svg+xml',
    'text/html',
    'application/octet-stream',
  ])('rejects the non-raster inline image media type %s', async (contentType) => {
    const fetcher = fetchMock(new Response('<svg onload="alert(1)"/>', {
      status: 200,
      headers: { 'Content-Type': contentType },
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readInlineImage(LOCATOR)).rejects.toMatchObject({
      status: 200,
      code: 'WORKBENCH_DOCUMENT_RESPONSE_INVALID',
      message: 'Workbench document request failed',
    });
  });
});

describe('workbench document download API', () => {
  it('returns a Blob and sanitized filename metadata without triggering a browser download', async () => {
    const body = new Blob(['report-body'], { type: 'text/plain' });
    const download = new Response(body, {
      status: 200,
      headers: {
        'Content-Type': 'text/plain',
        'Content-Disposition': "attachment; filename*=UTF-8''..%2F..%2F%E6%B5%8B%E8%AF%95.txt",
        ETag: '"sha256:download"',
      },
    });
    const fetcher = fetchMock(download);
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.download(LOCATOR);

    expect(fetcher.mock.calls[0][0]).toBe(
      '/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F' +
      '/documents/download' +
      '?repositoryKey=service%2Fapi' +
      '&path=docs%2F%E8%AE%BE%E8%AE%A1%20%E6%96%B9%E6%A1%88%3F.md',
    );
    expect(requestInit(fetcher).headers).toEqual(expect.objectContaining({
      Accept: 'application/octet-stream',
    }));
    expect(result.fileName).toBe('测试.txt');
    expect(result.mediaType).toBe('text/plain');
    expect(result.size).toBe(11);
    expect(result.etag).toBe('"sha256:download"');
    await expect(result.blob.text()).resolves.toBe('report-body');
  });

  it('falls back to the requested basename when response filename metadata is unsafe', async () => {
    const fetcher = fetchMock(new Response(new Blob(['x']), {
      status: 200,
      headers: { 'Content-Disposition': 'attachment; filename="../.."' },
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.download({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'reports/result.log',
    });

    expect(result.fileName).toBe('result.log');
  });

  it('bounds untrusted filename and media-type response metadata', async () => {
    const fetcher = fetchMock(new Response(new Blob(['x']), {
      status: 200,
      headers: {
        'Content-Disposition': `attachment; filename="${'s'.repeat(256)}.txt"`,
        'Content-Type': 'text/',
      },
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    const result = await client.download({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'reports/safe-result.log',
    });

    expect(result.fileName).toBe('safe-result.log');
    expect(result.mediaType).toBeNull();
  });

  it.each([
    [404, 'WORKBENCH_DOCUMENT_NOT_FOUND'],
    [413, 'WORKBENCH_DOCUMENT_TOO_LARGE'],
  ])('maps download %s to the allowlisted safe code %s', async (status, code) => {
    const fetcher = fetchMock(response(status, {
      code,
      message: 'failed at /home/private token=secret-value',
      path: '/home/private',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    let thrown: unknown;
    try {
      await client.download(LOCATOR);
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toMatchObject({
      status,
      code,
      message: 'Workbench document request failed',
    });
    expect(thrown).not.toHaveProperty('body');
    expect(thrown).not.toHaveProperty('stack');
    expect(String(thrown)).not.toMatch(/home|token|secret/i);
  });
});

describe('workbench document request safety', () => {
  it.each([
    '/etc/passwd',
    'C:/Windows/system.ini',
    'C:\\Windows\\system.ini',
    '\\\\server\\share\\secret.txt',
    '../secret.txt',
    'docs/../../secret.txt',
  ])('rejects unsafe relative paths without sending %s', async (relativePath) => {
    const fetcher = fetchMock(response(200, {}));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath,
    })).rejects.toBeInstanceOf(WorkbenchDocumentApiError);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it.each(['.', '..'])('rejects unsafe workbench path segment %s before fetch', async (workbenchId) => {
    const fetcher = fetchMock(response(200, {}));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.listTree({
      workbenchId,
      repositoryKey: 'agent-web',
      relativePath: '',
    })).rejects.toMatchObject({
      status: 400,
      code: 'WORKBENCH_DOCUMENT_REQUEST_INVALID',
    });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects oversized paths and header injection before fetch', async () => {
    const fetcher = fetchMock(response(200, {}));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: `docs/${'a'.repeat(WORKBENCH_DOCUMENT_API_LIMITS.maximumPathChars)}.md`,
    })).rejects.toMatchObject({ status: 400 });
    await expect(client.readContent({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
    }, '"etag"\r\nX-Token: secret')).rejects.toMatchObject({ status: 400 });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('throws only a safe status, code, and generic message for server errors', async () => {
    const fetcher = fetchMock(response(403, {
      code: 'WORKBENCH_PATH_FORBIDDEN',
      message: 'forbidden /home/private/project token=secret-value',
      path: '/home/private/project',
      token: 'secret-value',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    let thrown: unknown;
    try {
      await client.listTree({
        workbenchId: 'wb-1',
        repositoryKey: 'agent-web',
        relativePath: '',
      });
    } catch (error) {
      thrown = error;
    }

    expect(thrown).toBeInstanceOf(WorkbenchDocumentApiError);
    expect(thrown).toMatchObject({
      status: 403,
      code: 'WORKBENCH_PATH_FORBIDDEN',
      message: 'Workbench document request failed',
    });
    expect(thrown).not.toHaveProperty('body');
    expect(thrown).not.toHaveProperty('stack');
    expect(thrown).not.toHaveProperty('cause');
    expect(thrown).not.toHaveProperty('path');
    expect(thrown).not.toHaveProperty('token');
    expect(String(thrown)).not.toMatch(/home|token|secret/i);
  });

  it('does not accept arbitrary uppercase response codes as safe', async () => {
    const fetcher = fetchMock(response(403, {
      code: 'SECRET_TOKEN_VALUE',
      message: '/home/private secret-value',
    }));
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.listTree({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: '',
    })).rejects.toMatchObject({
      status: 403,
      code: 'WORKBENCH_DOCUMENT_REQUEST_FAILED',
      message: 'Workbench document request failed',
    });
  });

  it('does not echo network failure diagnostics', async () => {
    const fetcher = vi.fn<WorkbenchDocumentFetch>().mockRejectedValue(
      new Error('connect failed: token=secret-value at /home/private'),
    );
    const client = createWorkbenchDocumentApiClient(fetcher);

    await expect(client.listTree({
      workbenchId: 'wb-1',
      repositoryKey: 'agent-web',
      relativePath: '',
    })).rejects.toMatchObject({
      status: 0,
      code: 'WORKBENCH_DOCUMENT_NETWORK_ERROR',
      message: 'Workbench document request failed',
    });
  });

  it('never references the ordinary fs API or browser download side effects', async () => {
    const source = await readFile(
      new URL('../../frontend/js/api/workbench-document.ts', import.meta.url),
      'utf8',
    );

    expect(source).not.toMatch(/\/api\/fs(?:\/|['"`?])/);
    expect(source).not.toMatch(/createObjectURL|\.click\(\)|createElement\(['"]a['"]\)/);
    expect(source).not.toMatch(/provisional/i);
  });
});
