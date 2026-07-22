#!/usr/bin/env node

import fs from "node:fs";
import http from "node:http";

const readyFile = process.env.M0_MODEL_READY_FILE;
const logFile = process.env.M0_MODEL_LOG;
let sequence = 0;

function appendLog(message) {
  if (!logFile) {
    return;
  }
  fs.appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`, "utf8");
}

function nextId(prefix) {
  sequence += 1;
  return `${prefix}_m0_${sequence}`;
}

function emit(response, event) {
  response.write(`event: ${event.type}\n`);
  response.write(`data: ${JSON.stringify(event)}\n\n`);
}

function responseEnvelope(model, output, tools, status = "completed") {
  const inputTokens = 100;
  const outputTokens = 10;
  return {
    id: nextId("resp"),
    object: "response",
    created_at: Math.floor(Date.now() / 1000),
    status,
    background: false,
    error: null,
    incomplete_details: null,
    instructions: null,
    max_output_tokens: null,
    max_tool_calls: null,
    model,
    output,
    parallel_tool_calls: false,
    previous_response_id: null,
    prompt_cache_key: null,
    reasoning: { effort: null, summary: null },
    safety_identifier: null,
    service_tier: "default",
    store: false,
    temperature: null,
    text: { format: { type: "text" }, verbosity: "medium" },
    tool_choice: "auto",
    tools,
    top_logprobs: 0,
    top_p: null,
    truncation: "disabled",
    usage: {
      input_tokens: inputTokens,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: outputTokens,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: inputTokens + outputTokens,
    },
    user: null,
    metadata: {},
  };
}

function begin(response, body) {
  const envelope = responseEnvelope(body.model || "m0-model", [], body.tools || [], "in_progress");
  emit(response, { type: "response.created", response: envelope, sequence_number: 0 });
}

function completeFunctionCall(response, body, tool) {
  const item = {
    id: nextId("fc"),
    type: "function_call",
    status: "completed",
    call_id: nextId("call"),
    name: tool.name,
    arguments: "{}",
  };
  if (tool.namespace) {
    item.namespace = tool.namespace;
  }
  emit(response, {
    type: "response.output_item.added",
    output_index: 0,
    item: { ...item, status: "in_progress", arguments: "" },
    sequence_number: 1,
  });
  emit(response, {
    type: "response.function_call_arguments.delta",
    item_id: item.id,
    output_index: 0,
    delta: "{}",
    sequence_number: 2,
  });
  emit(response, {
    type: "response.function_call_arguments.done",
    item_id: item.id,
    output_index: 0,
    arguments: "{}",
    sequence_number: 3,
  });
  emit(response, {
    type: "response.output_item.done",
    output_index: 0,
    item,
    sequence_number: 4,
  });
  emit(response, {
    type: "response.completed",
    response: responseEnvelope(body.model || "m0-model", [item], body.tools || []),
    sequence_number: 5,
  });
}

function completeText(response, body, text) {
  const itemId = nextId("msg");
  const part = { type: "output_text", annotations: [], logprobs: [], text };
  const item = {
    id: itemId,
    type: "message",
    status: "completed",
    role: "assistant",
    content: [part],
  };
  emit(response, {
    type: "response.output_item.added",
    output_index: 0,
    item: { ...item, status: "in_progress", content: [] },
    sequence_number: 1,
  });
  emit(response, {
    type: "response.content_part.added",
    item_id: itemId,
    output_index: 0,
    content_index: 0,
    part: { ...part, text: "" },
    sequence_number: 2,
  });
  emit(response, {
    type: "response.output_text.delta",
    item_id: itemId,
    output_index: 0,
    content_index: 0,
    delta: text,
    logprobs: [],
    sequence_number: 3,
  });
  emit(response, {
    type: "response.output_text.done",
    item_id: itemId,
    output_index: 0,
    content_index: 0,
    text,
    logprobs: [],
    sequence_number: 4,
  });
  emit(response, {
    type: "response.content_part.done",
    item_id: itemId,
    output_index: 0,
    content_index: 0,
    part,
    sequence_number: 5,
  });
  emit(response, {
    type: "response.output_item.done",
    output_index: 0,
    item,
    sequence_number: 6,
  });
  emit(response, {
    type: "response.completed",
    response: responseEnvelope(body.model || "m0-model", [item], body.tools || []),
    sequence_number: 7,
  });
}

function inputContainsToolResult(body) {
  return JSON.stringify(body.input || []).includes("function_call_output");
}

function requestedTool(body) {
  const serialized = JSON.stringify(body.tools || []);
  return serialized.includes('"name":"slow_read"')
    ? "slow_read"
    : "read_fixture";
}

function resolvedToolName(body, requested) {
  const candidates = Array.isArray(body.tools) ? body.tools : [];
  const tool = candidates.find((candidate) =>
    typeof candidate.name === "string" && candidate.name.endsWith(requested));
  if (tool?.name) {
    return { name: tool.name };
  }
  for (const namespace of candidates) {
    if (!Array.isArray(namespace.tools)) {
      continue;
    }
    const nested = namespace.tools.find((candidate) => candidate.name === requested);
    if (nested && typeof namespace.name === "string") {
      return { namespace: namespace.name, name: nested.name };
    }
  }
  return undefined;
}

async function readRequest(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"status":"ok"}');
    return;
  }
  if (request.method !== "POST" || !request.url?.endsWith("/responses")) {
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"error":"not found"}');
    return;
  }

  const raw = await readRequest(request);
  let body;
  try {
    body = JSON.parse(raw);
  } catch (error) {
    response.writeHead(400, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: error.message }));
    return;
  }

  appendLog(`MODEL_REQUEST ${JSON.stringify(body)}`);
  response.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });

  begin(response, body);
  if (inputContainsToolResult(body)) {
    const input = JSON.stringify(body.input || []);
    const text = input.includes("M0_MCP_READ_OK")
      ? '{"skill_marker":"M0_SKILL_LOADED_V1","mcp_marker":"M0_MCP_READ_OK"}'
      : "M0 observed the expected MCP tool timeout.";
    appendLog(`MODEL_FINAL text=${text}`);
    completeText(response, body, text);
  } else {
    const requested = requestedTool(body);
    const actual = resolvedToolName(body, requested);
    if (!actual) {
      appendLog(`MODEL_TOOL_MISSING requested=${requested}`);
      completeText(response, body, `M0 fixture could not find tool ${requested}.`);
    } else {
      appendLog(`MODEL_TOOL_CALL requested=${requested} actual=${JSON.stringify(actual)}`);
      completeFunctionCall(response, body, actual);
    }
  }
  response.write("data: [DONE]\n\n");
  response.end();
});

server.listen(0, "127.0.0.1", () => {
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("Unable to determine fake model server port");
  }
  appendLog(`MODEL_SERVER_STARTED pid=${process.pid} port=${address.port}`);
  if (readyFile) {
    fs.writeFileSync(readyFile, String(address.port), "utf8");
  }
});

for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(signal, () => {
    appendLog(`MODEL_SERVER_SIGNAL signal=${signal}`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1000).unref();
  });
}
