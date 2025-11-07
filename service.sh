#!/bin/bash

# Agent Web Service - Daemon Control Script
# Usage: ./service.sh {start|stop|restart|status|logs}

set -e

PROJECT_NAME="agent-web"
VERSION="0.1.0-SNAPSHOT"
JAR_FILE="target/${PROJECT_NAME}-${VERSION}.jar"
PID_FILE="agent-web.pid"
LOG_FILE="logs/agent-web.log"
LOG_DIR="logs"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Create logs directory if not exists
mkdir -p "$LOG_DIR"

# Function to get PID
get_pid() {
    if [ -f "$PID_FILE" ]; then
        cat "$PID_FILE"
    else
        echo ""
    fi
}

# Function to check if service is running
is_running() {
    local pid=$(get_pid)
    if [ -n "$pid" ] && ps -p "$pid" > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Function to start the service
start() {
    echo -e "${BLUE}========================================"
    echo "  Starting Agent Web Service"
    echo -e "========================================${NC}"

    if is_running; then
        local pid=$(get_pid)
        echo -e "${YELLOW}Service is already running (PID: $pid)${NC}"
        return 1
    fi

    # Check if JAR file exists
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${YELLOW}JAR file not found. Building project...${NC}"
        mvn clean package -DskipTests
        if [ ! -f "$JAR_FILE" ]; then
            echo -e "${RED}Error: Build failed${NC}"
            return 1
        fi
    fi

    # Start service in background
    echo -e "${GREEN}Starting service in background...${NC}"
    nohup java -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"

    # Wait a moment and check if started successfully
    sleep 3
    if is_running; then
        echo -e "${GREEN}✓ Service started successfully (PID: $pid)${NC}"
        echo -e "${GREEN}✓ Log file: $LOG_FILE${NC}"
        echo -e "${GREEN}✓ Server running on port 18092${NC}"
        return 0
    else
        echo -e "${RED}✗ Failed to start service${NC}"
        echo -e "${YELLOW}Check log file: $LOG_FILE${NC}"
        rm -f "$PID_FILE"
        return 1
    fi
}

# Function to stop the service
stop() {
    echo -e "${BLUE}========================================"
    echo "  Stopping Agent Web Service"
    echo -e "========================================${NC}"

    if ! is_running; then
        echo -e "${YELLOW}Service is not running${NC}"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid=$(get_pid)
    echo -e "${YELLOW}Stopping service (PID: $pid)...${NC}"

    # Try graceful shutdown first
    kill "$pid" 2>/dev/null || true

    # Wait up to 30 seconds for graceful shutdown
    local count=0
    while [ $count -lt 30 ]; do
        if ! is_running; then
            echo -e "${GREEN}✓ Service stopped successfully${NC}"
            rm -f "$PID_FILE"
            return 0
        fi
        sleep 1
        count=$((count + 1))
    done

    # Force kill if still running
    if is_running; then
        echo -e "${YELLOW}Forcing service to stop...${NC}"
        kill -9 "$pid" 2>/dev/null || true
        sleep 1
        if ! is_running; then
            echo -e "${GREEN}✓ Service stopped (forced)${NC}"
            rm -f "$PID_FILE"
            return 0
        else
            echo -e "${RED}✗ Failed to stop service${NC}"
            return 1
        fi
    fi
}

# Function to restart the service
restart() {
    echo -e "${BLUE}========================================"
    echo "  Restarting Agent Web Service"
    echo -e "========================================${NC}"

    stop
    sleep 2
    start
}

# Function to check service status
status() {
    echo -e "${BLUE}========================================"
    echo "  Agent Web Service Status"
    echo -e "========================================${NC}"

    if is_running; then
        local pid=$(get_pid)
        echo -e "${GREEN}✓ Service is running${NC}"
        echo -e "  PID: $pid"
        echo -e "  Log: $LOG_FILE"
        echo -e "  Port: 18092"
        echo ""

        # Show process info
        if command -v ps &> /dev/null; then
            echo -e "${BLUE}Process Info:${NC}"
            ps -f -p "$pid" 2>/dev/null || true
        fi

        # Show recent logs
        if [ -f "$LOG_FILE" ]; then
            echo ""
            echo -e "${BLUE}Recent Logs (last 10 lines):${NC}"
            tail -n 10 "$LOG_FILE"
        fi
    else
        echo -e "${RED}✗ Service is not running${NC}"
        if [ -f "$PID_FILE" ]; then
            echo -e "${YELLOW}Warning: Stale PID file found (removing)${NC}"
            rm -f "$PID_FILE"
        fi
    fi
}

# Function to tail logs
logs() {
    if [ ! -f "$LOG_FILE" ]; then
        echo -e "${RED}Log file not found: $LOG_FILE${NC}"
        return 1
    fi

    echo -e "${BLUE}========================================"
    echo "  Agent Web Service Logs"
    echo -e "========================================${NC}"
    echo -e "${YELLOW}Press Ctrl+C to exit${NC}"
    echo ""

    tail -f "$LOG_FILE"
}

# Function to show usage
usage() {
    echo "Usage: $0 {start|stop|restart|status|logs}"
    echo ""
    echo "Commands:"
    echo "  start    - Start the service in background"
    echo "  stop     - Stop the running service"
    echo "  restart  - Restart the service"
    echo "  status   - Check service status"
    echo "  logs     - Tail service logs"
    echo ""
    exit 1
}

# Main script logic
case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    *)
        usage
        ;;
esac
