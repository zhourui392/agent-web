#!/bin/sh
set -eu

if [ "${1:-}" = "--version" ]; then
  printf '%s\n' 'codex-cli 0.145.0'
  exit 0
fi

schema=''
output=''
previous=''
for argument in "$@"; do
  if [ "$previous" = '--output-schema' ]; then
    schema=$argument
  elif [ "$previous" = '--output-last-message' ]; then
    output=$argument
  fi
  previous=$argument
done

if [ -z "$schema" ] || [ -z "$output" ]; then
  printf '%s\n' 'missing output contract paths' >&2
  exit 2
fi

prompt=$(cat)
stage=''
for candidate in ANALYSIS DESIGN IMPLEMENTATION DEPLOYMENT; do
  if grep -q "\"const\":\"$candidate\"" "$schema"; then
    stage=$candidate
    break
  fi
done
if [ -z "$stage" ]; then
  printf '%s\n' 'could not resolve stage from output schema' >&2
  exit 3
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bundle="$script_dir/bundles/$stage.json"
if [ "$stage" = 'ANALYSIS' ] && printf '%s' "$prompt" | grep -q '\[E2E_GATE_FAIL\]'; then
  bundle="$script_dir/bundles/ANALYSIS_FAIL.json"
fi

if [ "$stage" = 'IMPLEMENTATION' ]; then
  mkdir -p src/main
  printf '%s\n' 'final class A { private A() { } }' > src/main/A.java
  printf '%s\n' '{"type":"item.completed","item":{"type":"command_execution","command":"mvn -q -Dtest=RuleTest#red test","aggregated_output":"expected red failure","exit_code":1,"status":"failed"}}'
  printf '%s\n' '{"type":"item.completed","item":{"type":"command_execution","command":"mvn -q -Dtest=RuleTest#green test","aggregated_output":"green","exit_code":0,"status":"completed"}}'
fi

cp "$bundle" "$output"
printf '%s\n' '{"type":"thread.started","thread_id":"harness-e2e"}'
printf '%s\n' '{"type":"turn.completed"}'
