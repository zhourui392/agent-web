@echo off
setlocal EnableExtensions EnableDelayedExpansion

if "%~1"=="--version" (
  echo codex-cli 0.145.0
  exit /b 0
)

set "prompt=%TEMP%\agent-web-workbench-prompt-%RANDOM%-%RANDOM%.txt"
more > "%prompt%"
echo {"type":"thread.started","thread_id":"workbench-real-e2e"}
echo {"type":"item.completed","item":{"id":"message-1","type":"agent_message","text":"真实 Runtime 已读取本轮冻结的 Workbench 执行计划。"}}
echo {"type":"item.started","item":{"id":"command-1","type":"command_execution","command":"git status --short","aggregated_output":"","exit_code":null,"status":"in_progress"}}

findstr /C:"[E2E_WAIT_FOR_STOP]" "%prompt%" >nul && ping -n 31 127.0.0.1 >nul
findstr /C:"[E2E_RELOAD]" "%prompt%" >nul && ping -n 4 127.0.0.1 >nul
findstr /C:"[E2E_STAGE_MODIFY_TEST]" "%prompt%" >nul
if not errorlevel 1 (
  >"stage-e2e.txt" echo Stage modification applied from the frozen Dynamic Stage.
  echo {"type":"item.completed","item":{"id":"file-stage-1","type":"file_change","changes":[{"path":"stage-e2e.txt","kind":"add"}],"status":"completed"}}
  echo {"type":"item.started","item":{"id":"test-command-1","type":"command_execution","command":"mvn -q -Dtest=StageRuntimeContractTest test","aggregated_output":"","exit_code":null,"status":"in_progress"}}
  echo {"type":"item.completed","item":{"id":"test-command-1","type":"command_execution","command":"mvn -q -Dtest=StageRuntimeContractTest test","aggregated_output":"affected stage tests passed","exit_code":0,"status":"completed"}}
  echo {"type":"item.completed","item":{"id":"message-stage-1","type":"agent_message","text":"已按冻结 Stage 规则完成工作区修改和受影响测试。"}}
)

del /q "%prompt%" >nul 2>nul
echo {"type":"item.completed","item":{"id":"command-1","type":"command_execution","command":"git status --short","aggregated_output":"工作区状态已核对","exit_code":0,"status":"completed"}}
echo {"type":"item.completed","item":{"id":"message-2","type":"agent_message","text":"Workbench 真实后端运行完成。"}}
echo {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":8}}
exit /b 0
