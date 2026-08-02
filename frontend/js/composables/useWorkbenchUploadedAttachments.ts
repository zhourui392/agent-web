/**
 * Workbench 浏览器附件上传的局部状态、scope isolation 与释放编排。
 *
 * @author alex
 * @since 2026-08-01
 */
import { ref, watch, type Ref } from 'vue';
import {
  WorkbenchUploadedAttachmentApiError,
  createWorkbenchUploadedAttachmentApiClient,
  type WorkbenchUploadedAttachment,
  type WorkbenchUploadedAttachmentApiClient,
} from '../api/workbench-uploaded-attachment.js';
import type { WorkbenchPhase } from '../lib/workbench-state.js';

const MAXIMUM_BYTES = 10 * 1024 * 1024;
const MAXIMUM_COMBINED_ATTACHMENTS = 8;
const ALLOWED_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf',
  'txt', 'log', 'md', 'markdown', 'json', 'xml', 'csv', 'yaml', 'yml', 'toml',
  'java', 'kt', 'kts', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'vue', 'py', 'go', 'rs',
  'c', 'h', 'cc', 'cpp', 'cxx', 'hpp', 'sql', 'properties',
]);

export type WorkbenchUploadItemStatus = 'UPLOADING' | 'AVAILABLE' | 'FAILED' | 'REMOVING';

export interface PendingUploadedWorkbenchRunAttachment {
  type: 'UPLOADED_CONVERSATION';
  attachmentId: string;
  contentHash: string;
  displayName: string;
  mediaType: string;
  size: number;
  previewUrl: string | null;
}

export interface WorkbenchUploadItem {
  clientId: string;
  status: WorkbenchUploadItemStatus;
  displayName: string;
  mediaType: string;
  size: number;
  previewUrl: string | null;
  attachmentId: string | null;
  contentHash: string | null;
  expiresAt: string | null;
  error: string | null;
}

interface UploadScope {
  workbenchId: string;
  phase: WorkbenchPhase;
  conversationGeneration: number;
}

interface UploadRecord {
  file: File;
  scope: UploadScope;
  previewUrl: string | null;
}

interface PreviewAdapter {
  create(file: File): string | null;
  revoke(url: string): void;
}

export interface UseWorkbenchUploadedAttachmentsOptions {
  workbenchId: Ref<string | null>;
  phase: Ref<WorkbenchPhase>;
  conversationGeneration: Ref<number>;
  archived: Ref<boolean>;
  combinedAttachmentCount: Ref<number>;
  apiClient?: WorkbenchUploadedAttachmentApiClient;
  preview?: PreviewAdapter;
  onAvailable(attachment: PendingUploadedWorkbenchRunAttachment): boolean;
  onReleased(attachmentId: string): void;
}

export function useWorkbenchUploadedAttachments(
  options: UseWorkbenchUploadedAttachmentsOptions,
) {
  const apiClient = options.apiClient ?? createWorkbenchUploadedAttachmentApiClient();
  const preview = options.preview ?? browserPreviewAdapter();
  const items = ref<WorkbenchUploadItem[]>([]);
  const notice = ref<string | null>(null);
  const records = new Map<string, UploadRecord>();
  let scopeEpoch = 0;

  function findItem(clientId: string): WorkbenchUploadItem | undefined {
    return items.value.find(item => item.clientId === clientId);
  }

  function replaceItem(clientId: string, replacement: WorkbenchUploadItem): void {
    items.value = items.value.map(item => item.clientId === clientId ? replacement : item);
  }

  function removeItem(clientId: string): void {
    items.value = items.value.filter(item => item.clientId !== clientId);
  }

  async function upload(file: File): Promise<void> {
    notice.value = null;
    const scope = currentScope(options);
    const validation = validateUpload(file, scope, options);
    if (validation) {
      notice.value = validation;
      return;
    }
    const clientId = newClientId();
    const previewUrl = createPreview(preview, file);
    records.set(clientId, { file, scope: scope as UploadScope, previewUrl });
    items.value = [...items.value, {
      clientId,
      status: 'UPLOADING',
      displayName: file.name,
      mediaType: file.type || 'application/octet-stream',
      size: file.size,
      previewUrl,
      attachmentId: null,
      contentHash: null,
      expiresAt: null,
      error: null,
    }];
    await performUpload(clientId, scopeEpoch);
  }

  async function retry(clientId: string): Promise<void> {
    notice.value = null;
    const record = records.get(clientId);
    const item = findItem(clientId);
    const scope = currentScope(options);
    if (!record || !item || item.status !== 'FAILED'
      || !scope || !sameScope(record.scope, scope)) {
      return;
    }
    const validation = validateUpload(record.file, scope, options);
    if (validation) {
      notice.value = validation;
      return;
    }
    replaceItem(clientId, {
      ...item,
      status: 'UPLOADING',
      error: null,
    });
    await performUpload(clientId, scopeEpoch);
  }

  async function performUpload(clientId: string, expectedEpoch: number): Promise<void> {
    const record = records.get(clientId);
    if (!record) return;
    let uploaded: WorkbenchUploadedAttachment;
    try {
      uploaded = await apiClient.upload(
        record.scope.workbenchId,
        record.scope.phase,
        record.scope.conversationGeneration,
        record.file,
      );
    } catch (error) {
      const item = findItem(clientId);
      if (item && expectedEpoch === scopeEpoch) {
        replaceItem(clientId, {
          ...item,
          status: 'FAILED',
          error: uploadErrorMessage(error),
        });
      }
      return;
    }

    const item = findItem(clientId);
    const activeScope = currentScope(options);
    if (!item || expectedEpoch !== scopeEpoch
      || !activeScope || !sameScope(record.scope, activeScope)) {
      await bestEffortRelease(apiClient, record.scope, uploaded.attachmentId);
      records.delete(clientId);
      revokePreview(preview, record.previewUrl);
      removeItem(clientId);
      return;
    }

    const pending: PendingUploadedWorkbenchRunAttachment = {
      type: 'UPLOADED_CONVERSATION',
      attachmentId: uploaded.attachmentId,
      contentHash: uploaded.sha256,
      displayName: uploaded.displayName,
      mediaType: uploaded.mediaType,
      size: uploaded.size,
      previewUrl: record.previewUrl,
    };
    let accepted = false;
    try {
      accepted = options.onAvailable(pending);
    } catch {
      accepted = false;
    }
    if (!accepted) {
      await bestEffortRelease(apiClient, record.scope, uploaded.attachmentId);
      records.delete(clientId);
      revokePreview(preview, record.previewUrl);
      removeItem(clientId);
      notice.value = '每轮仓内文档和浏览器上传附件合计最多 8 个。';
      return;
    }
    replaceItem(clientId, {
      ...item,
      status: 'AVAILABLE',
      displayName: uploaded.displayName,
      mediaType: uploaded.mediaType,
      size: uploaded.size,
      attachmentId: uploaded.attachmentId,
      contentHash: uploaded.sha256,
      expiresAt: uploaded.expiresAt,
      error: null,
    });
  }

  async function remove(clientId: string): Promise<void> {
    notice.value = null;
    const item = findItem(clientId);
    const record = records.get(clientId);
    if (!item || !record) return;
    if (item.status === 'FAILED' || item.status === 'UPLOADING') {
      removeItem(clientId);
      records.delete(clientId);
      revokePreview(preview, record.previewUrl);
      return;
    }
    if (item.status !== 'AVAILABLE' || !item.attachmentId) return;
    replaceItem(clientId, { ...item, status: 'REMOVING', error: null });
    try {
      await apiClient.release(
        record.scope.workbenchId,
        record.scope.phase,
        record.scope.conversationGeneration,
        item.attachmentId,
      );
    } catch (error) {
      if (isAlreadyReleased(error)) {
        options.onReleased(item.attachmentId);
        removeItem(clientId);
        records.delete(clientId);
        revokePreview(preview, record.previewUrl);
        return;
      }
      const current = findItem(clientId);
      if (current) {
        replaceItem(clientId, {
          ...current,
          status: 'AVAILABLE',
          error: '附件取消失败，请重试；其他文本和附件未受影响。',
        });
      }
      return;
    }
    options.onReleased(item.attachmentId);
    removeItem(clientId);
    records.delete(clientId);
    revokePreview(preview, record.previewUrl);
  }

  function markSubmitted(attachmentIds: ReadonlyArray<string>): void {
    const submitted = new Set(attachmentIds);
    for (const item of [...items.value]) {
      if (!item.attachmentId || !submitted.has(item.attachmentId)) continue;
      const record = records.get(item.clientId);
      removeItem(item.clientId);
      records.delete(item.clientId);
      revokePreview(preview, record?.previewUrl ?? item.previewUrl);
    }
  }

  function resetScope(): void {
    scopeEpoch += 1;
    const previous = [...items.value];
    items.value = [];
    notice.value = null;
    for (const item of previous) {
      const record = records.get(item.clientId);
      records.delete(item.clientId);
      revokePreview(preview, record?.previewUrl ?? item.previewUrl);
      if (record && item.status === 'AVAILABLE' && item.attachmentId) {
        options.onReleased(item.attachmentId);
        void bestEffortRelease(apiClient, record.scope, item.attachmentId);
      }
    }
  }

  watch(
    () => scopeFingerprint(options),
    (_next, previous) => {
      if (previous !== undefined) resetScope();
    },
    { flush: 'sync' },
  );

  return {
    items,
    notice,
    upload,
    retry,
    remove,
    markSubmitted,
  };
}

function validateUpload(
  file: File,
  scope: UploadScope | null,
  options: UseWorkbenchUploadedAttachmentsOptions,
): string | null {
  if (options.archived.value) return 'Workbench 已归档，不能上传附件。';
  if (!scope) return '当前 Workbench 阶段身份不可用，请刷新后重试。';
  if (options.combinedAttachmentCount.value >= MAXIMUM_COMBINED_ATTACHMENTS) {
    return '每轮仓内文档和浏览器上传附件合计最多 8 个。';
  }
  if (!file || typeof file.name !== 'string'
    || !Number.isSafeInteger(file.size) || file.size < 1) {
    return '附件不能为空。';
  }
  if (file.size > MAXIMUM_BYTES) return '单个附件不能超过 10 MB。';
  const extension = fileExtension(file.name);
  if (!extension || !ALLOWED_EXTENSIONS.has(extension)) {
    return '附件类型不受支持；请选择图片、PDF、文本、代码或配置文件。';
  }
  return null;
}

function currentScope(options: UseWorkbenchUploadedAttachmentsOptions): UploadScope | null {
  const workbenchId = options.workbenchId.value?.trim();
  const generation = options.conversationGeneration.value;
  if (!workbenchId || !Number.isSafeInteger(generation) || generation < 0) return null;
  return {
    workbenchId,
    phase: options.phase.value,
    conversationGeneration: generation,
  };
}

function scopeFingerprint(options: UseWorkbenchUploadedAttachmentsOptions): string {
  const scope = currentScope(options);
  return JSON.stringify([
    scope?.workbenchId ?? '',
    scope?.phase ?? '',
    scope?.conversationGeneration ?? -1,
    options.archived.value,
  ]);
}

function sameScope(left: UploadScope, right: UploadScope): boolean {
  return left.workbenchId === right.workbenchId
    && left.phase === right.phase
    && left.conversationGeneration === right.conversationGeneration;
}

function fileExtension(name: string): string {
  const normalized = name.trim().toLowerCase();
  const dot = normalized.lastIndexOf('.');
  return dot > 0 && dot < normalized.length - 1 ? normalized.substring(dot + 1) : '';
}

function uploadErrorMessage(error: unknown): string {
  if (!(error instanceof WorkbenchUploadedAttachmentApiError)) {
    return '附件上传失败，请重试；其他文本和附件未受影响。';
  }
  if (error.code === 'WORKBENCH_ATTACHMENT_TOO_LARGE') return '单个附件不能超过 10 MB。';
  if (error.code === 'WORKBENCH_ATTACHMENT_LIMIT_EXCEEDED') {
    return '当前会话可用附件数量已达上限。';
  }
  if (error.code === 'WORKBENCH_ATTACHMENT_UNAVAILABLE') return '附件已过期，请重新选择文件。';
  if (error.code === 'WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE') {
    return '附件存储暂时不可用，请稍后重试。';
  }
  if (error.code === 'WORKBENCH_NOT_FOUND') return 'Workbench 不存在或无权访问。';
  return '附件格式、内容或当前阶段绑定无效。';
}

function isAlreadyReleased(error: unknown): boolean {
  return error instanceof WorkbenchUploadedAttachmentApiError
    && error.code === 'WORKBENCH_ATTACHMENT_UNAVAILABLE';
}

function createPreview(preview: PreviewAdapter, file: File): string | null {
  if (!file.type.toLowerCase().startsWith('image/')) return null;
  try {
    return preview.create(file);
  } catch {
    return null;
  }
}

function revokePreview(preview: PreviewAdapter, url: string | null | undefined): void {
  if (!url) return;
  try {
    preview.revoke(url);
  } catch {
    // Local object URL cleanup is best effort and contains no durable resource.
  }
}

function browserPreviewAdapter(): PreviewAdapter {
  return {
    create(file) {
      return typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null;
    },
    revoke(url) {
      if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
    },
  };
}

async function bestEffortRelease(
  apiClient: WorkbenchUploadedAttachmentApiClient,
  scope: UploadScope,
  attachmentId: string,
): Promise<void> {
  try {
    await apiClient.release(
      scope.workbenchId,
      scope.phase,
      scope.conversationGeneration,
      attachmentId,
    );
  } catch {
    // Server TTL cleanup remains authoritative after the logical scope disappears.
  }
}

function newClientId(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `workbench-upload-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
