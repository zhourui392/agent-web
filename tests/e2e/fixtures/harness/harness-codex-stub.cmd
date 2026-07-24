@echo off
setlocal enabledelayedexpansion
if "%~1"=="--version" (
  echo codex-cli 0.145.0
  exit /b 0
)

set "schema="
set "output="
:args
if "%~1"=="" goto args_done
if "%~1"=="--output-schema" (
  set "schema=%~2"
  shift
  shift
  goto args
)
if "%~1"=="--output-last-message" (
  set "output=%~2"
  shift
  shift
  goto args
)
shift
goto args

:args_done
if not defined output exit /b 2
set "prompt_file=%TEMP%\harness-prompt-%RANDOM%-%RANDOM%.txt"
more > "%prompt_file%"
set "stage="
if defined schema (
  findstr /C:"ANALYSIS" "%schema%" >nul && set "stage=ANALYSIS"
  findstr /C:"DESIGN" "%schema%" >nul && set "stage=DESIGN"
  findstr /C:"IMPLEMENTATION" "%schema%" >nul && set "stage=IMPLEMENTATION"
  findstr /C:"DEPLOYMENT" "%schema%" >nul && set "stage=DEPLOYMENT"
) else (
  findstr /R /C:"^stage: ANALYSIS$" "%prompt_file%" >nul && set "stage=ANALYSIS"
  findstr /R /C:"^stage: DESIGN$" "%prompt_file%" >nul && set "stage=DESIGN"
  findstr /R /C:"^stage: IMPLEMENTATION$" "%prompt_file%" >nul && set "stage=IMPLEMENTATION"
  findstr /R /C:"^stage: DEPLOYMENT$" "%prompt_file%" >nul && set "stage=DEPLOYMENT"
)
if not defined stage exit /b 3
set "bundle=%~dp0bundles\%stage%.json"
if "%stage%"=="ANALYSIS" (
  findstr /C:"[E2E_GATE_FAIL]" "%prompt_file%" >nul && set "bundle=%~dp0bundles\ANALYSIS_FAIL.json"
)
if "%stage%"=="IMPLEMENTATION" (
  if not exist src\main mkdir src\main
  > src\main\A.java echo final class A { private A^(^) { } }
  echo {"type":"item.completed","item":{"type":"command_execution","command":"mvn -q -Dtest=RuleTest#red test","aggregated_output":"expected red failure","exit_code":1,"status":"failed"}}
  echo {"type":"item.completed","item":{"type":"command_execution","command":"mvn -q -Dtest=RuleTest#green test","aggregated_output":"green","exit_code":0,"status":"completed"}}
)
copy /Y "%bundle%" "%output%" >nul
del /Q "%prompt_file%" >nul 2>nul
echo {"type":"thread.started","thread_id":"harness-e2e"}
echo {"type":"turn.completed"}
exit /b 0
