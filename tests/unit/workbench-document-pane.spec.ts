/**
 * Workbench Document Pane 的 Pointer Events 几何契约。
 *
 * @author alex
 * @since 2026-08-01
 */
// Vitest 工程与 frontend 各自安装依赖；scope watcher 测试必须复用 composable 的 Vue 实例。
// @ts-expect-error Vue 的直接 ESM 入口没有为相对路径暴露声明文件。
import * as frontendVueRuntime from '../../frontend/node_modules/vue/index.mjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkbenchDocumentApiError } from '../../frontend/js/api/workbench-document.js';
import type {
  WorkbenchDocumentApiClient,
  WorkbenchDocumentContentView,
  WorkbenchDocumentDownload,
} from '../../frontend/js/api/workbench-document.js';
import { useWorkbenchDocumentPane } from '../../frontend/js/composables/useWorkbenchDocumentPane.js';
import {
  restoreWorkbenchDocumentPaneSession,
  workbenchDocumentWidthFromPointer,
} from '../../frontend/js/lib/workbench-document-pane.js';
import {
  createWorkbenchDocumentStateStore,
  createWorkbenchDocumentLayout,
  resizeWorkbenchDocumentLayout,
  type StorageLike,
} from '../../frontend/js/lib/workbench-document-state.js';

const { ref } = frontendVueRuntime as typeof import('vue');

function memoryStorage(): StorageLike {
  const values: Record<string, string> = {};
  return {
    getItem: key => values[key] ?? null,
    setItem: (key, value) => { values[key] = value; },
    removeItem: key => { delete values[key]; },
  };
}

const README = {
  repositoryKey: 'agent-web',
  relativePath: 'README.md',
} as const;

const DIAGRAM = {
  repositoryKey: 'agent-web',
  relativePath: 'docs/diagram.png',
} as const;

function markdownDocument(
  content = '# Workbench',
  contentVersion = 'sha256:v1',
): WorkbenchDocumentContentView {
  return {
    reference: README,
    kind: 'MARKDOWN',
    mediaType: 'text/markdown',
    encoding: 'UTF-8',
    size: content.length,
    lastModified: 1720000000000,
    contentVersion,
    content,
    truncated: false,
    deleted: false,
  };
}

function imageDocument(
  contentVersion = 'sha256:image-v1',
): WorkbenchDocumentContentView {
  return {
    reference: DIAGRAM,
    kind: 'IMAGE',
    mediaType: 'image/png',
    encoding: null,
    size: 9,
    lastModified: 1720000000000,
    contentVersion,
    content: null,
    truncated: false,
    deleted: false,
  };
}

function documentApi(
  overrides: Partial<WorkbenchDocumentApiClient> = {},
): WorkbenchDocumentApiClient {
  return {
    listTree: vi.fn().mockResolvedValue({
      repositoryKey: 'agent-web',
      path: '',
      entries: [{
        name: 'README.md',
        relativePath: 'README.md',
        kind: 'FILE',
        size: 20,
        lastModified: 1720000000000,
      }],
      truncated: false,
    }),
    readContent: vi.fn().mockResolvedValue({
      status: 'LOADED',
      etag: '"sha256:v1"',
      document: markdownDocument(),
    }),
    readInlineImage: vi.fn().mockResolvedValue({
      status: 'LOADED',
      blob: new Blob(['png-bytes'], { type: 'image/png' }),
      mediaType: 'image/png',
      size: 9,
      etag: '"sha256:image-v1"',
    }),
    download: vi.fn().mockResolvedValue({
      blob: new Blob(['# Workbench'], { type: 'text/markdown' }),
      fileName: 'README.md',
      mediaType: 'text/markdown',
      size: 11,
      etag: '"sha256:v1"',
    }),
    ...overrides,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('workbench document pane pointer geometry', () => {
  it('measures the document pane from the right edge of the split container', () => {
    const width = workbenchDocumentWidthFromPointer({
      left: 100,
      width: 1000,
    }, 750);

    expect(width).toBe(35);
  });

  it('lets the document layout policy clamp pointer positions to 25%-70%', () => {
    const initial = createWorkbenchDocumentLayout();
    const draggedBeyondLeft = workbenchDocumentWidthFromPointer({
      left: 100,
      width: 1000,
    }, -500);
    const draggedBeyondRight = workbenchDocumentWidthFromPointer({
      left: 100,
      width: 1000,
    }, 1400);

    expect(resizeWorkbenchDocumentLayout(initial, draggedBeyondLeft as number).widthPercent)
      .toBe(70);
    expect(resizeWorkbenchDocumentLayout(initial, draggedBeyondRight as number).widthPercent)
      .toBe(25);
  });

  it('rejects invalid container geometry and non-finite pointers', () => {
    expect(workbenchDocumentWidthFromPointer({ left: 0, width: 0 }, 100)).toBeNull();
    expect(workbenchDocumentWidthFromPointer({ left: 0, width: -1 }, 100)).toBeNull();
    expect(workbenchDocumentWidthFromPointer({ left: 0, width: 100 }, Number.NaN)).toBeNull();
  });
});

describe('workbench document pane restoration', () => {
  it('restores layouts by stable user, workbench, and phase identity', () => {
    const storage = memoryStorage();
    const userId = 'user-1';
    const workbenchId = 'workbench-1';
    const requirementStore = createWorkbenchDocumentStateStore(storage, {
      userId,
      workbenchId,
      phase: 'REQUIREMENT_ANALYSIS',
    });
    const designStore = createWorkbenchDocumentStateStore(storage, {
      userId,
      workbenchId,
      phase: 'SOLUTION_DESIGN',
    });
    requirementStore.save({
      layout: resizeWorkbenchDocumentLayout(createWorkbenchDocumentLayout(), 43),
      currentDocument: null,
      recentDocuments: [],
    });
    designStore.save({
      layout: resizeWorkbenchDocumentLayout(createWorkbenchDocumentLayout(), 61),
      currentDocument: null,
      recentDocuments: [],
    });

    const requirement = restoreWorkbenchDocumentPaneSession(storage, {
      userId,
      workbenchId,
      phase: 'REQUIREMENT_ANALYSIS',
    }, false);
    const design = restoreWorkbenchDocumentPaneSession(storage, {
      userId,
      workbenchId,
      phase: 'SOLUTION_DESIGN',
    }, false);
    const otherUser = restoreWorkbenchDocumentPaneSession(storage, {
      userId: 'user-2',
      workbenchId,
      phase: 'REQUIREMENT_ANALYSIS',
    }, false);

    expect(requirement.state.layout.widthPercent).toBe(43);
    expect(design.state.layout.widthPercent).toBe(61);
    expect(otherUser.state.layout).toEqual(createWorkbenchDocumentLayout());
  });

  it('cancels pending pointer frames before a context identity changes', () => {
    let pendingFrame: FrameRequestCallback | null = null;
    const cancelAnimationFrame = vi.fn();
    vi.stubGlobal('window', {
      requestAnimationFrame: vi.fn((callback: FrameRequestCallback) => {
        pendingFrame = callback;
        return 17;
      }),
      cancelAnimationFrame,
    });
    vi.stubGlobal('Element', class {});
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      storage: memoryStorage(),
    });
    pane.splitRoot.value = {
      getBoundingClientRect: () => ({ left: 0, width: 1000 }),
    } as HTMLElement;
    pane.beginResize({
      pointerId: 7,
      clientX: 300,
      currentTarget: null,
      preventDefault: vi.fn(),
    } as unknown as PointerEvent);
    expect(pane.dragging.value).toBe(true);
    expect(pendingFrame).not.toBeNull();

    pane.cancelResize();
    (pendingFrame as unknown as FrameRequestCallback)(0);

    expect(cancelAnimationFrame).toHaveBeenCalledWith(17);
    expect(pane.dragging.value).toBe(false);
    expect(pane.layout.value).toEqual(createWorkbenchDocumentLayout());
  });
});

describe('workbench document pane API orchestration', () => {
  it('loads an IMAGE only through the scoped inline endpoint and exposes a generated object URL', async () => {
    const readInlineImage = vi.fn().mockResolvedValue({
      status: 'LOADED',
      blob: new Blob(['png-bytes'], { type: 'image/png' }),
      mediaType: 'image/png',
      size: 9,
      etag: '"sha256:image-v1"',
    });
    const apiClient = documentApi({
      readContent: vi.fn().mockResolvedValue({
        status: 'LOADED',
        etag: '"sha256:image-v1"',
        document: imageDocument(),
      }),
      readInlineImage,
    });
    const inlineImageUrls = {
      create: vi.fn(() => 'blob:https://agent.example/preview-1'),
      revoke: vi.fn(),
    };
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      inlineImageUrls,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());

    await pane.openDocument(DIAGRAM);

    expect(readInlineImage).toHaveBeenCalledWith({
      workbenchId: 'workbench-1',
      ...DIAGRAM,
    });
    expect(inlineImageUrls.create).toHaveBeenCalledWith(expect.objectContaining({
      type: 'image/png',
    }));
    expect(pane.loadedContent.value).toEqual(expect.objectContaining({
      kind: 'IMAGE',
      inlineImageUrl: 'blob:https://agent.example/preview-1',
    }));
  });

  it('keeps the loaded image preview through stale and deleted events without downloading again', async () => {
    const readInlineImage = vi.fn().mockResolvedValue({
      status: 'LOADED',
      blob: new Blob(['png-bytes'], { type: 'image/png' }),
      mediaType: 'image/png',
      size: 9,
      etag: '"sha256:image-v1"',
    });
    const apiClient = documentApi({
      readContent: vi.fn().mockResolvedValue({
        status: 'LOADED',
        etag: '"sha256:image-v1"',
        document: imageDocument(),
      }),
      readInlineImage,
    });
    const inlineImageUrls = {
      create: vi.fn(() => 'blob:https://agent.example/preview-1'),
      revoke: vi.fn(),
    };
    const phase = ref<'IMPLEMENT_TEST' | 'REVIEW_REFACTOR'>('IMPLEMENT_TEST');
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase,
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      inlineImageUrls,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(DIAGRAM);
    const scope = pane.documentEventScope.value;

    expect(pane.receiveDocumentFileChanged(scope, {
      ...DIAGRAM,
      changeType: 'MODIFIED',
      contentVersion: 'sha256:image-v2',
    })).toBe(true);
    expect(pane.loadedContent.value?.inlineImageUrl)
      .toBe('blob:https://agent.example/preview-1');
    expect(readInlineImage).toHaveBeenCalledTimes(1);

    expect(pane.receiveDocumentFileChanged(scope, {
      ...DIAGRAM,
      changeType: 'DELETED',
      contentVersion: 'deleted:image-v3',
    })).toBe(true);
    expect(pane.loadedContent.value?.inlineImageUrl)
      .toBe('blob:https://agent.example/preview-1');
    await pane.downloadCurrent();
    expect(apiClient.download).not.toHaveBeenCalled();
    expect(readInlineImage).toHaveBeenCalledTimes(1);
    expect(inlineImageUrls.revoke).not.toHaveBeenCalled();

    phase.value = 'REVIEW_REFACTOR';
    expect(inlineImageUrls.revoke)
      .toHaveBeenCalledWith('blob:https://agent.example/preview-1');
    expect(pane.loadedContent.value).toBeNull();
  });

  it.each(['owner', 'workbench', 'phase'] as const)(
    'rejects an old delayed image response after the %s scope changes',
    async (dimension) => {
      let resolveImage: ((result: object) => void) | null = null;
      const readInlineImage = vi.fn().mockImplementation(
        () => new Promise(resolve => { resolveImage = resolve; }),
      );
      const apiClient = documentApi({
        readContent: vi.fn().mockResolvedValue({
          status: 'LOADED',
          etag: '"sha256:image-v1"',
          document: imageDocument(),
        }),
        readInlineImage,
      });
      const inlineImageUrls = {
        create: vi.fn(() => 'blob:https://agent.example/old-preview'),
        revoke: vi.fn(),
      };
      const userId = ref('user-1');
      const workbenchId = ref<string | null>('workbench-1');
      const phase = ref<'IMPLEMENT_TEST' | 'REVIEW_REFACTOR'>('IMPLEMENT_TEST');
      const pane = useWorkbenchDocumentPane({
        userId,
        workbenchId,
        phase,
        repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
        apiClient,
        inlineImageUrls,
        storage: memoryStorage(),
      });
      await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
      const opening = pane.openDocument(DIAGRAM);
      await vi.waitFor(() => expect(readInlineImage).toHaveBeenCalledOnce());

      if (dimension === 'owner') userId.value = 'user-2';
      if (dimension === 'workbench') workbenchId.value = 'workbench-2';
      if (dimension === 'phase') phase.value = 'REVIEW_REFACTOR';
      await vi.waitFor(() => expect(pane.loadedContent.value).toBeNull());
      (resolveImage as unknown as (result: object) => void)({
        status: 'LOADED',
        blob: new Blob(['old-png'], { type: 'image/png' }),
        mediaType: 'image/png',
        size: 7,
        etag: '"sha256:old"',
      });
      await opening;

      expect(inlineImageUrls.create).not.toHaveBeenCalled();
      expect(pane.loadedContent.value).toBeNull();
    },
  );

  it('keeps an existing preview when refresh discovers deletion and never refetches image bytes', async () => {
    const readContent = vi.fn()
      .mockResolvedValueOnce({
        status: 'LOADED',
        etag: '"sha256:image-v1"',
        document: imageDocument(),
      })
      .mockResolvedValueOnce({ status: 'DELETED' });
    const readInlineImage = vi.fn().mockResolvedValue({
      status: 'LOADED',
      blob: new Blob(['png-bytes'], { type: 'image/png' }),
      mediaType: 'image/png',
      size: 9,
      etag: '"sha256:image-v1"',
    });
    const apiClient = documentApi({ readContent, readInlineImage });
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      inlineImageUrls: {
        create: () => 'blob:https://agent.example/preview-1',
        revoke: vi.fn(),
      },
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(DIAGRAM);

    await pane.refreshDocument();

    expect(readInlineImage).toHaveBeenCalledTimes(1);
    expect(pane.loadedContent.value?.inlineImageUrl)
      .toBe('blob:https://agent.example/preview-1');
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      deleted: true,
      downloadEnabled: false,
    }));
  });

  it('revalidates a stale image with its image ETag and revokes the replaced object URL', async () => {
    const readContent = vi.fn()
      .mockResolvedValueOnce({
        status: 'LOADED',
        etag: '"content-v1"',
        document: imageDocument('sha256:image-v1'),
      })
      .mockResolvedValueOnce({
        status: 'LOADED',
        etag: '"content-v2"',
        document: imageDocument('sha256:image-v2'),
      });
    const readInlineImage = vi.fn()
      .mockResolvedValueOnce({
        status: 'LOADED',
        blob: new Blob(['png-v1'], { type: 'image/png' }),
        mediaType: 'image/png',
        size: 6,
        etag: '"inline-v1"',
      })
      .mockResolvedValueOnce({
        status: 'LOADED',
        blob: new Blob(['png-v2'], { type: 'image/png' }),
        mediaType: 'image/png',
        size: 6,
        etag: '"inline-v2"',
      });
    const inlineImageUrls = {
      create: vi.fn()
        .mockReturnValueOnce('blob:https://agent.example/preview-v1')
        .mockReturnValueOnce('blob:https://agent.example/preview-v2'),
      revoke: vi.fn(),
    };
    const apiClient = documentApi({ readContent, readInlineImage });
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      inlineImageUrls,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(DIAGRAM);
    pane.receiveDocumentFileChanged(pane.documentEventScope.value, {
      ...DIAGRAM,
      changeType: 'MODIFIED',
      contentVersion: 'sha256:image-v2',
    });

    await pane.refreshDocument();

    expect(readContent).toHaveBeenLastCalledWith({
      workbenchId: 'workbench-1',
      ...DIAGRAM,
    }, '"content-v1"');
    expect(readInlineImage).toHaveBeenLastCalledWith({
      workbenchId: 'workbench-1',
      ...DIAGRAM,
    }, '"inline-v1"');
    expect(pane.loadedContent.value?.inlineImageUrl)
      .toBe('blob:https://agent.example/preview-v2');
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      contentVersion: 'sha256:image-v2',
      stale: false,
      deleted: false,
      downloadEnabled: true,
    }));
    expect(inlineImageUrls.revoke)
      .toHaveBeenCalledWith('blob:https://agent.example/preview-v1');
  });

  it('loads the primary repository tree and opens scoped content without leaving Workbench APIs', async () => {
    const apiClient = documentApi();
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      repositories: ref([
        { repositoryKey: 'shared-lib', primary: false },
        { repositoryKey: 'agent-web', primary: true },
      ]),
      apiClient,
      storage: memoryStorage(),
    });

    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalledWith({
      workbenchId: 'workbench-1',
      repositoryKey: 'agent-web',
      relativePath: '',
      limit: 1000,
    }));
    expect(pane.selectedRepositoryKey.value).toBe('agent-web');
    expect(pane.treeEntries.value).toEqual([
      expect.objectContaining({ relativePath: 'README.md', kind: 'FILE' }),
    ]);

    await pane.openDocument(README);

    expect(apiClient.readContent).toHaveBeenCalledWith({
      workbenchId: 'workbench-1',
      ...README,
    });
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      reference: README,
      content: '# Workbench',
      contentVersion: 'sha256:v1',
      stale: false,
      deleted: false,
    }));
    expect(pane.loadedContent.value).toEqual(markdownDocument());
    expect(pane.recentDocuments.value).toEqual([README]);
  });

  it('keeps loaded content for 304 and deletion, and disables deleted downloads', async () => {
    const readContent = vi.fn()
      .mockResolvedValueOnce({
        status: 'LOADED',
        etag: '"sha256:v1"',
        document: markdownDocument(),
      })
      .mockResolvedValueOnce({ status: 'NOT_MODIFIED', etag: '"sha256:v1"' })
      .mockResolvedValueOnce({ status: 'DELETED' });
    const apiClient = documentApi({ readContent });
    const saveDownload = vi.fn<(download: WorkbenchDocumentDownload) => void>();
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('REQUIREMENT_ANALYSIS'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      saveDownload,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(README);

    await pane.refreshDocument();
    expect(readContent).toHaveBeenLastCalledWith({
      workbenchId: 'workbench-1',
      ...README,
    }, '"sha256:v1"');
    expect(pane.currentDocument.value?.content).toBe('# Workbench');

    await pane.refreshDocument();
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      content: '# Workbench',
      deleted: true,
      downloadEnabled: false,
    }));
    expect(pane.loadedContent.value?.content).toBe('# Workbench');

    await pane.downloadCurrent();
    expect(apiClient.download).not.toHaveBeenCalled();
    expect(saveDownload).not.toHaveBeenCalled();
  });

  it('downloads the current scoped reference through an injected browser saver', async () => {
    const apiClient = documentApi();
    const saveDownload = vi.fn<(download: WorkbenchDocumentDownload) => void>();
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('SOLUTION_DESIGN'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      saveDownload,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(README);

    await pane.downloadCurrent();

    expect(apiClient.download).toHaveBeenCalledWith({
      workbenchId: 'workbench-1',
      ...README,
    });
    expect(saveDownload).toHaveBeenCalledWith(expect.objectContaining({
      fileName: 'README.md',
      mediaType: 'text/markdown',
    }));
  });

  it('fails closed for mismatched injected tree responses and stale identity responses', async () => {
    let resolveFirst: ((value: {
      repositoryKey: string;
      path: string;
      entries: never[];
      truncated: boolean;
    }) => void) | null = null;
    const listTree = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({
        repositoryKey: 'foreign-lib',
        path: '',
        entries: [],
        truncated: false,
      });
    const apiClient = documentApi({ listTree });
    const workbenchId = ref<string | null>('workbench-1');
    const repositories = ref([{ repositoryKey: 'agent-web', primary: true }]);
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId,
      phase: ref('IMPLEMENT_TEST'),
      repositories,
      apiClient,
      storage: memoryStorage(),
    });
    expect(listTree).toHaveBeenCalledTimes(1);

    repositories.value = [{ repositoryKey: 'shared-lib', primary: true }];
    await vi.waitFor(() => expect(listTree).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(pane.treeLoading.value).toBe(false));
    (resolveFirst as unknown as (value: object) => void)({
      repositoryKey: 'agent-web',
      path: '',
      entries: [],
      truncated: false,
    });
    await vi.waitFor(() => expect(pane.treeLoading.value).toBe(false));

    expect(pane.selectedRepositoryKey.value).toBe('shared-lib');
    expect(pane.treeEntries.value).toEqual([]);
    expect(pane.documentError.value).toBe('文档响应与当前 Workbench 仓库范围不一致');
  });

  it('tells the user how to recover when a frozen repository moved or disappeared', async () => {
    const apiClient = documentApi({
      listTree: vi.fn().mockRejectedValue(new WorkbenchDocumentApiError(
        409,
        'WORKSPACE_TOPOLOGY_CHANGED',
      )),
    });
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      storage: memoryStorage(),
    });

    await vi.waitFor(() => expect(pane.treeLoading.value).toBe(false));

    expect(pane.documentError.value)
      .toBe('仓库目录已移动或不存在；请恢复原目录，或创建新的 Workbench。');
  });

  it('receives exact FILE_CHANGED events without replacing loaded content or scroll', async () => {
    const apiClient = documentApi();
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId: ref<string | null>('workbench-1'),
      phase: ref('IMPLEMENT_TEST'),
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(README);
    pane.updateDocumentScrollTop(240);
    const scope = pane.documentEventScope.value;
    expect(scope).not.toBeNull();

    expect(pane.receiveDocumentFileChanged(scope, {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      changeType: 'MODIFIED',
      contentVersion: 'sha256:v2',
    })).toBe(true);

    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      content: '# Workbench',
      contentVersion: 'sha256:v1',
      scrollTop: 240,
      stale: true,
      deleted: false,
    }));
    expect(pane.loadedContent.value).toEqual(markdownDocument());

    expect(pane.receiveDocumentFileChanged(scope, {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      changeType: 'DELETED',
      contentVersion: 'deleted:v3',
    })).toBe(true);
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      content: '# Workbench',
      contentVersion: 'sha256:v1',
      scrollTop: 240,
      stale: false,
      deleted: true,
      downloadEnabled: false,
    }));
    expect(pane.loadedContent.value).toEqual(markdownDocument());
  });

  it('rejects non-exact paths and scope tokens captured before a phase change', async () => {
    const apiClient = documentApi();
    const phase = ref<'IMPLEMENT_TEST' | 'REVIEW_REFACTOR'>('IMPLEMENT_TEST');
    const workbenchId = ref<string | null>('workbench-1');
    const pane = useWorkbenchDocumentPane({
      userId: ref('user-1'),
      workbenchId,
      phase,
      repositories: ref([{ repositoryKey: 'agent-web', primary: true }]),
      apiClient,
      storage: memoryStorage(),
    });
    await vi.waitFor(() => expect(apiClient.listTree).toHaveBeenCalled());
    await pane.openDocument(README);
    const oldScope = pane.documentEventScope.value;
    expect(oldScope).not.toBeNull();

    for (const mismatch of [
      { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      { repositoryKey: 'agent-web', relativePath: 'docs/README.md' },
      { repositoryKey: 'agent-web', relativePath: 'readme.md' },
    ]) {
      expect(pane.receiveDocumentFileChanged(oldScope, {
        ...mismatch,
        changeType: 'MODIFIED',
        contentVersion: 'sha256:v2',
      })).toBe(false);
    }
    expect(pane.currentDocument.value?.stale).toBe(false);

    phase.value = 'REVIEW_REFACTOR';
    await vi.waitFor(() => expect(pane.documentEventScope.value).not.toBe(oldScope));
    await pane.openDocument(README);

    expect(pane.receiveDocumentFileChanged(oldScope, {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      changeType: 'DELETED',
      contentVersion: 'deleted:v3',
    })).toBe(false);
    expect(pane.currentDocument.value).toEqual(expect.objectContaining({
      stale: false,
      deleted: false,
      content: '# Workbench',
    }));

    const phaseScope = pane.documentEventScope.value;
    expect(phaseScope).not.toBeNull();
    workbenchId.value = 'workbench-2';
    await vi.waitFor(() => expect(pane.documentEventScope.value).not.toBe(phaseScope));
    await pane.openDocument(README);

    expect(pane.receiveDocumentFileChanged(phaseScope, {
      repositoryKey: 'agent-web',
      relativePath: 'README.md',
      changeType: 'MODIFIED',
      contentVersion: 'sha256:v4',
    })).toBe(false);
    expect(pane.currentDocument.value?.stale).toBe(false);
  });
});
