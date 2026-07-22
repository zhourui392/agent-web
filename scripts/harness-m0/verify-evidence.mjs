#!/usr/bin/env node

import fs from "node:fs";

function fail(message) {
  process.stderr.write(`M0 evidence verification failed: ${message}\n`);
  process.exit(1);
}

function read(path) {
  return fs.readFileSync(path, "utf8");
}

function parseJson(path) {
  try {
    return JSON.parse(read(path));
  } catch (error) {
    fail(`${path} is not valid JSON: ${error.message}`);
  }
}

function parseJsonLines(path) {
  return read(path)
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line, index) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        fail(`${path}:${index + 1} is not valid JSON: ${error.message}`);
      }
    });
}

function includesDeep(value, marker) {
  return JSON.stringify(value).includes(marker);
}

const [mode, ...paths] = process.argv.slice(2);

if (mode === "mcp-list-present") {
  const list = parseJson(paths[0]);
  if (!Array.isArray(list) || !list.some((server) => server.name === "m0-fixture" && server.enabled)) {
    fail("m0-fixture is not registered and enabled");
  }
} else if (mode === "mcp-policy") {
  const server = parseJson(paths[0]);
  if (server.name !== "m0-fixture") {
    fail("unexpected MCP server name");
  }
  if (!server.enabled_tools?.includes("read_fixture")) {
    fail("read_fixture is not enabled");
  }
  if (!server.disabled_tools?.includes("write_fixture")) {
    fail("write_fixture is not disabled");
  }
} else if (mode === "mcp-list-empty") {
  const list = parseJson(paths[0]);
  if (!Array.isArray(list) || list.length !== 0) {
    fail("isolated MCP registry is not empty after removal");
  }
} else if (mode === "prompt-skill") {
  const prompt = parseJson(paths[0]);
  if (!includesDeep(prompt, "m0-harness-skill") || !includesDeep(prompt, "M0 Harness capability probe")) {
    fail("repository skill metadata is not present in model-visible prompt input");
  }
} else if (mode === "prompt-skill-disabled") {
  const prompt = parseJson(paths[0]);
  if (includesDeep(prompt, "M0 Harness capability probe")) {
    fail("disabled repository Skill remains visible in model-visible prompt input");
  }
} else if (mode === "live-success") {
  const events = parseJsonLines(paths[0]);
  const result = parseJson(paths[1]);
  const mcpLog = read(paths[2]);
  const modelLog = read(paths[3]);
  if (!events.some((event) => event.type === "thread.started")) {
    fail("thread.started event is missing");
  }
  if (!events.some((event) => event.type === "turn.completed")) {
    fail("turn.completed event is missing");
  }
  if (!events.some((event) => includesDeep(event, "read_fixture"))) {
    fail("read_fixture MCP event is missing");
  }
  if (events.some((event) => includesDeep(event, "command_execution"))) {
    fail("unexpected shell command execution occurred");
  }
  if (result.skill_marker !== "M0_SKILL_LOADED_V1" || result.mcp_marker !== "M0_MCP_READ_OK") {
    fail("structured output markers do not match");
  }
  const readCalls = mcpLog.match(/TOOL_CALL name=read_fixture/g) || [];
  if (readCalls.length !== 1) {
    fail(`read_fixture call count is ${readCalls.length}, expected 1`);
  }
  if (mcpLog.includes("UNSAFE_WRITE_TOOL_CALLED")) {
    fail("disabled write_fixture tool was called");
  }
  if (!modelLog.includes("M0_SKILL_LOADED_V1")) {
    fail("full Skill instructions were not visible to the model provider");
  }
  if (modelLog.includes('"name":"write_fixture"')) {
    fail("disabled write_fixture tool was exposed to the model provider");
  }
} else if (mode === "required-failure") {
  const combined = `${read(paths[0])}\n${read(paths[1])}`;
  if (!/m0-broken|required|initialize|No such file|failed/i.test(combined)) {
    fail("required MCP startup failure was not observable");
  }
} else if (mode === "tool-timeout") {
  const events = parseJsonLines(paths[0]);
  const errors = read(paths[1]);
  const mcpLog = read(paths[2]);
  if (!mcpLog.includes("TOOL_CALL name=slow_read")) {
    fail("slow_read was not called");
  }
  const combined = `${JSON.stringify(events)}\n${errors}`;
  if (!/timed out|timeout|failed/i.test(combined)) {
    fail("tool timeout was not observable");
  }
  if (!events.some((event) => event.type === "turn.completed" || event.type === "turn.failed")) {
    fail("timeout run has no terminal turn event");
  }
} else if (mode === "cancelled") {
  const events = parseJsonLines(paths[0]);
  const mcpLog = read(paths[1]);
  if (!mcpLog.includes("TOOL_CALL name=slow_read")) {
    fail("cancelled run never reached slow_read");
  }
  if (!events.some((event) => includesDeep(event, "slow_read") && includesDeep(event, "in_progress"))) {
    fail("cancelled run has no in-progress MCP event");
  }
  if (events.some((event) => event.type === "turn.completed" || event.type === "turn.failed")) {
    fail("cancelled run unexpectedly emitted a terminal turn event");
  }
} else {
  fail(`unknown verification mode: ${mode}`);
}
