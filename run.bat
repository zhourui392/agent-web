@echo off
REM Agent Web Service - Windows Run Script
REM This script builds and runs the agent-web service

setlocal enabledelayedexpansion

set PROJECT_NAME=agent-web
set VERSION=0.1.0-SNAPSHOT
set JAR_FILE=target\%PROJECT_NAME%-%VERSION%.jar
set MAIN_CLASS=com.example.agentweb.AgentWebApplication

echo ========================================
echo   Agent Web Service - Startup Script
echo ========================================
echo.

REM Check if Java is installed
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java is not installed or not in PATH
    echo Please install Java 8 or higher and try again
    pause
    exit /b 1
)

REM Display Java version
echo [INFO] Found Java:
java -version 2>&1 | findstr /i "version"
echo.

REM Check if Maven is installed
mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven is not installed or not in PATH
    echo Please install Maven and try again
    pause
    exit /b 1
)

REM Display Maven version
echo [INFO] Found Maven:
mvn -version 2>&1 | findstr /i "Apache Maven"
echo.

REM Check if JAR file exists
if not exist "%JAR_FILE%" (
    echo [WARN] JAR file not found. Building project...
    echo.
    call mvn clean package -DskipTests
    echo.

    if not exist "%JAR_FILE%" (
        echo [ERROR] Build failed, JAR file not found
        pause
        exit /b 1
    )
    echo [INFO] Build successful!
    echo.
) else (
    echo [INFO] JAR file found: %JAR_FILE%
    echo.
)

REM Run the application
echo ========================================
echo   Starting Agent Web Service...
echo ========================================
echo.
echo Server will start on port 18092
echo Press Ctrl+C to stop the service
echo.

java -jar "%JAR_FILE%" %*
