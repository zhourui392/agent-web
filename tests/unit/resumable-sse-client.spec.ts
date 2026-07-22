import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const sse = requireCjs('../../src/main/resources/static/js/lib/resumable-sse-client.js') as {
  parseFrames: (buffer: string, consumer: (event: SseFrame) => void) => string;
  shouldApply: (lastSequence: number, event: SseFrame) => boolean;
  nextDelay: (attempt: number, baseMs: number, maxMs: number, jitter: number) => number;
  classifyResponse: (status: number) => string;
};

type SseFrame = { type: string; data: string; id: string | null; retry: number | null };

describe('resumable GET SSE protocol', () => {
  it('parses id event multiline data and retry while retaining incomplete input', () => {
    const events: SseFrame[] = [];

    const remainder = sse.parseFrames(
      'retry: 3000\nid: 12\nevent: chunk\ndata: line1\ndata: line2\n\n' +
      'event: ping\ndata: {}\n\nevent: chunk\ndata: par',
      event => events.push(event),
    );

    expect(remainder).toBe('event: chunk\ndata: par');
    expect(events).toEqual([
      { type: 'chunk', data: 'line1\nline2', id: '12', retry: 3000 },
      { type: 'ping', data: '{}', id: null, retry: null },
    ]);
  });

  it('deduplicates persisted sequence events but always applies heartbeat frames', () => {
    expect(sse.shouldApply(12, { type: 'chunk', data: 'old', id: '12', retry: null })).toBe(false);
    expect(sse.shouldApply(12, { type: 'chunk', data: 'new', id: '13', retry: null })).toBe(true);
    expect(sse.shouldApply(12, { type: 'ping', data: '', id: null, retry: null })).toBe(true);
  });

  it('uses capped exponential backoff and deterministic jitter factor', () => {
    expect(sse.nextDelay(0, 1000, 15000, 0)).toBe(1000);
    expect(sse.nextDelay(3, 1000, 15000, 0)).toBe(8000);
    expect(sse.nextDelay(8, 1000, 15000, 0)).toBe(15000);
    expect(sse.nextDelay(1, 1000, 15000, 0.25)).toBe(2250);
  });

  it('classifies authentication cursor expiry and retryable responses', () => {
    expect(sse.classifyResponse(401)).toBe('unauthorized');
    expect(sse.classifyResponse(410)).toBe('cursor_expired');
    expect(sse.classifyResponse(503)).toBe('retry');
    expect(sse.classifyResponse(404)).toBe('fatal');
  });
});
