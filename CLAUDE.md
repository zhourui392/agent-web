# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot web service that provides a browser UI for driving local CLI AI agents (Claude / Codex). Users interact via chat interface; the backend spawns CLI subprocesses and streams responses back via SSE.

## Build & Run Commands

```bash
mvn clean package                # Build JAR
mvn spring-boot:run              # Run locally (port 17988)
mvn test                         # Run all tests
mvn test -Dtest=ChatFlowTest     # Run single test class
mvn test -Dtest=ChatFlowTest#testMethodName  # Run single test method
mvn pmd:check                    # Code quality check (Alibaba P3C)
./service.sh start|stop|restart|status|logs  # Daemon control
```

## Tech Stack

- **Backend**: Spring Boot 2.7.18 / Java 8+ / Maven
- **Database**: SQLite (session + scheduled task persistence)
- **Frontend**: Vue 3 + Element Plus via CDN (no build step, static HTML/JS/CSS in `src/main/resources/static/`)
- **Communication**: REST + Server-Sent Events (SSE)
- **Code Quality**: Alibaba P3C (PMD plugin)

## Architecture

DDD + Hexagonal Architecture with four layers:

```
interfaces/   → REST Controllers + DTOs (API boundary)
app/          → Application services (orchestration, no domain logic)
domain/       → Aggregate roots, value objects, repository interfaces
infra/        → CLI process execution, SQLite repos, auth filter, config properties
adapter/      → Port interfaces (AgentGateway)
```

### Key Data Flow: Chat Message

1. `ChatController` receives message → delegates to `ChatAppServiceImpl`
2. `ChatAppServiceImpl` manages `ChatSession` lifecycle, calls `AgentGateway`
3. `AgentCliGateway` spawns CLI subprocess (`claude --print --output-format stream-json ...`), reads line-by-line JSON
4. `StreamChunkHandler` processes chunks → SSE events pushed to browser via `SseEmitter`

### Storage Tiers

- **L1**: `InMemorySessionRepo` — fast lookup for active sessions
- **L2**: `SqliteSessionRepo` — persistent across restarts
- **Backup**: `JsonFileSessionRepo` — JSON file export/import

### Slash Commands

`FileSlashCommandScanner` scans `.claude/commands/` and `.claude/skills/` for `.md` files with YAML frontmatter. `SlashCommandExpander` replaces `/cmd args` with template body at send time.

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CLAUDE_CLI_CMD` | `claude` | Claude CLI executable path |
| `CODEX_CMD` | `codex` | Codex CLI executable path |
| `AGENT_DB_PATH` | `data/agent-web.db` | SQLite database path |

## Testing

JUnit 5 + MockMvc integration tests with `@SpringBootTest`. Tests use `@Transactional` for isolation. Test files are in `src/test/java/com/example/agentweb/`.
