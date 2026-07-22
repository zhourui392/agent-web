#!/usr/bin/env bash
# @author zhourui(V33215020)

set -euo pipefail

readonly REQUIRED_JAVA_MAJOR=21

java_major_version() {
    local java_bin="$1"
    local version_line

    version_line=$("$java_bin" -version 2>&1 | head -n 1) || return 1
    if [[ "$version_line" =~ \"([0-9]+)(\.[^\"]*)?\" ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi
    return 1
}

resolve_java_bin() {
    local candidate="$1"

    if [[ "$candidate" == */* ]]; then
        [[ -x "$candidate" ]] || return 1
        readlink -f "$candidate"
        return 0
    fi
    command -v "$candidate" 2>/dev/null
}

jdk_home_for_java() {
    local candidate="$1"
    local java_bin
    local jdk_home

    java_bin=$(resolve_java_bin "$candidate") || return 1
    jdk_home=$(cd "$(dirname "$java_bin")/.." && pwd -P)
    [[ -x "$jdk_home/bin/javac" ]] || return 1
    printf '%s\n' "$jdk_home"
}

find_jdk() {
    local candidate
    local jdk_home
    local major
    local fallback_home=""
    local fallback_major=""
    local -a candidates=()
    local -A visited=()

    if [[ -n "${JAVA_BIN:-}" ]]; then
        jdk_home=$(jdk_home_for_java "$JAVA_BIN") || {
            printf 'JAVA_BIN does not point to a complete JDK: %s\n' "$JAVA_BIN" >&2
            return 1
        }
        major=$(java_major_version "$jdk_home/bin/java") || {
            printf 'Unable to determine Java version: %s\n' "$jdk_home/bin/java" >&2
            return 1
        }
        if (( major < REQUIRED_JAVA_MAJOR )); then
            printf 'JAVA_BIN requires JDK %d or later, but found JDK %d: %s\n' \
                "$REQUIRED_JAVA_MAJOR" "$major" "$jdk_home" >&2
            return 1
        fi
        printf '%s\n' "$jdk_home"
        return 0
    fi

    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
    if command -v java >/dev/null 2>&1; then
        candidates+=("$(command -v java)")
    fi

    candidates+=(
        "/usr/local/jdk-${REQUIRED_JAVA_MAJOR}/bin/java"
        "$HOME/.sdkman/candidates/java/current/bin/java"
    )

    shopt -s nullglob
    candidates+=(
        /usr/local/jdk-*/bin/java
        /usr/local/java/*/bin/java
        /usr/lib/jvm/*/bin/java
        /usr/java/*/bin/java
        /opt/jdk*/bin/java
        /opt/jdks/*/bin/java
        /opt/java/*/bin/java
        "$HOME"/.jdks/*/bin/java
        "$HOME"/.local/share/jdks/*/bin/java
        "$HOME"/.sdkman/candidates/java/*/bin/java
    )
    shopt -u nullglob

    for candidate in "${candidates[@]}"; do
        [[ -n "$candidate" ]] || continue
        jdk_home=$(jdk_home_for_java "$candidate" 2>/dev/null) || continue
        [[ -z "${visited[$jdk_home]:-}" ]] || continue
        visited["$jdk_home"]=1
        major=$(java_major_version "$jdk_home/bin/java" 2>/dev/null) || continue
        if (( major == REQUIRED_JAVA_MAJOR )); then
            printf '%s\n' "$jdk_home"
            return 0
        fi
        if (( major > REQUIRED_JAVA_MAJOR )) && [[ -z "$fallback_home" ]]; then
            fallback_home="$jdk_home"
            fallback_major="$major"
        fi
    done

    if [[ -n "$fallback_home" ]]; then
        printf 'JDK %d was not found; using compatible JDK %s at %s\n' \
            "$REQUIRED_JAVA_MAJOR" "$fallback_major" "$fallback_home" >&2
        printf '%s\n' "$fallback_home"
        return 0
    fi

    printf 'No complete JDK %d or later was found. Install JDK %d, or set JAVA_BIN to its java executable.\n' \
        "$REQUIRED_JAVA_MAJOR" "$REQUIRED_JAVA_MAJOR" >&2
    return 1
}

configure_jdk() {
    printf '[1/3] Locating an installed JDK %d or later...\n' "$REQUIRED_JAVA_MAJOR"
    JAVA_HOME=$(find_jdk)
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    printf 'Using JAVA_HOME=%s\n' "$JAVA_HOME"
}

readonly SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
readonly PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
readonly APP_DIR="$PROJECT_DIR/app"
readonly RUNTIME_JAR="$APP_DIR/agent-web.jar"
readonly PID_FILE="$APP_DIR/agent-web.pid"
readonly LOG_DIR="$PROJECT_DIR/logs"
readonly SERVICE_LOG="$LOG_DIR/service.log"

find_maven() {
    if [[ -x "$PROJECT_DIR/mvnw" ]]; then
        printf '%s\n' "$PROJECT_DIR/mvnw"
        return 0
    fi
    command -v mvn 2>/dev/null || {
        printf 'Maven was not found. Install Maven 3.6 or later and add mvn to PATH.\n' >&2
        return 1
    }
}

build_app() {
    local maven_bin
    local -a artifacts

    maven_bin=$(find_maven)
    printf '[2/3] Building the application with Maven...\n'
    (cd "$PROJECT_DIR" && "$maven_bin" clean package)

    shopt -s nullglob
    artifacts=("$PROJECT_DIR"/target/agent-web-*.jar)
    shopt -u nullglob
    if (( ${#artifacts[@]} != 1 )); then
        printf 'Expected one application JAR in target/, but found %d.\n' "${#artifacts[@]}" >&2
        return 1
    fi

    mkdir -p "$APP_DIR"
    cp -f "${artifacts[0]}" "$RUNTIME_JAR"
    printf 'Build complete: %s\n' "$RUNTIME_JAR"
}

read_pid() {
    [[ -f "$PID_FILE" ]] || return 1
    local pid
    pid=$(<"$PID_FILE")
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$pid"
}

is_service_process() {
    local pid="$1"
    local command_line

    kill -0 "$pid" 2>/dev/null || return 1
    [[ -r "/proc/$pid/cmdline" ]] || return 0
    command_line=$(tr '\0' ' ' < "/proc/$pid/cmdline")
    [[ "$command_line" == *"$RUNTIME_JAR"* ]]
}

running_pid() {
    local pid

    pid=$(read_pid 2>/dev/null) || return 1
    if is_service_process "$pid"; then
        printf '%s\n' "$pid"
        return 0
    fi
    rm -f "$PID_FILE"
    return 1
}

launch_app() {
    local pid
    local -a java_options=()
    local -a app_arguments=("$@")

    if pid=$(running_pid); then
        printf 'agent-web is already running (PID %s).\n' "$pid"
        return 0
    fi

    if [[ -n "${JAVA_OPTS:-}" ]]; then
        read -r -a java_options <<< "$JAVA_OPTS"
    fi

    mkdir -p "$APP_DIR" "$LOG_DIR"
    printf '[3/3] Starting agent-web...\n'
    nohup "$JAVA_HOME/bin/java" "${java_options[@]}" -jar "$RUNTIME_JAR" \
        "${app_arguments[@]}" >> "$SERVICE_LOG" 2>&1 &
    pid=$!
    printf '%s\n' "$pid" > "$PID_FILE"

    sleep 2
    if ! is_service_process "$pid"; then
        rm -f "$PID_FILE"
        printf 'agent-web exited during startup. Check %s\n' "$SERVICE_LOG" >&2
        return 1
    fi
    printf 'agent-web started (PID %s). Log: %s\n' "$pid" "$SERVICE_LOG"
}

start_service() {
    local pid

    if pid=$(running_pid); then
        printf 'agent-web is already running (PID %s).\n' "$pid"
        return 0
    fi
    build_app
    launch_app "$@"
}

stop_service() {
    local pid
    local attempt

    if ! pid=$(running_pid); then
        printf 'agent-web is not running.\n'
        return 0
    fi

    printf 'Stopping agent-web (PID %s)...\n' "$pid"
    kill "$pid"
    for ((attempt = 0; attempt < 30; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            printf 'agent-web stopped.\n'
            return 0
        fi
        sleep 1
    done

    printf 'Graceful shutdown timed out; forcing PID %s to stop.\n' "$pid" >&2
    kill -KILL "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
}

show_status() {
    local pid

    if pid=$(running_pid); then
        printf 'agent-web is running (PID %s).\n' "$pid"
        return 0
    fi
    printf 'agent-web is not running.\n'
    return 1
}

show_logs() {
    mkdir -p "$LOG_DIR"
    touch "$SERVICE_LOG"
    tail -n 200 -f "$SERVICE_LOG"
}

show_usage() {
    cat <<'EOF'
Usage: ./scripts/service.sh {build|start|stop|restart|status|logs} [application arguments]

Commands:
  build     Locate JDK 21+ and run Maven clean package.
  start     Build, then start agent-web in the background.
  stop      Stop the background agent-web process.
  restart   Stop, rebuild, and start agent-web.
  status    Show whether the managed process is running.
  logs      Follow logs/service.log.

Environment:
  JAVA_BIN  Explicit java executable used to locate the JDK.
  JAVA_OPTS JVM options, for example: -Xms512m -Xmx2g
EOF
}

command_name="${1:-start}"
if (( $# > 0 )); then
    shift
fi

case "$command_name" in
    build)
        configure_jdk
        build_app
        ;;
    start)
        configure_jdk
        start_service "$@"
        ;;
    stop)
        stop_service
        ;;
    restart)
        configure_jdk
        stop_service
        build_app
        launch_app "$@"
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs
        ;;
    help|-h|--help)
        show_usage
        ;;
    *)
        show_usage >&2
        exit 2
        ;;
esac
