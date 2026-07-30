#!/bin/bash
# E2E 启动脚本：同时启动前端 vite preview + 后端 Spring Boot。
# 前端分离后 Spring Boot 不再提供静态文件，vite preview 提供 frontend/dist/ 并代理 /api 到后端。
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FRONTEND_DIR="$REPO_ROOT/frontend"
VITE_PORT="${VITE_PORT:-5174}"

# 1. 清 e2e db
node "$SCRIPT_DIR/e2e-clean.js"

# 2. 构建前端
cd "$FRONTEND_DIR"
npm run build

# 3. 后台启动 vite preview（提供前端静态文件 + /api 代理到后端）
npx vite preview --port "$VITE_PORT" &
VITE_PID=$!

# 4. 等待 vite preview 启动
sleep 3

# 5. 前台启动后端，测试结束后自动杀掉 vite preview
trap "kill $VITE_PID 2>/dev/null" EXIT
ACTIVE_SPRING_PROFILES="${SPRING_PROFILES:-e2e}"
unset SPRING_PROFILES
cd "$REPO_ROOT"
mvn org.springframework.boot:spring-boot-maven-plugin:run \
  -Dspring-boot.run.profiles="$ACTIVE_SPRING_PROFILES" \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8