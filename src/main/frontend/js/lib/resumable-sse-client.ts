/**
 * Fetch-based resumable GET SSE client with persisted-event cursor deduplication.
 *
 * @author zhourui(V33215020)
 */

interface SseFrame {
  type: string;
  data: string;
  id: string | null;
  retry: number | null;
}

interface SseEvent {
  type: string;
  data: string;
  lastEventId: string;
}

type ResponseClass = 'unauthorized' | 'cursor_expired' | 'retry' | 'ok' | 'fatal';

interface SseClient {
  CONNECTING: 0;
  OPEN: 1;
  CLOSED: 2;
  readyState: number;
  onerror: ((event: SseEvent) => void) | null;
  addEventListener(type: string, listener: (event: SseEvent) => void): void;
  close(): void;
  readonly lastEventId: string;
}

interface SseClientOptions {
  after?: number;
  retryBaseMs?: number;
  retryMaxMs?: number;
  reconnectTimeoutMs?: number;
  fetch?: typeof fetch;
}

export function parseFrames(buffer: string, consumer: (frame: SseFrame) => void): string {
  var normalized = String(buffer || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  var boundary;
  while ((boundary = normalized.indexOf('\n\n')) >= 0) {
    var block = normalized.substring(0, boundary);
    normalized = normalized.substring(boundary + 2);
    var eventType = 'message';
    var eventId: string | null = null;
    var retry: number | null = null;
    var data: string[] = [];
    var lines = block.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line || line.charAt(0) === ':') continue;
      var colon = line.indexOf(':');
      var field = colon < 0 ? line : line.substring(0, colon);
      var value = colon < 0 ? '' : line.substring(colon + 1).replace(/^ /, '');
      if (field === 'event') eventType = value;
      if (field === 'id' && value.indexOf('\0') < 0) eventId = value;
      if (field === 'retry' && /^\d+$/.test(value)) retry = Number(value);
      if (field === 'data') data.push(value);
    }
    if (data.length || eventType !== 'message' || eventId !== null || retry !== null) {
      consumer({ type: eventType, data: data.join('\n'), id: eventId, retry: retry });
    }
  }
  return normalized;
}

function numericSequence(event: SseFrame): number | null {
  if (!event || event.id == null || !/^\d+$/.test(String(event.id))) return null;
  return Number(event.id);
}

export function shouldApply(lastSequence: number, event: SseFrame): boolean {
  var sequence = numericSequence(event);
  return sequence == null || sequence > Number(lastSequence || 0);
}

export function nextDelay(attempt: number, baseMs: number, maxMs: number, jitter: number): number {
  var base = Math.max(1, Number(baseMs) || 1000);
  var maximum = Math.max(base, Number(maxMs) || 15000);
  var power = Math.max(0, Number(attempt) || 0);
  var delay = Math.min(maximum, base * Math.pow(2, power));
  var jitterFactor = Math.max(-1, Math.min(1, Number(jitter) || 0));
  return Math.round(Math.min(maximum, delay * (1 + jitterFactor / 2)));
}

export function classifyResponse(status: number): ResponseClass {
  if (status === 401) return 'unauthorized';
  if (status === 410) return 'cursor_expired';
  if (status === 408 || status === 425 || status === 429 || status >= 500) return 'retry';
  return status >= 200 && status < 300 ? 'ok' : 'fatal';
}

function appendCursor(url: string, cursor: number): string {
  var separator = String(url).indexOf('?') >= 0 ? '&' : '?';
  return String(url) + separator + 'after=' + encodeURIComponent(cursor);
}

export function open(url: string, options?: SseClientOptions): SseClient {
  options = options || {};
  var listeners: Record<string, Array<(event: SseEvent) => void>> = {};
  var closed = false;
  var terminal = false;
  var reconnectAttempt = 0;
  var reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  var watchdogTimer: ReturnType<typeof setTimeout> | null = null;
  var controller: AbortController | null = null;
  var lastSequence = Math.max(0, Number(options.after) || 0);
  var retryBaseMs = Math.max(1, Number(options.retryBaseMs) || 1000);
  var retryMaxMs = Math.max(retryBaseMs, Number(options.retryMaxMs) || 15000);
  var reconnectTimeoutMs = Math.max(1000, Number(options.reconnectTimeoutMs) || 35000);
  var fetchFn: typeof fetch = options.fetch || window.fetch;

  var client = {
    CONNECTING: 0,
    OPEN: 1,
    CLOSED: 2,
    readyState: 0,
    onerror: null,
    addEventListener: function (type: string, listener: (event: SseEvent) => void) {
      (listeners[type] || (listeners[type] = [])).push(listener);
    },
    close: function () {
      closed = true;
      client.readyState = client.CLOSED;
      clearTimers();
      if (controller) controller.abort();
    }
  } as SseClient;
  Object.defineProperty(client, 'lastEventId', {
    enumerable: true,
    get: function () { return String(lastSequence); }
  });

  function dispatch(type: string, data: string | null, frame: SseFrame | null) {
    var event: SseEvent = {
      type: type,
      data: data == null ? '' : data,
      lastEventId: frame && frame.id != null ? String(frame.id) : ''
    };
    var handlers = listeners[type] || [];
    for (var i = 0; i < handlers.length; i++) handlers[i](event);
    if (type === 'fatal' && typeof client.onerror === 'function') (client.onerror as any)(event);
  }

  function clearTimers() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    if (watchdogTimer) clearTimeout(watchdogTimer);
    reconnectTimer = null;
    watchdogTimer = null;
  }

  function resetWatchdog() {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(function () {
      if (!closed && controller) controller.abort();
    }, reconnectTimeoutMs);
  }

  function permanentClose() {
    terminal = true;
    client.close();
  }

  function scheduleReconnect(reason: string) {
    if (closed || terminal || reconnectTimer) return;
    client.readyState = client.CONNECTING;
    var randomJitter = (Math.random() * 0.4) - 0.2;
    var delay = nextDelay(reconnectAttempt++, retryBaseMs, retryMaxMs, randomJitter);
    dispatch('reconnecting', JSON.stringify({ attempt: reconnectAttempt, delayMs: delay, reason: reason }), null);
    reconnectTimer = setTimeout(function () {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function consume(frame: SseFrame) {
    resetWatchdog();
    if (frame.retry != null) retryBaseMs = Math.max(1, frame.retry);
    if (!shouldApply(lastSequence, frame)) return;
    var sequence = numericSequence(frame);
    if (sequence != null) lastSequence = sequence;
    dispatch(frame.type, frame.data, frame);
    if (frame.type === 'terminal') permanentClose();
  }

  async function connect() {
    if (closed || terminal) return;
    client.readyState = client.CONNECTING;
    controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    try {
      var headers: Record<string, string> = { 'Accept': 'text/event-stream' };
      if (lastSequence > 0) headers['Last-Event-ID'] = String(lastSequence);
      var response = await fetchFn(appendCursor(url, lastSequence), {
        method: 'GET',
        headers: headers,
        signal: controller ? controller.signal : undefined
      });
      var classification = classifyResponse(response.status);
      if (classification === 'unauthorized') {
        dispatch('unauthorized', '', null);
        permanentClose();
        return;
      }
      if (classification === 'cursor_expired') {
        var expired = await response.text();
        dispatch('cursor_expired', expired || '{}', null);
        permanentClose();
        return;
      }
      if (classification === 'fatal') {
        dispatch('fatal', 'HTTP ' + response.status, null);
        permanentClose();
        return;
      }
      if (classification === 'retry' || !response.body) {
        throw new Error('HTTP ' + response.status);
      }

      client.readyState = client.OPEN;
      reconnectAttempt = 0;
      resetWatchdog();
      var reader = response.body.getReader();
      var decoder = new TextDecoder('utf-8');
      var remainder = '';
      while (!closed && !terminal) {
        var part = await reader.read();
        if (part.done) break;
        remainder = parseFrames(remainder + decoder.decode(part.value, { stream: true }), consume);
      }
      if (!closed && !terminal) {
        parseFrames(remainder + decoder.decode(), consume);
        scheduleReconnect('stream-ended');
      }
    } catch (error) {
      if (closed || terminal) return;
      var reason = error && (error as Error).message ? (error as Error).message : String(error);
      scheduleReconnect(reason);
    }
  }

  Promise.resolve().then(connect);
  return client;
}