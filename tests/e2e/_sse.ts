export type SseEvent = {
  id?: number;
  event?: string;
  data: string;
};

type ReadSseOptions = {
  headers?: Record<string, string>;
  terminalEvents?: string[];
  maxEvents?: number;
  timeoutMs?: number;
};

export async function readSse(url: string, options: ReadSseOptions = {}): Promise<SseEvent[]> {
  const terminalEvents = new Set(options.terminalEvents || ['result', 'error']);
  const maxEvents = options.maxEvents || 100;
  const timeoutMs = options.timeoutMs || 20_000;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const events: SseEvent[] = [];

  try {
    const res = await fetch(url, {
      headers: options.headers || {},
      signal: controller.signal,
    });
    if (!res.ok) {
      throw new Error('SSE request failed: ' + res.status + ' ' + await res.text());
    }
    if (!res.body) {
      throw new Error('SSE response has no body');
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.search(/\r?\n\r?\n/);
      while (boundary >= 0) {
        const raw = buffer.slice(0, boundary);
        buffer = buffer.slice(buffer[boundary] === '\r' ? boundary + 4 : boundary + 2);
        const event = parseSseEvent(raw);
        if (event) {
          events.push(event);
          if (terminalEvents.has(event.event || 'message') || events.length >= maxEvents) {
            controller.abort();
            return events;
          }
        }
        boundary = buffer.search(/\r?\n\r?\n/);
      }
    }
    return events;
  } catch (e) {
    if ((e as Error).name === 'AbortError' && events.length > 0) {
      return events;
    }
    throw e;
  } finally {
    clearTimeout(timeout);
  }
}

function parseSseEvent(raw: string): SseEvent | null {
  const event: SseEvent = { data: '' };
  const data: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) {
      continue;
    }
    const idx = line.indexOf(':');
    const field = idx >= 0 ? line.slice(0, idx) : line;
    const value = idx >= 0 ? line.slice(idx + 1).replace(/^ /, '') : '';
    if (field === 'id') {
      const parsed = Number(value);
      if (!Number.isNaN(parsed)) {
        event.id = parsed;
      }
    } else if (field === 'event') {
      event.event = value;
    } else if (field === 'data') {
      data.push(value);
    }
  }
  event.data = data.join('\n');
  return event.event || event.id != null || event.data ? event : null;
}
