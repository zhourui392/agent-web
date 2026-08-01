import { createInterface } from 'node:readline';

const FIXTURE_VALUE = 'harness-readonly-fixture:v1';
const TOOL = {
  name: 'read_fixture',
  description: 'Return a fixed, non-sensitive Harness compatibility value.',
  inputSchema: {
    type: 'object',
    properties: {},
    additionalProperties: false,
  },
  annotations: {
    title: 'Read Harness fixture',
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: true,
    openWorldHint: false,
  },
};

function result(id, value) {
  return { jsonrpc: '2.0', id, result: value };
}

function error(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function handle(message) {
  if (!message || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
    return error(message?.id ?? null, -32600, 'Invalid Request');
  }
  if (message.id === undefined) {
    return undefined;
  }
  if (message.method === 'initialize') {
    return result(message.id, {
      protocolVersion: message.params?.protocolVersion ?? '2024-11-05',
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: 'harness-local-readonly-fixture', version: '1.0.0' },
    });
  }
  if (message.method === 'ping') {
    return result(message.id, {});
  }
  if (message.method === 'tools/list') {
    return result(message.id, { tools: [TOOL] });
  }
  if (message.method === 'tools/call') {
    if (message.params?.name !== TOOL.name) {
      return error(message.id, -32602, 'Unknown or unauthorized tool');
    }
    return result(message.id, {
      content: [{ type: 'text', text: FIXTURE_VALUE }],
      isError: false,
    });
  }
  return error(message.id, -32601, 'Method not found');
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', line => {
  if (!line.trim()) {
    return;
  }
  let response;
  try {
    response = handle(JSON.parse(line));
  } catch (_error) {
    response = error(null, -32700, 'Parse error');
  }
  if (response) {
    process.stdout.write(`${JSON.stringify(response)}\n`);
  }
});
