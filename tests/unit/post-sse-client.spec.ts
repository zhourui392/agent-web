import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';

const requireCjs = createRequire(import.meta.url);
const sse = requireCjs('../../src/main/resources/static/js/lib/post-sse-client.js') as {
  parseFrames: (buffer: string, consumer: (event: { type: string; data: string }) => void) => string;
};

describe('POST SSE frame parser', () => {
  it('parses named events and joins multiline data', () => {
    const events: Array<{ type: string; data: string }> = [];

    const remainder = sse.parseFrames(
      'event: chunk\ndata: line1\ndata: line2\n\nevent: exit\ndata: 0\n\n',
      event => events.push(event),
    );

    expect(remainder).toBe('');
    expect(events).toEqual([
      { type: 'chunk', data: 'line1\nline2' },
      { type: 'exit', data: '0' },
    ]);
  });

  it('keeps incomplete frame for the next network chunk and accepts CRLF', () => {
    const events: Array<{ type: string; data: string }> = [];
    const first = sse.parseFrames('event: ping\r\ndata: {}\r\n\r\nevent: chunk\r\ndata: par',
      event => events.push(event));

    expect(events).toEqual([{ type: 'ping', data: '{}' }]);
    expect(first).toBe('event: chunk\ndata: par');

    const second = sse.parseFrames(first + 'tial\n\n', event => events.push(event));
    expect(second).toBe('');
    expect(events[1]).toEqual({ type: 'chunk', data: 'partial' });
  });
});
