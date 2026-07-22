/**
 * 通过 fetch POST 消费 text/event-stream，对外提供与当前页面所需 EventSource 子集一致的 API。
 *
 * @author zhourui(V33215020)
 */
(function (root) {
  function parseFrames(buffer, consumer) {
    var normalized = String(buffer || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    var boundary;
    while ((boundary = normalized.indexOf('\n\n')) >= 0) {
      var block = normalized.substring(0, boundary);
      normalized = normalized.substring(boundary + 2);
      var eventType = 'message';
      var data = [];
      var lines = block.split('\n');
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (!line || line.charAt(0) === ':') continue;
        var colon = line.indexOf(':');
        var field = colon < 0 ? line : line.substring(0, colon);
        var value = colon < 0 ? '' : line.substring(colon + 1).replace(/^ /, '');
        if (field === 'event') eventType = value;
        if (field === 'data') data.push(value);
      }
      if (data.length || eventType !== 'message') {
        consumer({ type: eventType, data: data.join('\n') });
      }
    }
    return normalized;
  }

  function open(url, payload) {
    var listeners = {};
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var closed = false;
    var client = {
      onerror: null,
      addEventListener: function (type, listener) {
        (listeners[type] || (listeners[type] = [])).push(listener);
      },
      close: function () {
        closed = true;
        if (controller) controller.abort();
      }
    };

    function dispatch(type, data) {
      var event = { type: type, data: data };
      var handlers = listeners[type] || [];
      for (var i = 0; i < handlers.length; i++) handlers[i](event);
    }

    Promise.resolve().then(async function () {
      try {
        var response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
          body: JSON.stringify(payload || {}),
          signal: controller ? controller.signal : undefined
        });
        if (!response.ok || !response.body) {
          throw new Error('HTTP ' + response.status);
        }
        var reader = response.body.getReader();
        var decoder = new TextDecoder('utf-8');
        var remainder = '';
        while (!closed) {
          var part = await reader.read();
          if (part.done) break;
          remainder = parseFrames(remainder + decoder.decode(part.value, { stream: true }), function (event) {
            dispatch(event.type, event.data);
          });
        }
        remainder = parseFrames(remainder + decoder.decode(), function (event) {
          dispatch(event.type, event.data);
        });
      } catch (error) {
        if (closed || (error && error.name === 'AbortError')) return;
        var message = error && error.message ? error.message : String(error);
        dispatch('error', message);
        if (typeof client.onerror === 'function') client.onerror({ type: 'error', data: message });
      }
    });

    return client;
  }

  var api = { parseFrames: parseFrames, open: open };
  root.AgentPostSse = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
