#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
AGENTKIT_ROOT=${AGENTKIT_ROOT:-"$ROOT/../agent-langchain4j"}
SECRET_FILE=${AGENT_NATIVE_SECRET_FILE:-"$ROOT/data/secrets.properties"}
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export JAVA_HOME

fail() {
  printf 'native diagnosis release gate failed: %s\n' "$1" >&2
  exit 1
}

contains_literal() {
  local needle=$1
  local file=$2
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *"$needle"* ]] && return 0
  done < "$file"
  return 1
}

property() {
  local name=$1
  awk -F= -v key="$name" '
    $0 !~ /^[[:space:]]*#/ && $1 == key {
      sub(/^[^=]*=/, ""); print; exit
    }
  ' "$SECRET_FILE"
}

[[ -d "$AGENTKIT_ROOT/agentkit-agent-diagnosis" ]] \
  || fail "AgentKit repository was not found"
[[ -f "$SECRET_FILE" ]] || fail "secret properties file was not found"

mode=$(stat -c '%a' "$SECRET_FILE")
[[ "$mode" == "600" || "$mode" == "400" ]] \
  || fail "secret properties file permissions must be 0600 or 0400"

export AK_API_KEY=${AK_API_KEY:-$(property agent.native.api-key)}
export AK_BASE_URL=${AK_BASE_URL:-$(property agent.native.base-url)}
export AK_MODEL=${AK_MODEL:-$(property agent.native.model)}
AK_MODEL=${AK_MODEL:-gpt-5.6-sol}
export AK_MODEL

[[ -n "$AK_API_KEY" ]] || fail "Provider credential is missing"
[[ -n "$AK_BASE_URL" ]] || fail "Provider base URL is missing"

gate_logs=$(mktemp -d)
trap 'rm -rf -- "$gate_logs"' EXIT
provider_log="$gate_logs/provider-smoke.log"
host_log="$gate_logs/host-flow.log"

if ! (
  cd "$AGENTKIT_ROOT"
  mvn -B -ntp -pl agentkit-agent-diagnosis -am -Psmoke \
    -Dtest=__NoUnitTests__ \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dit.test=DiagnosisOpenAiProviderSmokeIT \
    verify
) >"$provider_log" 2>&1; then
  fail "Provider tool/evidence smoke did not pass"
fi

if contains_literal "$AK_API_KEY" "$provider_log"; then
  fail "Provider smoke log contains the credential"
fi
if contains_literal "$AK_BASE_URL" "$provider_log"; then
  fail "Provider smoke log contains the configured endpoint"
fi
if contains_literal "HTTP request:" "$provider_log" \
    || contains_literal "- body:" "$provider_log"; then
  fail "Provider request or response payload logging is enabled"
fi

if ! (
  cd "$ROOT"
  mvn -B -ntp \
    -Dtest=NativeLocalLogDiagnosisFlowTest \
    -Dtest.excludedGroups=live,git-integration,process-integration \
    test
) >"$host_log" 2>&1; then
  fail "agent-web Spring/SSE/SQLite diagnosis flow did not pass"
fi

if contains_literal "$AK_API_KEY" "$host_log"; then
  fail "agent-web flow log contains the credential"
fi

unset AK_API_KEY
printf 'native diagnosis release gate passed\n'
