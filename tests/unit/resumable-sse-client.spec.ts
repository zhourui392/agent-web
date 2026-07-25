import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  parseFrames,
  shouldApply,
  nextDelay,
  classifyResponse,
  open,
} from '../../src/main/frontend/js/lib/resumable-sse-client.js';

type SseFrame = { type: string; data: string; id: string | null; retry: number | null };

describe('resumable GET SSE protocol', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('parses id event multiline data and retry while retaining incomplete input', () => {
    const events: SseFrame[] = [];

    const remainder = parseFrames(
      'retry: 3000\nid: 12\nevent: chunk\ndata: line1\ndata: line2\n\n' +
      'event: ping\ndata: {}\n\nevent: chunk\ndata: par',
      (event: SseFrame) => events.push(event),
    );

    expect(remainder).toBe('event: chunk\ndata: par');
    expect(events).toEqual([
      { type: 'chunk', data: 'line1\nline2', id: '12', retry: 3000 },
      { type: 'ping', data: '{}', id: null, retry: null },
    ]);
  });

  it('deduplicates persisted sequence events but always applies heartbeat frames', () => {
    expect(shouldApply(12, { type: 'chunk', data: 'old', id: '12', retry: null })).toBe(false);
    expect(shouldApply(12, { type: 'chunk', data: 'new', id: '13', retry: null })).toBe(true);
    expect(shouldApply(12, { type: 'ping', data: '', id: null, retry: null })).toBe(true);
  });

  it('uses capped exponential backoff and deterministic jitter factor', () => {
    expect(nextDelay(0, 1000, 15000, 0)).toBe(1000);
    expect(nextDelay(3, 1000, 15000, 0)).toBe(8000);
    expect(nextDelay(8, 1000, 15000, 0)).toBe(15000);
    expect(nextDelay(1, 1000, 15000, 0.25)).toBe(2250);
  });

  it('classifies authentication cursor expiry and retryable responses', () => {
    expect(classifyResponse(401)).toBe('unauthorized');
    expect(classifyResponse(410)).toBe('cursor_expired');
    expect(classifyResponse(503)).toBe('retry');
    expect(classifyResponse(404)).toBe('fatal');
  });

  it('reconnects after a transient network failure and continues consuming events', async () => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const terminalFrame = new TextEncoder().encode(
      'id: 7\nevent: terminal\ndata: {"status":"SUCCEEDED"}\n\n',
    );
    const fetchFn = vi.fn()
      .mockRejectedValueOnce(new Error('network disconnected'))
      .mockResolvedValueOnce({
        status: 200,
        body: {
          getReader: () => ({
            read: vi.fn().mockResolvedValueOnce({ done: false, value: terminalFrame }),
          }),
        },
      });
    const reconnecting = vi.fn();
    const terminal = vi.fn();

    const client = open('/api/chat/runs/run-1/events', {
      fetch: fetchFn,
      retryBaseMs: 1000,
      retryMaxMs: 1000,
    });
    client.addEventListener('reconnecting', reconnecting);
    client.addEventListener('terminal', terminal);
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(reconnecting).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);

    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(terminal).toHaveBeenCalledWith(expect.objectContaining({
      data: '{"status":"SUCCEEDED"}',
    }));
    client.close();
  });
});
