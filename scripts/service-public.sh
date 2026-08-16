#!/usr/bin/env bash
# agent-web 公网部署入口：作为同机 Caddy 的上游（docs/public-deployment.md）。
#
# 保持公网姿态：公网启动门禁开启（数据库仍是公开种子密码时，必须提供
# AGENT_BOOTSTRAP_ADMIN_PASSWORD，否则应用拒绝启动）、Secure Cookie 开启、
# 仅监听 loopback（127.0.0.1:18092，公网只经 Caddy 443 进入）。
# 共用构建/启停逻辑见 scripts/service-common.sh。
#
# @author zhourui(V33215020)

set -euo pipefail

: "${SERVER_ADDRESS:=127.0.0.1}"
: "${AGENT_PUBLIC_ACCESS_ENABLED:=true}"
: "${AGENT_AUTH_COOKIE_SECURE:=true}"
export SERVER_ADDRESS AGENT_PUBLIC_ACCESS_ENABLED AGENT_AUTH_COOKIE_SECURE

if [[ "${1:-start}" == "start" || "${1:-start}" == "restart" ]] \
    && [[ -z "${AGENT_BOOTSTRAP_ADMIN_PASSWORD:-}" ]]; then
    printf '%s\n' '提示: 未设置 AGENT_BOOTSTRAP_ADMIN_PASSWORD。若数据库仍是公开种子密码，' \
        '应用将拒绝启动（公网门禁）；已轮换过密码则可忽略本提示。' >&2
fi

SERVICE_ENTRY='./scripts/service-public.sh'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/service-common.sh"
service_main "$@"
