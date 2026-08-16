#!/usr/bin/env bash
# agent-web 本机开发入口：明文 HTTP 直连 127.0.0.1:18092，不经过 Caddy。
#
# 内置本机开发必需的四项覆盖（均可在环境中预先导出以覆盖）：
#   AGENT_PUBLIC_ACCESS_ENABLED=false  关闭公网启动门禁（种子密码检查）
#   AGENT_AUTH_COOKIE_SECURE=false     明文 HTTP 下浏览器才回传会话 Cookie
#   AGENT_AUTH_COOKIE_NAME=local_session  与公网 __Host- 前缀 Cookie 隔离
#   SERVER_ADDRESS=127.0.0.1           仅监听 loopback
# 共用构建/启停逻辑见 scripts/service-common.sh。
#
# @author zhourui(V33215020)

set -euo pipefail

: "${SERVER_ADDRESS:=127.0.0.1}"
: "${AGENT_PUBLIC_ACCESS_ENABLED:=false}"
: "${AGENT_AUTH_COOKIE_SECURE:=false}"
: "${AGENT_AUTH_COOKIE_NAME:=local_session}"
export SERVER_ADDRESS AGENT_PUBLIC_ACCESS_ENABLED AGENT_AUTH_COOKIE_SECURE AGENT_AUTH_COOKIE_NAME

SERVICE_ENTRY='./scripts/service-local.sh'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/service-common.sh"
service_main "$@"
