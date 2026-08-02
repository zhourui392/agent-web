/**
 * Workbench Document Viewer 的布局、持久化与 stale 状态契约。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from 'vitest';
import {
  WORKBENCH_DOCUMENT_LIMITS,
  applyWorkbenchDocumentFileChanged,
  applyWorkbenchDocumentFileEvent,
  applyWorkbenchDocumentNotModified,
  authorizedDocumentReference,
  collapseWorkbenchDocumentLayout,
  conversationUsesFullWidth,
  createLoadedWorkbenchDocument,
  createWorkbenchDocumentLayout,
  createWorkbenchDocumentStateStore,
  enterWorkbenchMobileDrawer,
  extractAuthorizedAgentDocumentReferences,
  exitWorkbenchMobileDrawer,
  maximizeWorkbenchDocumentLayout,
  normalizeDocumentReference,
  groupDocumentReferencesByRepository,
  refreshWorkbenchDocument,
  rememberRecentDocument,
  resetWorkbenchDocumentLayout,
  resizeWorkbenchDocumentLayout,
  restoreWorkbenchDocumentLayout,
  switchWorkbenchDocument,
  workbenchDocumentLayoutStorageKey,
  workbenchDocumentsStorageKey,
  markWorkbenchDocumentDeleted,
  type DocumentReference,
  type WorkbenchDocumentStorageIdentity,
} from '../../frontend/js/lib/workbench-document-state.js';

type MemoryStorage = {
  values: Record<string, string>;
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
};

const IDENTITY: WorkbenchDocumentStorageIdentity = {
  userId: 'user/a',
  workbenchId: 'workbench:1',
  phase: 'SOLUTION_DESIGN',
};

const README: DocumentReference = {
  repositoryKey: 'agent-web',
  relativePath: 'README.md',
};

function memoryStorage(): MemoryStorage {
  const values: Record<string, string> = {};
  return {
    values,
    getItem: key => values[key] ?? null,
    setItem: (key, value) => { values[key] = value; },
    removeItem: key => { delete values[key]; },
  };
}

describe('workbench document layout', () => {
  it('uses a normal 35% pane and clamps resizing to the 25%-70% contract', () => {
    const initial = createWorkbenchDocumentLayout();

    expect(initial).toEqual({
      mode: 'NORMAL',
      widthPercent: 35,
      restoreWidthPercent: 35,
    });
    expect(resizeWorkbenchDocumentLayout(initial, 10).widthPercent).toBe(25);
    expect(resizeWorkbenchDocumentLayout(initial, 90).widthPercent).toBe(70);
    expect(resizeWorkbenchDocumentLayout(initial, 46).widthPercent).toBe(46);
  });

  it('gives the conversation full width when collapsed and restores maximized panes', () => {
    const resized = resizeWorkbenchDocumentLayout(createWorkbenchDocumentLayout(), 48);
    const collapsed = collapseWorkbenchDocumentLayout(resized);

    expect(collapsed.mode).toBe('COLLAPSED');
    expect(conversationUsesFullWidth(collapsed)).toBe(true);
    expect(restoreWorkbenchDocumentLayout(collapsed)).toEqual({
      mode: 'NORMAL',
      widthPercent: 48,
      restoreWidthPercent: 48,
    });

    const maximized = maximizeWorkbenchDocumentLayout(resized);
    expect(maximized).toEqual({
      mode: 'MAXIMIZED',
      widthPercent: 48,
      restoreWidthPercent: 48,
    });
    expect(restoreWorkbenchDocumentLayout(maximized)).toEqual(resized);
    expect(resetWorkbenchDocumentLayout(maximized)).toEqual(createWorkbenchDocumentLayout());
  });

  it('uses a mobile drawer without corrupting the previous desktop layout', () => {
    const desktop = maximizeWorkbenchDocumentLayout(
      resizeWorkbenchDocumentLayout(createWorkbenchDocumentLayout(), 51),
    );
    const mobile = enterWorkbenchMobileDrawer(desktop);

    expect(mobile.mode).toBe('MOBILE_DRAWER');
    expect(mobile.desktopLayout).toEqual(desktop);
    expect(resizeWorkbenchDocumentLayout(mobile, 30)).toBe(mobile);
    expect(exitWorkbenchMobileDrawer(mobile)).toEqual(desktop);
  });
});

describe('document reference and recent documents', () => {
  it('accepts a structured reference only when its repository is in the frozen scope', () => {
    expect(authorizedDocumentReference(README, ['agent-web', 'shared-lib']))
      .toEqual(README);
    expect(authorizedDocumentReference({
      repositoryKey: 'unselected',
      relativePath: 'README.md',
    }, ['agent-web', 'shared-lib'])).toBeNull();
    expect(authorizedDocumentReference({
      repositoryKey: 'agent-web',
      relativePath: '/etc/passwd',
    }, ['agent-web'])).toBeNull();
  });

  it('extracts only deduplicated scoped repository paths from explicit backtick references', () => {
    expect(extractAuthorizedAgentDocumentReferences([
      '请查看 `agent-web/docs/design.md`，并对照 `shared-lib/src/main.ts`。',
      '重复引用 `agent-web/docs/design.md`。',
      '普通文本 agent-web/docs/plain.md 不提升为入口。',
      '无仓库前缀的 `README.md`、越权的 `foreign/README.md`、',
      '绝对路径 `/etc/passwd` 和遍历路径 `agent-web/../secret.txt` 均忽略。',
    ].join('\n'), ['agent-web', 'shared-lib'])).toEqual([
      { repositoryKey: 'agent-web', relativePath: 'docs/design.md' },
      { repositoryKey: 'shared-lib', relativePath: 'src/main.ts' },
    ]);
  });

  it('fails closed when a backtick path matches more than one selected repository key', () => {
    expect(extractAuthorizedAgentDocumentReferences(
      '歧义引用 `services/api/docs/design.md`，明确引用 `shared-lib/README.md`。',
      ['services', 'services/api', 'shared-lib'],
    )).toEqual([
      { repositoryKey: 'shared-lib', relativePath: 'README.md' },
    ]);
  });

  it('bounds the number of agent-text document candidates', () => {
    const content = Array.from(
      { length: WORKBENCH_DOCUMENT_LIMITS.agentTextDocumentReferences + 10 },
      (_, index) => `\`agent-web/docs/${index}.md\``,
    ).join(' ');

    const references = extractAuthorizedAgentDocumentReferences(content, ['agent-web']);

    expect(references).toHaveLength(WORKBENCH_DOCUMENT_LIMITS.agentTextDocumentReferences);
    expect(references[references.length - 1]).toEqual({
      repositoryKey: 'agent-web',
      relativePath: `docs/${WORKBENCH_DOCUMENT_LIMITS.agentTextDocumentReferences - 1}.md`,
    });
  });

  it('groups recent references by repository without merging same-named files', () => {
    expect(groupDocumentReferencesByRepository([
      { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      { repositoryKey: 'agent-web', relativePath: 'docs/design.md' },
      { repositoryKey: 'shared-lib', relativePath: 'src/App.java' },
      { repositoryKey: 'agent-web', relativePath: 'README.md' },
    ])).toEqual([
      {
        repositoryKey: 'shared-lib',
        documents: [
          { repositoryKey: 'shared-lib', relativePath: 'README.md' },
          { repositoryKey: 'shared-lib', relativePath: 'src/App.java' },
        ],
      },
      {
        repositoryKey: 'agent-web',
        documents: [
          { repositoryKey: 'agent-web', relativePath: 'docs/design.md' },
          { repositoryKey: 'agent-web', relativePath: 'README.md' },
        ],
      },
    ]);
  });

  it('accepts only repository-scoped POSIX relative document paths', () => {
    expect(normalizeDocumentReference(README)).toEqual(README);
    expect(normalizeDocumentReference({
      repositoryKey: 'services/api',
      relativePath: 'docs/design/README.md',
    })).toEqual({
      repositoryKey: 'services/api',
      relativePath: 'docs/design/README.md',
    });

    const invalidPaths = [
      '/etc/passwd',
      'C:/Windows/system.ini',
      'src\\main\\App.java',
      '../secret.txt',
      'src/../secret.txt',
      'src/./App.java',
      'src//App.java',
      'src/\u0000secret.txt',
      '',
    ];
    for (const relativePath of invalidPaths) {
      expect(normalizeDocumentReference({
        repositoryKey: 'agent-web',
        relativePath,
      })).toBeNull();
    }
    const invalidRepositoryKeys = [
      '/absolute/repository',
      'C:/workspace/repository',
      'services\\api',
      '../foreign-repository',
      'services/../api',
      'services/./api',
      'services//api',
      'services/\u0000api',
      '',
    ];
    for (const repositoryKey of invalidRepositoryKeys) {
      expect(normalizeDocumentReference({
        repositoryKey,
        relativePath: 'README.md',
      })).toBeNull();
    }
  });

  it('keeps at most 20 recent references and distinguishes same paths across repositories', () => {
    let recent: ReadonlyArray<DocumentReference> = [];
    recent = rememberRecentDocument(recent, README);
    recent = rememberRecentDocument(recent, {
      repositoryKey: 'shared-lib',
      relativePath: 'README.md',
    });
    for (let index = 0; index < WORKBENCH_DOCUMENT_LIMITS.recentDocuments + 5; index++) {
      recent = rememberRecentDocument(recent, {
        repositoryKey: 'agent-web',
        relativePath: `docs/${index}.md`,
      });
    }

    expect(recent).toHaveLength(WORKBENCH_DOCUMENT_LIMITS.recentDocuments);
    expect(recent[0]).toEqual({
      repositoryKey: 'agent-web',
      relativePath: `docs/${WORKBENCH_DOCUMENT_LIMITS.recentDocuments + 4}.md`,
    });

    recent = rememberRecentDocument(recent, README);
    recent = rememberRecentDocument(recent, {
      repositoryKey: 'shared-lib',
      relativePath: 'README.md',
    });
    expect(recent.filter(item => item.relativePath === 'README.md')).toEqual([
      { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      README,
    ]);
  });
});

describe('workbench document local restoration', () => {
  it('strictly isolates both storage keys by authenticated user, workbench, and phase', () => {
    const baseLayout = workbenchDocumentLayoutStorageKey(IDENTITY);
    const baseDocuments = workbenchDocumentsStorageKey(IDENTITY);
    const variants: WorkbenchDocumentStorageIdentity[] = [
      { ...IDENTITY, userId: 'user/b' },
      { ...IDENTITY, workbenchId: 'workbench:2' },
      { ...IDENTITY, phase: 'IMPLEMENT_TEST' },
    ];

    for (const variant of variants) {
      expect(workbenchDocumentLayoutStorageKey(variant)).not.toBe(baseLayout);
      expect(workbenchDocumentsStorageKey(variant)).not.toBe(baseDocuments);
    }
    expect(() => workbenchDocumentLayoutStorageKey({
      ...IDENTITY,
      userId: '',
    })).toThrow(/authenticated user/i);
  });

  it('persists only layout, references, scroll position, and bounded recent references', () => {
    const storage = memoryStorage();
    const store = createWorkbenchDocumentStateStore(storage, IDENTITY);
    const currentDocument = createLoadedWorkbenchDocument({
      reference: README,
      content: 'TOP-SECRET-DOCUMENT-BODY',
      contentVersion: 'sha256:secret-version',
      scrollTop: 321,
    });

    store.save({
      layout: resizeWorkbenchDocumentLayout(createWorkbenchDocumentLayout(), 44),
      currentDocument,
      recentDocuments: [README],
    });

    expect(store.load()).toEqual({
      layout: {
        mode: 'NORMAL',
        widthPercent: 44,
        restoreWidthPercent: 44,
      },
      currentDocument: expect.objectContaining({
        reference: README,
        content: null,
        contentVersion: null,
        scrollTop: 321,
      }),
      recentDocuments: [README],
    });
    const raw = Object.values(storage.values).join('\n');
    expect(raw).not.toContain('TOP-SECRET-DOCUMENT-BODY');
    expect(raw).not.toContain('sha256:secret-version');
    expect(raw).not.toContain('absoluteRoot');
    expect(raw).not.toContain('/home/ubuntu');
  });

  it('fails safe for malformed, mismatched, or unsafe persisted state', () => {
    const storage = memoryStorage();
    const layoutKey = workbenchDocumentLayoutStorageKey(IDENTITY);
    const documentsKey = workbenchDocumentsStorageKey(IDENTITY);
    const store = createWorkbenchDocumentStateStore(storage, IDENTITY);

    storage.values[layoutKey] = '{broken-json';
    storage.values[documentsKey] = JSON.stringify({
      schemaVersion: 'workbench-documents@1',
      ...IDENTITY,
      userId: 'foreign-user',
      current: { reference: README, scrollTop: 99 },
      recentDocuments: [README],
    });
    expect(store.load()).toEqual({
      layout: createWorkbenchDocumentLayout(),
      currentDocument: null,
      recentDocuments: [],
    });
    expect(storage.values[layoutKey]).toBeUndefined();
    expect(storage.values[documentsKey]).toBeUndefined();

    storage.values[documentsKey] = JSON.stringify({
      schemaVersion: 'workbench-documents@1',
      ...IDENTITY,
      current: {
        reference: { repositoryKey: 'agent-web', relativePath: '../../secret' },
        scrollTop: -1,
      },
      recentDocuments: [],
    });
    expect(store.load().currentDocument).toBeNull();
    expect(storage.values[documentsKey]).toBeUndefined();
  });
});

describe('workbench document stale reducer', () => {
  it('represents binary metadata without inventing text content', () => {
    const binary = createLoadedWorkbenchDocument({
      reference: {
        repositoryKey: 'agent-web',
        relativePath: 'target/report.bin',
      },
      content: null,
      contentVersion: 'sha256:binary-v1',
    });

    expect(binary.content).toBeNull();
    expect(refreshWorkbenchDocument(binary, {
      reference: binary.reference,
      content: null,
      contentVersion: 'sha256:binary-v2',
    })).toEqual({
      ...binary,
      contentVersion: 'sha256:binary-v2',
    });
  });

  it('marks only the same changed reference stale without replacing content or scroll', () => {
    const initial = createLoadedWorkbenchDocument({
      reference: README,
      content: '# Loaded body',
      contentVersion: 'v1',
      scrollTop: 240,
    });

    expect(applyWorkbenchDocumentFileChanged(initial, {
      reference: { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      contentVersion: 'v2',
    })).toBe(initial);
    expect(applyWorkbenchDocumentFileChanged(initial, {
      reference: README,
      contentVersion: 'v1',
    })).toBe(initial);

    const stale = applyWorkbenchDocumentFileChanged(initial, {
      reference: README,
      contentVersion: 'v2',
    });
    expect(stale).toEqual({
      ...initial,
      stale: true,
    });
    expect(stale.content).toBe('# Loaded body');
    expect(stale.contentVersion).toBe('v1');
    expect(stale.scrollTop).toBe(240);
  });

  it('reduces exact FILE_CHANGED modifications and deletions without replacing loaded data', () => {
    const initial = createLoadedWorkbenchDocument({
      reference: README,
      content: '# Loaded body',
      contentVersion: 'v1',
      scrollTop: 240,
    });

    const modified = applyWorkbenchDocumentFileEvent(initial, {
      reference: README,
      changeType: 'MODIFIED',
      contentVersion: 'v2',
    });
    expect(modified).toEqual({ ...initial, stale: true });
    expect(modified.content).toBe(initial.content);
    expect(modified.contentVersion).toBe(initial.contentVersion);
    expect(modified.scrollTop).toBe(initial.scrollTop);

    const deleted = applyWorkbenchDocumentFileEvent(initial, {
      reference: README,
      changeType: 'DELETED',
      contentVersion: 'deleted:v2',
    });
    expect(deleted).toEqual({
      ...initial,
      deleted: true,
      downloadEnabled: false,
    });
    expect(deleted.content).toBe(initial.content);
    expect(deleted.contentVersion).toBe(initial.contentVersion);
    expect(deleted.scrollTop).toBe(initial.scrollTop);

    const recreated = applyWorkbenchDocumentFileEvent(deleted, {
      reference: README,
      changeType: 'MODIFIED',
      contentVersion: 'v3',
    });
    expect(recreated).toEqual({
      ...deleted,
      stale: true,
      deleted: false,
    });
    expect(recreated.downloadEnabled).toBe(false);
    expect(recreated.content).toBe(initial.content);
  });

  it('ignores FILE_CHANGED for a different repository or relative path', () => {
    const initial = createLoadedWorkbenchDocument({
      reference: README,
      content: '# Loaded body',
      contentVersion: 'v1',
      scrollTop: 240,
    });
    const mismatches = [
      { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      { repositoryKey: 'agent-web', relativePath: 'docs/README.md' },
      { repositoryKey: 'agent-web', relativePath: 'readme.md' },
    ];

    for (const reference of mismatches) {
      expect(applyWorkbenchDocumentFileEvent(initial, {
        reference,
        changeType: 'DELETED',
        contentVersion: 'deleted:v2',
      })).toBe(initial);
    }
  });

  it('handles 304, explicit refresh, and 404 without disrupting reading state', () => {
    const initial = createLoadedWorkbenchDocument({
      reference: README,
      content: '# Version 1',
      contentVersion: 'v1',
      scrollTop: 240,
    });
    const stale = applyWorkbenchDocumentFileChanged(initial, {
      reference: README,
      contentVersion: 'v2',
    });

    const notModified = applyWorkbenchDocumentNotModified(stale);
    expect(notModified).toEqual({ ...initial, stale: false });

    const refreshedAtSameScroll = refreshWorkbenchDocument(stale, {
      reference: README,
      content: '# Version 2',
      contentVersion: 'v2',
    });
    expect(refreshedAtSameScroll).toEqual({
      ...initial,
      content: '# Version 2',
      contentVersion: 'v2',
      stale: false,
    });
    expect(refreshWorkbenchDocument(stale, {
      reference: README,
      content: '# Version 2',
      contentVersion: 'v2',
    }, 12).scrollTop).toBe(12);

    const deleted = markWorkbenchDocumentDeleted(stale);
    expect(deleted).toEqual({
      ...stale,
      stale: false,
      deleted: true,
      downloadEnabled: false,
    });
    expect(deleted.content).toBe('# Version 1');
    expect(deleted.scrollTop).toBe(240);
  });

  it('may discard the old body when switching to another repository-scoped document', () => {
    const loaded = createLoadedWorkbenchDocument({
      reference: README,
      content: '# Loaded body',
      contentVersion: 'v1',
      scrollTop: 240,
    });
    const switched = switchWorkbenchDocument(loaded, {
      repositoryKey: 'shared-lib',
      relativePath: 'README.md',
    });

    expect(switched).toEqual({
      reference: { repositoryKey: 'shared-lib', relativePath: 'README.md' },
      content: null,
      contentVersion: null,
      scrollTop: 0,
      stale: false,
      deleted: false,
      downloadEnabled: true,
    });
  });
});
