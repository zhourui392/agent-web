#!/bin/bash

# Agent Web Service - Linux/macOS Run Script
# This script builds and runs the agent-web service

set -e

PROJECT_NAME="agent-web"
VERSION="0.1.0-SNAPSHOT"
JAR_FILE="target/${PROJECT_NAME}-${VERSION}.jar"
MAIN_CLASS="com.example.agentweb.AgentWebApplication"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "========================================"
echo "  Agent Web Service - Startup Script"
echo "========================================"
echo ""

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java is not installed or not in PATH${NC}"
    echo "Please install Java 8 or higher and try again"
    exit 1
fi

# Display Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
echo -e "${GREEN}Found Java:${NC} $JAVA_VERSION"
echo ""

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed or not in PATH${NC}"
    echo "Please install Maven and try again"
    exit 1
fi

# Display Maven version
MVN_VERSION=$(mvn -version | head -n 1)
echo -e "${GREEN}Found Maven:${NC} $MVN_VERSION"
echo ""

# Check if JAR file exists
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${YELLOW}JAR file not found. Building project...${NC}"
    echo ""
    mvn clean package -DskipTests
    echo ""

    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${RED}Error: Build failed, JAR file not found${NC}"
        exit 1
    fi
    echo -e "${GREEN}Build successful!${NC}"
    echo ""
else
    echo -e "${GREEN}JAR file found: $JAR_FILE${NC}"
    echo ""
fi

# Run the application
echo "========================================"
echo "  Starting Agent Web Service..."
echo "========================================"
echo ""
echo "Server will start on port 18092"
echo "Press Ctrl+C to stop the service"
echo ""

java -jar "$JAR_FILE" "$@"
