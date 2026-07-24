#!/usr/bin/env bash

set -euo pipefail
set +x

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
AUTH_MODE=${AGENT_HARNESS_ONLINE_AUTH_MODE:-local-login}
REFERENCE=${AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE:-}
CODEX_COMMAND=${AGENT_HARNESS_CODEX_COMMAND:-codex}
TEMP_ROOT=""
PROVIDER_CREDENTIAL=""
ISOLATED_CODEX_HOME=false

configuration_error() {
  printf 'FAIL - %s\n' "$1" >&2
  exit 2
}

fail() {
  printf 'FAIL - %s\n' "$1" >&2
  exit 1
}

cleanup() {
  PROVIDER_CREDENTIAL=""
  if [[ -z "$TEMP_ROOT" || ! -e "$TEMP_ROOT" ]]; then
    return
  fi
  node -e '
    const fs = require("fs");
    const path = require("path");
    const target = path.resolve(process.argv[1]);
    if (!/^\/(tmp|var\/tmp)\/agent-web-harness-m4-online\.[^/]+$/.test(target)) {
      throw new Error(`unsafe cleanup path: ${target}`);
    }
    fs.rmSync(target, { recursive: true, force: true });
  ' "$TEMP_ROOT"
}

case "$AUTH_MODE" in
  local-login)
    ;;
  isolated-key)
    ISOLATED_CODEX_HOME=true
    [[ -n "$REFERENCE" ]] \
      || configuration_error "provider credential reference is required"
    [[ "$REFERENCE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
      || configuration_error "provider credential reference is invalid"
    PROVIDER_CREDENTIAL=${!REFERENCE-}
    [[ -n "$PROVIDER_CREDENTIAL" ]] \
      || configuration_error "referenced provider credential is unavailable"
    unset "$REFERENCE"
    ;;
  *)
    configuration_error "online authentication mode is invalid"
    ;;
esac

trap cleanup EXIT

command -v "$CODEX_COMMAND" >/dev/null 2>&1 || fail "codex is unavailable"
command -v node >/dev/null 2>&1 || fail "node is unavailable"
command -v git >/dev/null 2>&1 || fail "git is unavailable"
command -v rg >/dev/null 2>&1 || fail "rg is unavailable"
command -v timeout >/dev/null 2>&1 || fail "timeout is unavailable"

"$CODEX_COMMAND" --version | rg -qx 'codex-cli 0\.145\.0' \
  || fail "Codex version is outside the verified compatibility baseline"

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/var/tmp}/agent-web-harness-m4-online.XXXXXX")
ISOLATED_HOME="$TEMP_ROOT/home"
WORKSPACE="$TEMP_ROOT/workspace"
mkdir -p "$ISOLATED_HOME" "$WORKSPACE"
git -C "$WORKSPACE" init -q

STAGES=(ANALYSIS DESIGN IMPLEMENTATION DEPLOYMENT)
SLUGS=(analysis design implementation deployment)
SKILLS=(domain-modeling-audit java-ddd-design java-tdd release-verification)
EVIDENCE=(
  "提取业务不变量、强一致性边界和变化点"
  "保持 Interface、Application、Domain、Infrastructure 分层"
  "先按分层门禁定位业务规则，再写失败测试"
  "核对批准版本、Preflight、构建与验收证据"
)

for index in "${!STAGES[@]}"; do
  stage=${STAGES[$index]}
  slug=${SLUGS[$index]}
  skill=${SKILLS[$index]}
  expected=${EVIDENCE[$index]}
  pack="$PROJECT_DIR/src/main/resources/harness/prompt-packs/$slug/1.0.0"
  skill_entry="$PROJECT_DIR/src/main/resources/harness/skills/$skill/1.0.0/SKILL.md"
  prompt="$TEMP_ROOT/$slug.prompt.md"
  schema="$TEMP_ROOT/$slug.schema.json"
  result="$TEMP_ROOT/$slug.result.json"
  events="$TEMP_ROOT/$slug.events.jsonl"

  [[ -f "$pack/system.md" && -f "$pack/task.md" && -f "$pack/gate-hints.md" \
    && -f "$skill_entry" ]] || fail "stage prompt or Skill package is unavailable"

  {
    printf '## PLATFORM_SAFETY\n只读在线兼容性验证；不得修改文件、运行命令或访问未授权能力。\n\n'
    printf '## STAGE_CONTRACT\nstage: %s\n\n' "$stage"
    printf '## STAGE_SYSTEM\n'
    sed -n '1,240p' "$pack/system.md"
    printf '\n\n## STAGE_TASK\n'
    sed -n '1,240p' "$pack/task.md"
    printf '\n\n## STAGE_GATE_HINTS\n'
    sed -n '1,240p' "$pack/gate-hints.md"
    printf '\n\n## SELECTED_SKILLS\n### %s@1.0.0\n' "$skill"
    sed -n '1,240p' "$skill_entry"
    printf '\n\n## CURRENT_INPUT\n这是在线 Provider 只读兼容性验证。'
    printf '把所选 Skill 标题下的唯一一句正文原样复制到 skillEvidence；不要使用工具。\n'
    printf '\n## LIVE_SMOKE_OUTPUT_CONTRACT\n只返回 JSON 对象，不要 Markdown 代码块或解释。'
    printf '对象只能包含 stage 和 skillEvidence；stage 必须精确为 %s，' "$stage"
    printf 'skillEvidence 必须是复制出的非空字符串。\n'
  } >"$prompt"

  STAGE="$stage" node -e '
    const fs = require("fs");
    const stage = process.env.STAGE;
    fs.writeFileSync(process.argv[1], JSON.stringify({
      type: "object",
      additionalProperties: false,
      required: ["stage", "skillEvidence"],
      properties: {
        stage: { const: stage },
        skillEvidence: { type: "string", minLength: 1, maxLength: 500 },
      },
    }));
  ' "$schema"

  if [[ "$AUTH_MODE" == "local-login" ]]; then
    timeout 180s "$CODEX_COMMAND" --ask-for-approval never exec \
        --ignore-rules --ephemeral --json \
        --skip-git-repo-check --sandbox read-only -C "$WORKSPACE" \
        --output-last-message "$result" - \
        <"$prompt" >"$events" 2>"$TEMP_ROOT/$slug.err" \
      || fail "online Provider stage verification failed: $stage"
  else
    env -i \
      PATH="${PATH:-/usr/bin:/bin}" \
      HOME="$ISOLATED_HOME" \
      CODEX_HOME="$ISOLATED_HOME" \
      XDG_CONFIG_HOME="$ISOLATED_HOME" \
      OPENAI_API_KEY="$PROVIDER_CREDENTIAL" \
      timeout 180s "$CODEX_COMMAND" --ask-for-approval never exec \
        --ignore-user-config --ignore-rules --ephemeral --json \
        --skip-git-repo-check --sandbox read-only -C "$WORKSPACE" \
        --output-schema "$schema" --output-last-message "$result" - \
        <"$prompt" >"$events" 2>"$TEMP_ROOT/$slug.err" \
      || fail "online Provider stage verification failed: $stage"
  fi

  STAGE="$stage" EXPECTED="$expected" SCAN_SECRET="$PROVIDER_CREDENTIAL" node -e '
    const fs = require("fs");
    const result = fs.readFileSync(process.argv[1], "utf8");
    const events = fs.readFileSync(process.argv[2], "utf8");
    const parsed = JSON.parse(result);
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("stage output must be an object");
    }
    const fields = Object.keys(parsed).sort();
    if (fields.length !== 2 || fields[0] !== "skillEvidence" || fields[1] !== "stage") {
      throw new Error("stage output fields mismatch");
    }
    if (parsed.stage !== process.env.STAGE) {
      throw new Error("stage output mismatch");
    }
    if (typeof parsed.skillEvidence !== "string"
        || parsed.skillEvidence.length < 1 || parsed.skillEvidence.length > 500) {
      throw new Error("selected Skill evidence must be a bounded string");
    }
    if (!parsed.skillEvidence.includes(process.env.EXPECTED)) {
      throw new Error("selected Skill evidence mismatch");
    }
    if (!events.includes("turn.completed")) {
      throw new Error("Codex turn did not complete");
    }
    const secret = process.env.SCAN_SECRET;
    if (secret && (result.includes(secret) || events.includes(secret))) {
      throw new Error("provider credential leaked into output");
    }
  ' "$result" "$events" || fail "online Provider evidence validation failed: $stage"

  printf 'PASS - online Provider received %s prompt and %s Skill\n' "$stage" "$skill"
done

printf 'M4 ONLINE PROVIDER PASS - stages=4 auth_mode=%s isolated_codex_home=%s\n' \
  "$AUTH_MODE" "$ISOLATED_CODEX_HOME"
