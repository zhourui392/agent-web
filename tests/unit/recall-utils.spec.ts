import { describe, it, expect } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const recall = requireCjs('../../src/main/resources/static/js/admin/recall-utils.js') as {
  buildRecallQuery: (filters: Record<string, unknown>, page?: number, size?: number) => string;
  pct: (value: number | null | undefined) => string;
  score: (value: number | null | undefined) => string;
  millis: (value: number | null | undefined) => string;
  epochTime: (value: number | null | undefined) => string;
  statusLabel: (status: string | null | undefined) => string;
  statusTagType: (status: string | null | undefined) => string;
  compactText: (value: string | null | undefined, max: number) => string;
  bucketDisplayKey: (group: string | null | undefined, key: string | null | undefined) => string;
};

describe('buildRecallQuery', () => {
  it('keeps page and size then appends non-empty filters', () => {
    const out = recall.buildRecallQuery({
      status: 'HIT',
      sessionId: 'sess-1',
      embeddingModel: 'qwen',
      env: 'test',
      sourceType: 'CHAT',
      tier: 'EXPLORATORY',
      from: 1000,
      to: 2000,
    }, 2, 50);

    expect(out).toBe('page=2&size=50&status=HIT&sessionId=sess-1'
      + '&embeddingModel=qwen&env=test&sourceType=CHAT&tier=EXPLORATORY&from=1000&to=2000');
  });

  it('drops blank filters but preserves zero numeric range', () => {
    const out = recall.buildRecallQuery({
      status: '',
      sessionId: '  ',
      from: 0,
      to: null,
    }, 1, 20);

    expect(out).toBe('page=1&size=20&from=0');
  });

  it('omits pagination when callers do not pass pagination', () => {
    expect(recall.buildRecallQuery({ from: 1000, status: 'HIT' }))
      .toBe('status=HIT&from=1000');
  });

  it('url-encodes values', () => {
    expect(recall.buildRecallQuery({ sessionId: 's 1/2' }, 1, 20))
      .toBe('page=1&size=20&sessionId=s+1%2F2');
  });
});

describe('recall display helpers', () => {
  it('formats ratio, score and latency with stable placeholders', () => {
    expect(recall.pct(0.1234)).toBe('12.3%');
    expect(recall.pct(null)).toBe('-');
    expect(recall.score(0.87654)).toBe('0.877');
    expect(recall.score(undefined)).toBe('-');
    expect(recall.millis(88)).toBe('88ms');
    expect(recall.millis(null)).toBe('-');
  });

  it('labels status and tag type', () => {
    expect(recall.statusLabel('HIT')).toBe('命中');
    expect(recall.statusLabel('NO_HIT')).toBe('0 命中');
    expect(recall.statusLabel('ERROR')).toBe('异常');
    expect(recall.statusTagType('ERROR')).toBe('danger');
    expect(recall.statusTagType('PENDING')).toBe('info');
  });

  it('formats epoch millis and compacts long text', () => {
    expect(recall.epochTime(0)).toContain('1970');
    expect(recall.epochTime(null)).toBe('-');
    expect(recall.compactText('abcdef', 4)).toBe('abcd...');
    expect(recall.compactText(null, 4)).toBe('');
  });

  it('formats bucket display keys with placeholders', () => {
    expect(recall.bucketDisplayKey('env', 'test')).toBe('env · test');
    expect(recall.bucketDisplayKey('source', '')).toBe('source · -');
    expect(recall.bucketDisplayKey(null, 'qwen')).toBe('- · qwen');
  });
});
