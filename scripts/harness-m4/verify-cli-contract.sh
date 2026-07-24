#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
FAKE_PROVIDER="$PROJECT_DIR/scripts/harness-m0/fake-responses-server.mjs"
MCP_SERVER="$PROJECT_DIR/src/main/resources/harness/mcp-servers/local-readonly-fixture/1.0.0/server.mjs"
TEMP_ROOT=""
PROVIDER_PID=""

fail() {
  printf 'FAIL - %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$PROVIDER_PID" ]] && kill -0 "$PROVIDER_PID" 2>/dev/null; then
    kill -TERM "$PROVIDER_PID" 2>/dev/null || true
    wait "$PROVIDER_PID" 2>/dev/null || true
  fi
  if [[ -z "$TEMP_ROOT" || ! -e "$TEMP_ROOT" ]]; then
    return
  fi
  node -e '
    const fs = require("fs");
    const path = require("path");
    const target = path.resolve(process.argv[1]);
    if (!/^\/(tmp|var\/tmp)\/agent-web-harness-m4\.[^/]+$/.test(target)) {
      throw new Error(`unsafe cleanup path: ${target}`);
    }
    fs.rmSync(target, { recursive: true, force: true });
  ' "$TEMP_ROOT"
}

trap cleanup EXIT

command -v codex >/dev/null 2>&1 || fail "codex is unavailable"
command -v node >/dev/null 2>&1 || fail "node is unavailable"
command -v git >/dev/null 2>&1 || fail "git is unavailable"
command -v rg >/dev/null 2>&1 || fail "rg is unavailable"
command -v timeout >/dev/null 2>&1 || fail "timeout is unavailable"

codex --version | rg -qx 'codex-cli 0\.145\.0' \
  || fail "Codex version is outside the verified compatibility baseline"

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/var/tmp}/agent-web-harness-m4.XXXXXX")
ISOLATED_HOME="$TEMP_ROOT/home"
WORKSPACE="$TEMP_ROOT/workspace"
PROVIDER_LOG="$TEMP_ROOT/provider.log"
mkdir -p "$ISOLATED_HOME" "$WORKSPACE"
git -C "$WORKSPACE" init -q

M0_MODEL_READY_FILE="$TEMP_ROOT/provider.port" M0_MODEL_LOG="$PROVIDER_LOG" \
  node "$FAKE_PROVIDER" >"$TEMP_ROOT/provider.out" 2>"$TEMP_ROOT/provider.err" &
PROVIDER_PID=$!

for _ in $(seq 1 100); do
  if [[ -s "$TEMP_ROOT/provider.port" ]]; then
    break
  fi
  kill -0 "$PROVIDER_PID" 2>/dev/null \
    || fail "local Responses provider exited before becoming ready"
  sleep 0.1
done
[[ -s "$TEMP_ROOT/provider.port" ]] \
  || fail "local Responses provider did not become ready"

PORT=$(<"$TEMP_ROOT/provider.port")
COMMON=(
  codex --ask-for-approval never exec --ignore-user-config --ignore-rules --ephemeral
  --skip-git-repo-check --json --sandbox read-only -C "$WORKSPACE"
  -c 'model_provider="m4_local"'
  -c 'model="m4-model"'
  -c 'model_providers.m4_local.name="M4 Local Responses Fixture"'
  -c "model_providers.m4_local.base_url=\"http://127.0.0.1:$PORT/v1\""
  -c 'model_providers.m4_local.wire_api="responses"'
  -c 'model_providers.m4_local.supports_websockets=false'
  -c 'model_providers.m4_local.request_max_retries=0'
  -c 'model_providers.m4_local.stream_max_retries=0'
)

HOME="$ISOLATED_HOME" CODEX_HOME="$ISOLATED_HOME" timeout 60s "${COMMON[@]}" \
  -c 'mcp_servers.local-readonly-fixture.command="node"' \
  -c "mcp_servers.local-readonly-fixture.args=[\"$MCP_SERVER\"]" \
  -c 'mcp_servers.local-readonly-fixture.required=true' \
  -c 'mcp_servers.local-readonly-fixture.startup_timeout_sec=5' \
  -c 'mcp_servers.local-readonly-fixture.tool_timeout_sec=10' \
  -c 'mcp_servers.local-readonly-fixture.enabled_tools=["read_fixture"]' \
  -c 'mcp_servers.local-readonly-fixture.disabled_tools=[]' \
  'Call read_fixture exactly once and report its marker.' \
  >"$TEMP_ROOT/analysis.events.jsonl" 2>"$TEMP_ROOT/analysis.err"

rg -q 'harness-readonly-fixture:v1' \
  "$TEMP_ROOT/analysis.events.jsonl" "$PROVIDER_LOG" \
  || fail "ANALYSIS-compatible run did not observe the read-only fixture marker"
printf 'PASS - real Codex calls the M4 read-only MCP fixture in an ANALYSIS-compatible run\n'

: >"$PROVIDER_LOG"
HOME="$ISOLATED_HOME" CODEX_HOME="$ISOLATED_HOME" timeout 60s "${COMMON[@]}" \
  'Do not call tools. Return the word design.' \
  >"$TEMP_ROOT/forbidden-stage.events.jsonl" 2>"$TEMP_ROOT/forbidden-stage.err"

rg -q 'MODEL_TOOL_MISSING requested=read_fixture' "$PROVIDER_LOG" \
  || fail "forbidden-stage-compatible run did not prove the fixture tool was absent"
if rg -q 'MODEL_TOOL_CALL.*actual' "$PROVIDER_LOG"; then
  fail "forbidden-stage-compatible run exposed a callable MCP tool"
fi
printf 'PASS - real Codex exposes no M4 fixture tool when the server is not mounted\n'
