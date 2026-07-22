---
name: m0-harness-skill
description: M0 Harness capability probe. Use only when explicitly asked to validate Codex Skill and read-only MCP integration.
---

# M0 Harness Skill Probe

When this skill is explicitly invoked:

1. Call the MCP tool `read_fixture` exactly once.
2. Do not call `write_fixture`, `slow_read`, shell commands, web search, or any other tool.
3. Read the marker returned by `read_fixture`.
4. Return a result containing `skill_marker` equal to `M0_SKILL_LOADED_V1` and `mcp_marker` equal to the MCP marker.
5. Follow the caller's output schema exactly and add no prose outside it.
