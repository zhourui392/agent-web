#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
MODE=${1:-all}
REAL_HOME=${HOME}
REAL_CODEX_HOME=${CODEX_HOME:-$REAL_HOME/.codex}
TEMP_ROOT=""
PASS_COUNT=0
MODEL_PID=""

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS %02d - %s\n' "$PASS_COUNT" "$1"
}

fail() {
  printf 'FAIL - %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"
}

cleanup() {
  if [[ -n "$MODEL_PID" ]] && kill -0 "$MODEL_PID" 2>/dev/null; then
    kill -TERM "$MODEL_PID" 2>/dev/null || true
    wait "$MODEL_PID" 2>/dev/null || true
  fi
  if [[ -z "$TEMP_ROOT" || ! -e "$TEMP_ROOT" ]]; then
    return
  fi
  if [[ "$TEMP_ROOT" != /tmp/agent-web-harness-m0.* && "$TEMP_ROOT" != /var/tmp/agent-web-harness-m0.* ]]; then
    fail "refusing to clean unexpected path: $TEMP_ROOT"
  fi
  if [[ "${KEEP_M0_TEMP:-0}" == "1" ]]; then
    printf 'M0 temporary evidence retained at %s\n' "$TEMP_ROOT"
    return
  fi
  node -e '
    const fs = require("fs");
    const path = require("path");
    const target = path.resolve(process.argv[1]);
    if (!/^\/(tmp|var\/tmp)\/agent-web-harness-m0\.[^/]+$/.test(target)) {
      throw new Error(`unsafe cleanup path: ${target}`);
    }
    fs.rmSync(target, { recursive: true, force: true });
    if (fs.existsSync(target)) {
      throw new Error(`temporary path still exists: ${target}`);
    }
  ' "$TEMP_ROOT"
}

trap cleanup EXIT

require_command codex
require_command node
require_command git
require_command setsid
require_command timeout

if [[ "$MODE" != "static" && "$MODE" != "live" && "$MODE" != "all" ]]; then
  fail "usage: $0 [static|live|all]"
fi

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/var/tmp}/agent-web-harness-m0.XXXXXX")
ISOLATED_CODEX_HOME="$TEMP_ROOT/codex-home"
ISOLATED_USER_HOME="$TEMP_ROOT/user-home"
WORKSPACE="$TEMP_ROOT/workspace"
EVIDENCE="$TEMP_ROOT/evidence"
mkdir -p "$ISOLATED_CODEX_HOME" "$ISOLATED_USER_HOME" \
  "$WORKSPACE/.agents/skills/m0-harness-skill" "$EVIDENCE"
cp "$SCRIPT_DIR/fixtures/m0-harness-skill/SKILL.md" \
  "$WORKSPACE/.agents/skills/m0-harness-skill/SKILL.md"
cp "$SCRIPT_DIR/fixtures/result.schema.json" "$WORKSPACE/result.schema.json"
git -C "$WORKSPACE" init -q

run_static_tests() {
  codex --version >"$EVIDENCE/codex-version.txt"
  node --version >"$EVIDENCE/node-version.txt"
  pass "Codex and Node versions captured"

  CODEX_HOME="$ISOLATED_CODEX_HOME" codex mcp add m0-fixture -- \
    node "$SCRIPT_DIR/fake-mcp-server.mjs" >"$EVIDENCE/mcp-add.out" 2>"$EVIDENCE/mcp-add.err"
  CODEX_HOME="$ISOLATED_CODEX_HOME" codex mcp list --json >"$EVIDENCE/mcp-list.json" \
    2>"$EVIDENCE/mcp-list.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" mcp-list-present "$EVIDENCE/mcp-list.json"
  pass "MCP registration is isolated under a temporary CODEX_HOME"

  CODEX_HOME="$ISOLATED_CODEX_HOME" codex mcp get m0-fixture --json \
    -c 'mcp_servers.m0-fixture.enabled_tools=["read_fixture"]' \
    -c 'mcp_servers.m0-fixture.disabled_tools=["write_fixture"]' \
    >"$EVIDENCE/mcp-policy.json" 2>"$EVIDENCE/mcp-policy.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" mcp-policy "$EVIDENCE/mcp-policy.json"
  pass "MCP enabled_tools and disabled_tools overrides are effective"

  CODEX_HOME="$ISOLATED_CODEX_HOME" codex mcp remove m0-fixture \
    >"$EVIDENCE/mcp-remove.out" 2>"$EVIDENCE/mcp-remove.err"
  CODEX_HOME="$ISOLATED_CODEX_HOME" codex mcp list --json >"$EVIDENCE/mcp-empty.json" \
    2>"$EVIDENCE/mcp-empty.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" mcp-list-empty "$EVIDENCE/mcp-empty.json"
  pass "MCP registration can be removed without touching the real Codex home"

  HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" \
    codex -C "$WORKSPACE" debug prompt-input \
    'Use $m0-harness-skill explicitly and return its marker.' \
    >"$EVIDENCE/prompt-input.json" 2>"$EVIDENCE/prompt-input.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" prompt-skill "$EVIDENCE/prompt-input.json"
  pass "Repository Skill metadata is discoverable in model-visible input"

  HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" \
    codex -C "$WORKSPACE" debug prompt-input \
    -c "skills.config=[{path=\"$WORKSPACE/.agents/skills/m0-harness-skill/SKILL.md\",enabled=false}]" \
    'Return the fixed word disabled.' \
    >"$EVIDENCE/prompt-input-skill-disabled.json" 2>"$EVIDENCE/prompt-input-skill-disabled.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" prompt-skill-disabled \
    "$EVIDENCE/prompt-input-skill-disabled.json"
  pass "Unselected repository Skill can be disabled for a run"
}

CODEX_EXEC_COMMON=(
  codex
  --ask-for-approval never
  exec
  --ignore-user-config
  --ignore-rules
  --ephemeral
  --skip-git-repo-check
  --json
  --sandbox read-only
  -C "$WORKSPACE"
  -c 'mcp_servers.m0-fixture.command="node"'
  -c "mcp_servers.m0-fixture.args=[\"$SCRIPT_DIR/fake-mcp-server.mjs\"]"
  -c 'mcp_servers.m0-fixture.env_vars=["M0_MCP_LOG","M0_SLOW_MS"]'
  -c 'mcp_servers.m0-fixture.required=true'
  -c 'mcp_servers.m0-fixture.startup_timeout_sec=5'
  -c 'mcp_servers.m0-fixture.default_tools_approval_mode="writes"'
)

MODEL_CONFIG=()

start_fake_model_provider() {
  local ready_file="$EVIDENCE/model-provider.port"
  local model_log="$EVIDENCE/model-provider.log"
  : >"$model_log"
  M0_MODEL_READY_FILE="$ready_file" M0_MODEL_LOG="$model_log" \
    node "$SCRIPT_DIR/fake-responses-server.mjs" \
    >"$EVIDENCE/model-provider.out" 2>"$EVIDENCE/model-provider.err" &
  MODEL_PID=$!

  for _ in $(seq 1 100); do
    if [[ -s "$ready_file" ]]; then
      break
    fi
    if ! kill -0 "$MODEL_PID" 2>/dev/null; then
      fail "fake Responses provider exited before becoming ready"
    fi
    sleep 0.1
  done
  if [[ ! -s "$ready_file" ]]; then
    fail "fake Responses provider did not become ready"
  fi

  local port
  port=$(<"$ready_file")
  MODEL_CONFIG=(
    -c 'model_provider="m0_local"'
    -c 'model="m0-model"'
    -c 'model_providers.m0_local.name="M0 Local Responses Fixture"'
    -c "model_providers.m0_local.base_url=\"http://127.0.0.1:$port/v1\""
    -c 'model_providers.m0_local.wire_api="responses"'
    -c 'model_providers.m0_local.supports_websockets=false'
    -c 'model_providers.m0_local.request_max_retries=0'
    -c 'model_providers.m0_local.stream_max_retries=0'
  )
  pass "Local deterministic Responses provider is ready"
}

run_live_success() {
  local mcp_log="$EVIDENCE/live-success.mcp.log"
  : >"$mcp_log"
  timeout 120s env HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" \
    M0_MCP_LOG="$mcp_log" "${CODEX_EXEC_COMMON[@]}" \
    "${MODEL_CONFIG[@]}" \
    -c 'mcp_servers.m0-fixture.enabled_tools=["read_fixture"]' \
    -c 'mcp_servers.m0-fixture.disabled_tools=["write_fixture","slow_read"]' \
    --output-schema "$WORKSPACE/result.schema.json" \
    --output-last-message "$EVIDENCE/live-success.result.json" \
    'Use $m0-harness-skill explicitly. Follow it exactly and return only the schema-conforming result.' \
    >"$EVIDENCE/live-success.events.jsonl" 2>"$EVIDENCE/live-success.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" live-success \
    "$EVIDENCE/live-success.events.jsonl" "$EVIDENCE/live-success.result.json" "$mcp_log" \
    "$EVIDENCE/model-provider.log"
  pass "Codex explicitly loads the Skill, calls only the read-only MCP tool, and returns schema output"
}

run_required_failure() {
  local exit_code
  set +e
  HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" timeout 20s \
    codex --ask-for-approval never exec \
    --ignore-user-config --ignore-rules --ephemeral --skip-git-repo-check \
    --json --sandbox read-only -C "$WORKSPACE" \
    -c 'mcp_servers.m0-broken.command="/definitely/missing/agent-web-m0-server"' \
    -c 'mcp_servers.m0-broken.required=true' \
    -c 'mcp_servers.m0-broken.startup_timeout_sec=1' \
    "${MODEL_CONFIG[@]}" \
    'Return the word unreachable.' \
    >"$EVIDENCE/required-failure.events.jsonl" 2>"$EVIDENCE/required-failure.err"
  exit_code=$?
  set -e
  if [[ $exit_code -eq 0 || $exit_code -eq 124 ]]; then
    fail "required MCP did not fail closed, exit code: $exit_code"
  fi
  node "$SCRIPT_DIR/verify-evidence.mjs" required-failure \
    "$EVIDENCE/required-failure.events.jsonl" "$EVIDENCE/required-failure.err"
  pass "required=true fails closed before continuing without the MCP server"
}

run_tool_timeout() {
  local mcp_log="$EVIDENCE/tool-timeout.mcp.log"
  : >"$mcp_log"
  timeout 90s env HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" \
    M0_MCP_LOG="$mcp_log" M0_SLOW_MS=3000 "${CODEX_EXEC_COMMON[@]}" \
    "${MODEL_CONFIG[@]}" \
    -c 'mcp_servers.m0-fixture.enabled_tools=["slow_read"]' \
    -c 'mcp_servers.m0-fixture.disabled_tools=["read_fixture","write_fixture"]' \
    -c 'mcp_servers.m0-fixture.tool_timeout_sec=0.5' \
    'Call the MCP tool slow_read exactly once. Do not use shell. If it times out, report the timeout briefly.' \
    >"$EVIDENCE/tool-timeout.events.jsonl" 2>"$EVIDENCE/tool-timeout.err"
  node "$SCRIPT_DIR/verify-evidence.mjs" tool-timeout \
    "$EVIDENCE/tool-timeout.events.jsonl" "$EVIDENCE/tool-timeout.err" "$mcp_log"
  pass "MCP tool timeout is observable and the turn reaches a terminal event"
}

run_cancellation() {
  local mcp_log="$EVIDENCE/cancel.mcp.log"
  local codex_pid=""
  local reached_tool=0
  local server_pid=""
  : >"$mcp_log"

  setsid env HOME="$ISOLATED_USER_HOME" CODEX_HOME="$ISOLATED_CODEX_HOME" \
    M0_MCP_LOG="$mcp_log" M0_SLOW_MS=60000 \
    codex --ask-for-approval never exec \
    --ignore-user-config --ignore-rules --ephemeral --skip-git-repo-check \
    --json --sandbox read-only -C "$WORKSPACE" \
    -c 'mcp_servers.m0-fixture.command="node"' \
    -c "mcp_servers.m0-fixture.args=[\"$SCRIPT_DIR/fake-mcp-server.mjs\"]" \
    -c 'mcp_servers.m0-fixture.env_vars=["M0_MCP_LOG","M0_SLOW_MS"]' \
    -c 'mcp_servers.m0-fixture.required=true' \
    -c 'mcp_servers.m0-fixture.enabled_tools=["slow_read"]' \
    -c 'mcp_servers.m0-fixture.disabled_tools=["read_fixture","write_fixture"]' \
    -c 'mcp_servers.m0-fixture.tool_timeout_sec=120' \
    "${MODEL_CONFIG[@]}" \
    'Call the MCP tool slow_read exactly once and wait for it. Do not use shell or other tools.' \
    >"$EVIDENCE/cancel.events.jsonl" 2>"$EVIDENCE/cancel.err" &
  codex_pid=$!

  for _ in $(seq 1 300); do
    if grep -q 'TOOL_CALL name=slow_read' "$mcp_log"; then
      reached_tool=1
      break
    fi
    if ! kill -0 "$codex_pid" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  if [[ $reached_tool -ne 1 ]]; then
    kill -TERM -- "-$codex_pid" 2>/dev/null || true
    wait "$codex_pid" 2>/dev/null || true
    fail "cancellation probe did not reach slow_read"
  fi

  server_pid=$(sed -n 's/.*SERVER_STARTED pid=\([0-9][0-9]*\).*/\1/p' "$mcp_log" | tail -n 1)
  kill -TERM -- "-$codex_pid"
  set +e
  wait "$codex_pid"
  local exit_code=$?
  set -e
  printf '%s\n' "$exit_code" >"$EVIDENCE/cancel.exit-code"

  for _ in $(seq 1 50); do
    if [[ -z "$server_pid" ]] || ! kill -0 "$server_pid" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    kill -KILL "$server_pid" 2>/dev/null || true
    fail "MCP child process survived process-group cancellation"
  fi
  node "$SCRIPT_DIR/verify-evidence.mjs" cancelled "$EVIDENCE/cancel.events.jsonl" "$mcp_log"
  pass "Process-group cancellation stops Codex/MCP; cancel intent must override exit code $exit_code"
}

run_live_tests() {
  start_fake_model_provider
  run_live_success
  run_required_failure
  run_tool_timeout
  run_cancellation
}

if [[ "$MODE" == "static" || "$MODE" == "all" ]]; then
  run_static_tests
fi
if [[ "$MODE" == "live" || "$MODE" == "all" ]]; then
  run_live_tests
fi

printf 'M0 SELF-TEST PASS - mode=%s checks=%d codex_home_untouched=%s\n' \
  "$MODE" "$PASS_COUNT" "$REAL_CODEX_HOME"
