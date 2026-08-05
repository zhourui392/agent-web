/**
 * Workbench 浏览器附件上传、失败重试、释放与 scope isolation.
 *
 * @author alex
 * @since 2026-08-01
 */
// @ts-expect-error Frontend Vue ESM entry has no declaration for this relative test import.
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { describe, expect, it, vi } from 'vitest';
import {
  WorkbenchUploadedAttachmentApiError,
  type WorkbenchUploadedAttachment,
  type WorkbenchUploadedAttachmentApiClient,
} from '../../frontend/js/api/workbench-uploaded-attachment.js';
import { useWorkbenchUploadedAttachments } from '../../frontend/js/composables/useWorkbenchUploadedAttachments.js';

const { nextTick, ref } = frontendVueRuntime as typeof import('vue');

function projection(id = 'attachment-1'): WorkbenchUploadedAttachment {
  return {
    attachmentId: id,
    displayName: 'architecture.png',
    mediaType: 'image/png',
    size: 128,
    sha256: 'a'.repeat(64),
    expiresAt: '2026-08-02T00:00:00Z',
  };
}

function api(overrides: Partial<WorkbenchUploadedAttachmentApiClient> = {})
  : WorkbenchUploadedAttachmentApiClient {
  return {
    upload: vi.fn().mockResolvedValue(projection()),
    release: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason?: unknown) => void = () => undefined;
  const promise = new Promise<T>((accept, fail) => {
    resolve = accept;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe('useWorkbenchUploadedAttachments', () => {
  it('uploads an image with a local preview and exposes only a logical Run reference', async () => {
    const client = api();
    const accepted: unknown[] = [];
    const revoke = vi.fn();
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('SOLUTION_DESIGN'),
      conversationGeneration: ref(2),
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue('blob:preview-1'), revoke },
      onAvailable: attachment => { accepted.push(attachment); return true; },
      onReleased: vi.fn(),
    });
    const file = new File(['png'], 'architecture.png', { type: 'image/png' });

    await attachments.upload(file);

    expect(client.upload).toHaveBeenCalledWith('wb-1', 'SOLUTION_DESIGN', 2, file);
    expect(attachments.items.value).toEqual([expect.objectContaining({
      status: 'AVAILABLE',
      attachmentId: 'attachment-1',
      displayName: 'architecture.png',
      previewUrl: 'blob:preview-1',
    })]);
    expect(accepted).toEqual([{
      type: 'UPLOADED_CONVERSATION',
      attachmentId: 'attachment-1',
      contentHash: 'a'.repeat(64),
      displayName: 'architecture.png',
      mediaType: 'image/png',
      size: 128,
      previewUrl: 'blob:preview-1',
    }]);
    expect(JSON.stringify(accepted)).not.toMatch(/storage|absolutePath|repositoryKey|relativePath/);
    expect(revoke).not.toHaveBeenCalled();
  });

  it('retains a failed item for retry without dropping text or other attachment callbacks', async () => {
    const client = api({
      upload: vi.fn()
        .mockRejectedValueOnce(new WorkbenchUploadedAttachmentApiError(
          422, 'WORKBENCH_ATTACHMENT_INVALID', 'Uploaded attachment request is invalid',
        ))
        .mockResolvedValueOnce(projection()),
    });
    const onAvailable = vi.fn().mockReturnValue(true);
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(0),
      archived: ref(false),
      combinedAttachmentCount: ref(1),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue(null), revoke: vi.fn() },
      onAvailable,
      onReleased: vi.fn(),
    });
    const file = new File(['notes'], 'notes.txt', { type: 'text/plain' });

    await attachments.upload(file);

    expect(attachments.items.value).toEqual([expect.objectContaining({
      status: 'FAILED',
      error: '附件格式、内容或当前阶段绑定无效。',
    })]);
    expect(onAvailable).not.toHaveBeenCalled();
    const clientId = attachments.items.value[0]?.clientId as string;

    await attachments.retry(clientId);

    expect(client.upload).toHaveBeenCalledTimes(2);
    expect(attachments.items.value[0]).toEqual(expect.objectContaining({ status: 'AVAILABLE' }));
    expect(onAvailable).toHaveBeenCalledTimes(1);
  });

  it('keeps an available item when release fails and removes it only after a confirmed 204', async () => {
    const client = api({
      release: vi.fn()
        .mockRejectedValueOnce(new Error('network'))
        .mockResolvedValueOnce(undefined),
    });
    const onReleased = vi.fn();
    const revoke = vi.fn();
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('stage-delivery'),
      conversationGeneration: ref(5),
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue('blob:preview'), revoke },
      onAvailable: vi.fn().mockReturnValue(true),
      onReleased,
    });
    await attachments.upload(new File(['png'], 'architecture.png', { type: 'image/png' }));
    const clientId = attachments.items.value[0]?.clientId as string;

    await attachments.remove(clientId);

    expect(attachments.items.value).toEqual([expect.objectContaining({
      status: 'AVAILABLE',
      error: '附件取消失败，请重试；其他文本和附件未受影响。',
    })]);
    expect(onReleased).not.toHaveBeenCalled();
    expect(revoke).not.toHaveBeenCalled();

    await attachments.remove(clientId);

    expect(client.release).toHaveBeenNthCalledWith(
      2, 'wb-1', 'stage-delivery', 5, 'attachment-1',
    );
    expect(attachments.items.value).toEqual([]);
    expect(onReleased).toHaveBeenCalledWith('attachment-1');
    expect(revoke).toHaveBeenCalledWith('blob:preview');
  });

  it('treats an unavailable response as an idempotent release after a lost 204 response', async () => {
    const client = api({
      release: vi.fn().mockRejectedValue(new WorkbenchUploadedAttachmentApiError(
        410,
        'WORKBENCH_ATTACHMENT_UNAVAILABLE',
        'Uploaded attachment is unavailable',
      )),
    });
    const onReleased = vi.fn();
    const revoke = vi.fn();
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('stage-delivery'),
      conversationGeneration: ref(5),
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue('blob:preview'), revoke },
      onAvailable: vi.fn().mockReturnValue(true),
      onReleased,
    });
    await attachments.upload(new File(['png'], 'architecture.png', { type: 'image/png' }));
    const clientId = attachments.items.value[0]?.clientId as string;

    await attachments.remove(clientId);

    expect(client.release).toHaveBeenCalledWith(
      'wb-1', 'stage-delivery', 5, 'attachment-1',
    );
    expect(attachments.items.value).toEqual([]);
    expect(onReleased).toHaveBeenCalledWith('attachment-1');
    expect(revoke).toHaveBeenCalledWith('blob:preview');
  });

  it('drops a late upload after scope change and releases it against the original scope', async () => {
    const late = deferred<WorkbenchUploadedAttachment>();
    const client = api({ upload: vi.fn().mockReturnValue(late.promise) });
    const workbenchId = ref<string | null>('wb-old');
    const stageInstanceIdentifier = ref<'REQUIREMENT_ANALYSIS' | 'SOLUTION_DESIGN'>('REQUIREMENT_ANALYSIS');
    const generation = ref(1);
    const onAvailable = vi.fn().mockReturnValue(true);
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId,
      stageInstanceIdentifier,
      conversationGeneration: generation,
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue(null), revoke: vi.fn() },
      onAvailable,
      onReleased: vi.fn(),
    });
    const upload = attachments.upload(
      new File(['notes'], 'notes.txt', { type: 'text/plain' }),
    );

    workbenchId.value = 'wb-new';
    stageInstanceIdentifier.value = 'SOLUTION_DESIGN';
    generation.value = 2;
    await nextTick();
    late.resolve(projection('attachment-late'));
    await upload;

    expect(onAvailable).not.toHaveBeenCalled();
    expect(client.release).toHaveBeenCalledWith(
      'wb-old', 'REQUIREMENT_ANALYSIS', 1, 'attachment-late',
    );
    expect(attachments.items.value).toEqual([]);
  });

  it('best-effort releases available files and clears all local state on Stage generation change', async () => {
    const client = api();
    const generation = ref(1);
    const onReleased = vi.fn();
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: generation,
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue(null), revoke: vi.fn() },
      onAvailable: vi.fn().mockReturnValue(true),
      onReleased,
    });
    await attachments.upload(new File(['notes'], 'notes.txt', { type: 'text/plain' }));

    generation.value = 2;
    await vi.waitFor(() => expect(attachments.items.value).toEqual([]));

    expect(client.release).toHaveBeenCalledWith(
      'wb-1', 'REQUIREMENT_ANALYSIS', 1, 'attachment-1',
    );
    expect(onReleased).toHaveBeenCalledWith('attachment-1');
  });

  it('enforces archived, combined-count, extension, empty and size UX bounds before upload', async () => {
    const client = api();
    const archived = ref(true);
    const count = ref(0);
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('REQUIREMENT_ANALYSIS'),
      conversationGeneration: ref(0),
      archived,
      combinedAttachmentCount: count,
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue(null), revoke: vi.fn() },
      onAvailable: vi.fn().mockReturnValue(true),
      onReleased: vi.fn(),
    });

    await attachments.upload(new File(['x'], 'notes.txt', { type: 'text/plain' }));
    archived.value = false;
    count.value = 8;
    await attachments.upload(new File(['x'], 'notes.txt', { type: 'text/plain' }));
    count.value = 0;
    await attachments.upload(new File(['x'], 'script.sh', { type: 'text/plain' }));
    await attachments.upload(new File([], 'empty.txt', { type: 'text/plain' }));
    await attachments.upload(new File(
      [new Uint8Array(10 * 1024 * 1024 + 1)], 'large.txt', { type: 'text/plain' },
    ));

    expect(client.upload).not.toHaveBeenCalled();
    expect(attachments.notice.value).toBe('单个附件不能超过 10 MB。');
  });

  it('forgets submitted uploads without issuing DELETE because the Run transaction owns cleanup', async () => {
    const client = api();
    const revoke = vi.fn();
    const onReleased = vi.fn();
    const attachments = useWorkbenchUploadedAttachments({
      workbenchId: ref('wb-1'),
      stageInstanceIdentifier: ref('stage-delivery'),
      conversationGeneration: ref(1),
      archived: ref(false),
      combinedAttachmentCount: ref(0),
      apiClient: client,
      preview: { create: vi.fn().mockReturnValue('blob:preview'), revoke },
      onAvailable: vi.fn().mockReturnValue(true),
      onReleased,
    });
    await attachments.upload(new File(['png'], 'architecture.png', { type: 'image/png' }));

    attachments.markSubmitted(['attachment-1']);

    expect(attachments.items.value).toEqual([]);
    expect(client.release).not.toHaveBeenCalled();
    expect(onReleased).not.toHaveBeenCalled();
    expect(revoke).toHaveBeenCalledWith('blob:preview');
  });
});
