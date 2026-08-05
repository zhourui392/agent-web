#!/bin/sh
set -eu

if [ "${1:-}" = "--version" ]; then
  printf '%s\n' 'codex-cli 0.145.0'
  exit 0
fi

prompt=$(cat)
printf '%s\n' '{"type":"thread.started","thread_id":"workbench-real-e2e"}'
printf '%s\n' '{"type":"item.completed","item":{"id":"message-1","type":"agent_message","text":"真实 Runtime 已读取本轮冻结的 Workbench 执行计划。"}}'
printf '%s\n' '{"type":"item.started","item":{"id":"command-1","type":"command_execution","command":"git status --short","aggregated_output":"","exit_code":null,"status":"in_progress"}}'

case "$prompt" in
  *'[E2E_RESTART_RECOVERY_WAIT]'*)
    restart_log=.agent-web-restart-runtime-invocations
    if [ ! -s "$restart_log" ]; then
      printf '%s\n' 'first-write-runtime-started' >> "$restart_log"
      runtime_parent=$PPID
      while kill -0 "$runtime_parent" 2>/dev/null; do
        sleep 1
      done
      exit 0
    fi
    printf '%s\n' 'subsequent-write-runtime-started' >> "$restart_log"
    ;;
  *'[E2E_WAIT_FOR_STOP]'*)
    sleep 30
    ;;
  *'[E2E_RELOAD]'*)
    sleep 3
    ;;
  *'[E2E_STAGE_MODIFY_TEST]'*)
    printf '%s\n' 'Stage modification applied from the frozen Dynamic Stage.' > stage-e2e.txt
    printf '%s\n' '{"type":"item.completed","item":{"id":"file-stage-1","type":"file_change","changes":[{"path":"stage-e2e.txt","kind":"add"}],"status":"completed"}}'
    printf '%s\n' '{"type":"item.started","item":{"id":"test-command-1","type":"command_execution","command":"mvn -q -Dtest=StageRuntimeContractTest test","aggregated_output":"","exit_code":null,"status":"in_progress"}}'
    printf '%s\n' '{"type":"item.completed","item":{"id":"test-command-1","type":"command_execution","command":"mvn -q -Dtest=StageRuntimeContractTest test","aggregated_output":"affected stage tests passed","exit_code":0,"status":"completed"}}'
    printf '%s\n' '{"type":"item.completed","item":{"id":"message-stage-1","type":"agent_message","text":"已按冻结 Stage 规则完成工作区修改和受影响测试。"}}'
    ;;
esac

printf '%s\n' '{"type":"item.completed","item":{"id":"command-1","type":"command_execution","command":"git status --short","aggregated_output":"工作区状态已核对","exit_code":0,"status":"completed"}}'
printf '%s\n' '{"type":"item.completed","item":{"id":"message-2","type":"agent_message","text":"Workbench 真实后端运行完成。"}}'
printf '%s\n' '{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":8}}'
