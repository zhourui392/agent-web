#!/usr/bin/env bash
# agent-web 兼容入口：不预设运行模式，行为完全由环境变量与 application.yml 默认值决定。
# 本机开发请用 scripts/service-local.sh；公网部署请用 scripts/service-public.sh。
#
# @author zhourui(V33215020)

set -euo pipefail

SERVICE_ENTRY='./scripts/service.sh'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/service-common.sh"
service_main "$@"
