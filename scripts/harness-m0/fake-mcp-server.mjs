#!/usr/bin/env node

import fs from "node:fs";
import readline from "node:readline";

const logPath = process.env.M0_MCP_LOG;
const slowMilliseconds = Number.parseInt(process.env.M0_SLOW_MS || "30000", 10);

function appendLog(message) {
  if (!logPath) {
    return;
  }
  fs.appendFileSync(logPath, `${new Date().toISOString()} ${message}\n`, "utf8");
}
function send(id, result) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}

function sendError(id, code, message) {
  process.stdout.write(`${JSON.stringify({
    jsonrpc: "2.0",
    id,
    error: { code, message },
  })}\n`);
}

function tools() {
  return [
    {
      name: "read_fixture",
      description: "Return the deterministic M0 read-only marker.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false,
      },
      annotations: {
        title: "Read M0 fixture",
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    {
      name: "write_fixture",
      description: "A deliberately unsafe fixture tool that must remain disabled.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false,
      },
      annotations: {
        title: "Write M0 fixture",
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: false,
      },
    },
    {
      name: "slow_read",
      description: "Wait before returning a marker so timeout and cancellation can be tested.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false,
      },
      annotations: {
        title: "Slow M0 read",
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
  ];
}

async function callTool(name) {
  appendLog(`TOOL_CALL name=${name}`);
  if (name === "read_fixture") {
    return {
      content: [{ type: "text", text: "M0_MCP_READ_OK" }],
      isError: false,
    };
  }
  if (name === "write_fixture") {
    appendLog("UNSAFE_WRITE_TOOL_CALLED");
    return {
      content: [{ type: "text", text: "M0_MCP_WRITE_SHOULD_NOT_BE_VISIBLE" }],
      isError: false,
    };
  }
  if (name === "slow_read") {
    await new Promise((resolve) => setTimeout(resolve, slowMilliseconds));
    return {
      content: [{ type: "text", text: "M0_MCP_SLOW_OK" }],
      isError: false,
    };
  }
  throw new Error(`Unknown tool: ${name}`);
}

async function handle(message) {
  const { id, method, params } = message;
  appendLog(`REQUEST method=${method || "unknown"}`);

  if (method === "initialize") {
    send(id, {
      protocolVersion: params?.protocolVersion || "2025-06-18",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "agent-web-harness-m0", version: "1.0.0" },
      instructions: "This fixture is read-only. Use read_fixture only when explicitly requested.",
    });
    return;
  }
  if (method === "notifications/initialized" || method === "notifications/cancelled") {
    return;
  }
  if (method === "ping") {
    send(id, {});
    return;
  }
  if (method === "tools/list") {
    send(id, { tools: tools() });
    return;
  }
  if (method === "tools/call") {
    try {
      send(id, await callTool(params?.name));
    } catch (error) {
      sendError(id, -32602, error.message);
    }
    return;
  }
  if (id !== undefined) {
    sendError(id, -32601, `Method not found: ${method}`);
  }
}

appendLog(`SERVER_STARTED pid=${process.pid}`);

const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on("line", (line) => {
  if (!line.trim()) {
    return;
  }
  try {
    const message = JSON.parse(line);
    void handle(message);
  } catch (error) {
    appendLog(`PARSE_ERROR message=${error.message}`);
  }
});

input.on("close", () => {
  appendLog("STDIN_CLOSED");
  process.exit(0);
});

for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(signal, () => {
    appendLog(`SERVER_SIGNAL signal=${signal}`);
    process.exit(0);
  });
}
