# 对话工具调用独立持久化与历史迁移设计方案

> 当前状态：已实施；本文保留设计约束与迁移规则。独立工具调用表、旁路写入和历史投影以当前代码与测试为准，本文中的改造前描述仅作为历史基线。

## 1. 背景与目标

当前 Claude Code 与 Codex 的流式输出经过方言归一化后，以 NDJSON 形式整体保存在 `chat_message.content`。工具调用、参数和执行结果与普通文本混在同一个字段中，前端通过重新解析流事件还原工具调用展示。

本方案目标：

1. 将每一次对话内的工具调用独立写入 `chat_tool_invocation` 表；
2. 同时支持 Claude Code 与 Codex 不同的工具事件协议；
3. 将 Skill 作为可识别的调用类别，并保存 Skill 名称；
4. 区分 agent 自主调用 Skill 与用户通过 slash 命令显式指定 Skill；
5. 对存量 `chat_message.content` 执行幂等、可恢复的历史迁移；
6. 永久保持现有消息存储、SSE、`chat_message.content` 工具流事件和前端渲染逻辑不变；新表始终作为旁路结构化数据源；
7. 为后续按会话、run、原生调用类型、工具、Skill 和状态查询提供结构化数据基础。

## 2. 永久兼容约束与非目标

以下是长期约束，而非仅限第一阶段：

- 永久保留 `chat_message.content` 中已有和未来产生的工具流事件；
- 永久保持现有前端 NDJSON 解析与工具渲染逻辑，不切换为从新表渲染；
- 新表只服务于结构化查询、审计、统计和后端分析，不成为聊天内容的唯一事实源；
- 新表写入失败时默认降级，不能影响原消息落库、SSE 或前端展示；
- 不保存所有 CLI 原始事件的法证级副本；
- 不从已经被截断的历史内容中恢复完整工具输出；
- 不将普通 slash command 错误归类为 Skill；
- 不根据不可靠文本推断无法确认的历史 Skill 名称。

## 3. 当前实现与数据事实

### 3.1 当前数据链路

```text
CLI 原始事件
  → AgentGateway.normalizeChunk()
  → StreamChunkHandler
  → Claude 兼容的归一化 NDJSON
  → chat_message.content
  → 前端 parseStreamJson() 重建 text/tool 段
```

关键代码：

- `src/main/java/com/example/agentweb/app/StreamChunkHandler.java`
- `src/main/java/com/example/agentweb/infra/cli/CodexEventNormalizer.java`
- `src/main/java/com/example/agentweb/app/chatrun/ChatRunExecutor.java`
- `frontend/js/lib/formatters.ts`

`StreamChunkHandler` 目前负责：

1. 从原始事件提取 CLI resume ID；
2. 调用 `AgentGateway.normalizeChunk()`；
3. 将归一化事件发给 SSE；
4. 累积归一化事件；
5. run 结束时整体保存为 assistant 消息。

当前没有独立工具调用表。

### 3.2 Claude Code 工具事件

一次 Claude Code 调用通常由三类事件组成。

调用开始：

```json
{
  "type": "stream_event",
  "event": {
    "type": "content_block_start",
    "content_block": {
      "type": "tool_use",
      "id": "toolu_xxx",
      "name": "Read",
      "input": {}
    }
  }
}
```

参数增量：

```json
{
  "type": "stream_event",
  "event": {
    "type": "content_block_delta",
    "delta": {
      "type": "input_json_delta",
      "partial_json": "{\"file_path\":\"/...\"}"
    }
  }
}
```

调用结果：

```json
{
  "type": "user",
  "message": {
    "content": [
      {
        "type": "tool_result",
        "tool_use_id": "toolu_xxx",
        "content": "...",
        "is_error": false
      }
    ]
  }
}
```

必须使用 `tool_use.id` 聚合参数，并通过 `tool_use_id` 关联结果。`partial_json` 是 JSON 字符串片段，必须按事件顺序拼接后整体解析，不能逐片段解析。

### 3.3 Codex 工具事件

Codex `exec --json` 使用 item 事件，不直接输出 Claude 的 `tool_use`。

调用开始：

```json
{
  "type": "item.started",
  "item": {
    "id": "item_1",
    "type": "command_execution",
    "command": "...",
    "status": "in_progress"
  }
}
```

调用完成：

```json
{
  "type": "item.completed",
  "item": {
    "id": "item_1",
    "type": "command_execution",
    "command": "...",
    "aggregated_output": "...",
    "exit_code": 0,
    "status": "completed"
  }
}
```

当前 `CodexEventNormalizer` 为了兼容现有前端渲染契约，将其映射为：

- 展示层兼容名称：`shell`；
- 输入：`{"command":"..."}`；
- 结果：Claude 兼容的 `tool_result`。

这里的 `shell` 只是现有 NDJSON/前端协议使用的展示占位符，不代表 Codex 调用了一个名为 `shell` 或 `Bash` 的工具。Codex 原生语义是执行了一个 `command_execution` item。

新表必须直接保存该原生语义：

```text
invocation_kind   = COMMAND_EXECUTION
tool_name         = null
provider_item_type = command_execution
```

不能因为命令文本以 `bash ...` 开头，就将 `bash` 当成工具名。此时 `bash` 只是被执行命令的可执行程序或 shell 解释器，完整内容仍保存在 `input_json.command`。同理，`powershell.exe`、`cmd`、`sh`、`python` 等也不是 Codex 工具名。

如果新表只解析归一化事件，不仅会丢失 Codex 原始的 `exit_code`、provider status 和 item type，还会把兼容展示名称误当成真实工具。因此，新数据采集必须访问原始 Codex 事件。

### 3.4 Skill 的两种调用方式

#### Agent 主动调用

Claude Code 会发出名为 `Skill` 的工具调用，完整参数通常为：

```json
{
  "skill": "trace-issue",
  "args": "..."
}
```

归一化记录：

```text
invocation_kind = SKILL
tool_name       = Skill
skill_name      = trace-issue
trigger_source  = AGENT
```

#### 用户通过 slash 命令显式调用

用户输入：

```text
/trace-issue 12345
```

当前 `SlashCommandExpander` 在将 prompt 发送给 CLI 前展开 Skill 正文。这个过程通常不会产生 `tool_use(name=Skill)`，因此必须由平台在 prompt 展开时单独记录：

```text
invocation_kind = SKILL
tool_name       = Skill
skill_name      = trace-issue
trigger_source  = USER_SLASH
```

普通 `.claude/commands/*.md` slash command 不是 Skill，不应写成 Skill 调用。`SlashCommand.skill` 已能区分来源：

- `.claude/commands/*.md`：普通 command；
- `.claude/skills/*/SKILL.md`：Claude Skill；
- `.codex/skills/*/SKILL.md`：Codex Skill。

## 4. 总体架构

```text
                             ┌──────────────────────────┐
Claude 原始事件 ─────────────→ ClaudeToolInvocationExtractor
                             └────────────┬─────────────┘
                                          │
                                          ▼
                             ToolInvocationEvent
                                          │
                                          ▼
                             ChatToolInvocationTracker
                                          │
                                          ▼
                             ToolInvocationRepository
                                          │
                                          ▼
                             chat_tool_invocation
                                          ▲
                                          │
                             ToolInvocationEvent
                                          ▲
                                          │
Codex 原始事件 ──────────────→ CodexToolInvocationExtractor

用户 /skill ─→ SlashCommandExpander ─→ ExplicitSkillInvocation ─┘
```

设计原则：

1. 方言层解析 provider 协议；
2. 应用层聚合调用生命周期；
3. 领域模型维护状态约束；
4. Infra Repository 负责 SQLite 幂等写入；
5. `StreamChunkHandler` 不直接包含 Claude/Codex JSON 分支；
6. 原有消息 NDJSON 继续写入，形成兼容双写。

## 5. 数据模型

### 5.1 主表

```sql
CREATE TABLE IF NOT EXISTS chat_tool_invocation (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,

    session_id            TEXT    NOT NULL,
    run_id                TEXT,
    assistant_message_id  INTEGER,

    provider              TEXT    NOT NULL,
    provider_call_id      TEXT,
    invocation_index      INTEGER NOT NULL,

    invocation_kind       TEXT    NOT NULL,
    tool_name             TEXT,
    skill_name            TEXT,
    trigger_source        TEXT    NOT NULL,

    input_json             TEXT,
    output_text            TEXT,

    status                 TEXT    NOT NULL,
    is_error               INTEGER NOT NULL DEFAULT 0,
    exit_code              INTEGER,

    provider_item_type     TEXT,
    provider_status        TEXT,

    input_truncated        INTEGER NOT NULL DEFAULT 0,
    output_truncated       INTEGER NOT NULL DEFAULT 0,
    output_original_size   INTEGER,

    started_at             INTEGER,
    completed_at           INTEGER,
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL,

    source                 TEXT    NOT NULL,
    source_message_id      INTEGER,
    migration_confidence   TEXT,

    CHECK (provider IN ('CLAUDE', 'CODEX', 'CURSOR', 'UNKNOWN')),
    CHECK (invocation_kind IN ('TOOL_USE', 'COMMAND_EXECUTION', 'SKILL')),
    CHECK (
        (invocation_kind = 'TOOL_USE' AND tool_name IS NOT NULL AND skill_name IS NULL)
        OR (invocation_kind = 'COMMAND_EXECUTION' AND tool_name IS NULL AND skill_name IS NULL)
        OR (invocation_kind = 'SKILL' AND tool_name = 'Skill')
    ),
    CHECK (trigger_source IN ('AGENT', 'USER_SLASH')),
    CHECK (status IN (
        'STARTED', 'SUCCEEDED', 'FAILED',
        'INCOMPLETE', 'UNKNOWN'
    )),
    CHECK (source IN ('LIVE', 'HISTORY_MIGRATION')),
    CHECK (migration_confidence IS NULL OR
           migration_confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    CHECK (is_error IN (0, 1)),
    CHECK (input_truncated IN (0, 1)),
    CHECK (output_truncated IN (0, 1))
);
```

### 5.2 字段说明

| 字段 | 说明 |
|---|---|
| `session_id` | 所属会话，所有记录必须具备 |
| `run_id` | 新 ChatRun 可关联；旧数据允许为空 |
| `assistant_message_id` | 最终承载该调用的 assistant 消息 |
| `provider` | CLI provider/agent 类型 |
| `provider_call_id` | Claude `tool_use.id` 或 Codex `item.id` |
| `invocation_index` | 同一消息/run 内的调用顺序 |
| `invocation_kind` | Claude 风格工具调用、Codex 命令执行或 Skill：`TOOL_USE` / `COMMAND_EXECUTION` / `SKILL` |
| `tool_name` | provider 明确提供的真实工具名；Claude 可为 `Read`、`Bash`、`Skill`，Codex `command_execution` 必须为空 |
| `skill_name` | Skill 调用时保存具体名称 |
| `trigger_source` | agent 自主调用或用户 slash 指定 |
| `input_json` | 完整重建并规范化后的输入 JSON |
| `output_text` | 工具执行结果，按限长策略保存 |
| `status` | 统一生命周期状态 |
| `provider_item_type` | Codex 原始 item 类型等 provider 语义 |
| `provider_status` | Codex 等 provider 原始状态 |
| `source` | 实时双写或历史迁移 |
| `source_message_id` | 历史迁移来源消息，也是幂等键的一部分 |
| `migration_confidence` | 仅历史推断需要，明确迁移可靠度 |

### 5.3 唯一约束

`provider_call_id` 不能全局唯一。历史数据库中工具 ID 会跨消息、跨 turn 重复，Codex 的 `item_1` 尤其常见。

```sql
CREATE UNIQUE INDEX uk_chat_tool_invocation_provider_call
ON chat_tool_invocation(
    session_id,
    source_message_id,
    provider_call_id
)
WHERE provider_call_id IS NOT NULL AND provider_call_id <> '';
```

用户 slash Skill 没有 provider call ID：

```sql
CREATE UNIQUE INDEX uk_chat_tool_invocation_message_index
ON chat_tool_invocation(
    session_id,
    source_message_id,
    invocation_index,
    trigger_source
);
```

新数据运行期内存聚合键使用：

```text
(runId, providerCallId)
```

### 5.4 查询索引

```sql
CREATE INDEX idx_chat_tool_invocation_session
ON chat_tool_invocation(session_id, created_at);

CREATE INDEX idx_chat_tool_invocation_run
ON chat_tool_invocation(run_id, invocation_index);

CREATE INDEX idx_chat_tool_invocation_message
ON chat_tool_invocation(assistant_message_id);

CREATE INDEX idx_chat_tool_invocation_tool
ON chat_tool_invocation(tool_name, created_at);

CREATE INDEX idx_chat_tool_invocation_skill
ON chat_tool_invocation(skill_name, created_at)
WHERE skill_name IS NOT NULL;

CREATE INDEX idx_chat_tool_invocation_status
ON chat_tool_invocation(status, updated_at);
```

### 5.5 示例

Claude Read：

```text
provider             = CLAUDE
provider_call_id     = toolu_01...
invocation_kind      = TOOL_USE
tool_name            = Read
skill_name           = null
trigger_source       = AGENT
input_json           = {"file_path":"/..."}
status               = SUCCEEDED
```

Claude Skill：

```text
provider             = CLAUDE
provider_call_id     = toolu_02...
invocation_kind      = SKILL
tool_name            = Skill
skill_name           = trace-issue
trigger_source       = AGENT
input_json           = {"skill":"trace-issue","args":"..."}
```

Codex 命令执行：

```text
provider             = CODEX
provider_call_id     = item_1
invocation_kind      = COMMAND_EXECUTION
tool_name            = null
provider_item_type   = command_execution
input_json           = {"command":"bash -lc '...'"}
exit_code            = 0
provider_status      = completed
status               = SUCCEEDED
```

`bash` 只出现在命令输入中，不作为工具名。

用户 `/trace-issue 123`：

```text
provider             = CLAUDE 或 CODEX
provider_call_id     = null
invocation_kind      = SKILL
tool_name            = Skill
skill_name           = trace-issue
trigger_source       = USER_SLASH
input_json           = {"arguments":"123"}
status               = SUCCEEDED
```

## 6. 领域模型与代码分层

建议目录：

```text
domain/chatrun/
  ToolInvocation
  ToolInvocationKind
  ToolInvocationStatus
  ToolInvocationRepository

app/chatrun/
  ChatToolInvocationTracker
  ToolInvocationEvent
  HistoricalToolInvocationMigrator

infra/cli/
  ClaudeToolInvocationEventExtractor
  CodexToolInvocationEventExtractor

infra/chatrun/
  SqliteToolInvocationRepository
  SqliteToolInvocationMigrationStateRepository
```

职责边界：

- Domain：调用状态、不变量、统一枚举；
- App：事件聚合、run 生命周期联动、消息 ID 回填；
- Infra CLI：解析 Claude/Codex 原始协议；
- Infra SQLite：UPSERT、分页迁移、checkpoint；
- Interface：后续查询接口，不参与协议解析。

Claude JSON 路径、Codex item 类型、Jackson 解析和 SQL 不应进入领域对象。

## 7. 统一事件模型

建议增加方言级端口，或在现有 `CliDialect` 增加能力：

```java
List<ToolInvocationEvent> extractToolInvocationEvents(
        String rawChunk,
        List<String> normalizedChunks);
```

统一事件：

```java
sealed interface ToolInvocationEvent
        permits ToolInvocationStarted,
                ToolInvocationInputDelta,
                ToolInvocationCompleted {
}
```

```java
final class ToolInvocationStarted {
    String providerCallId;
    ToolInvocationKind invocationKind;
    String toolName; // 仅 TOOL_USE / SKILL 使用，COMMAND_EXECUTION 为空
    String providerItemType;
    String initialInputJson;
}
```

```java
final class ToolInvocationInputDelta {
    String providerCallId;
    String partialJson;
}
```

```java
final class ToolInvocationCompleted {
    String providerCallId;
    String outputText;
    boolean error;
    Integer exitCode;
    String providerStatus;
}
```

接口同时接收原始事件和归一化事件：

- Claude 路径可解析直通的原始事件；
- Codex 路径必须优先解析原始 item，以保留 `exit_code` 等信息；
- 归一化事件可作为兼容 fallback；
- 未识别的 Codex item 类型应产生指标或诊断日志，不能永久静默丢弃。

## 8. 新数据运行期采集

### 8.1 Claude extractor

| Claude 事件 | 统一事件 |
|---|---|
| `content_block_start.tool_use` | `ToolInvocationStarted` |
| `content_block_delta.input_json_delta` | `ToolInvocationInputDelta` |
| `user.message.content[].tool_result` | `ToolInvocationCompleted` |

注意事项：

1. 参数 delta 按事件顺序拼接；
2. 只在完整输入形成后解析 JSON；
3. 最终 JSON 不完整时保留可用原始片段并标记 `INCOMPLETE`；
4. `tool_result.content` 同时支持字符串和结构化数组；
5. `tool_name=Skill` 时，从完整输入的 `skill` 字段提取 `skill_name`；
6. 不能从工具输出猜测 Skill 名称。

### 8.2 Codex extractor

| Codex 事件 | 统一事件 |
|---|---|
| `item.started + command_execution` | `ToolInvocationStarted(kind=COMMAND_EXECUTION, toolName=null)` |
| `item.completed + command_execution` | `ToolInvocationCompleted` |
| `status=failed` 或非零 `exit_code` | `FAILED` |

保留字段：

- `item.id`；
- `item.type`；
- `command`；
- `aggregated_output`；
- `exit_code`；
- `status`。

Codex `command_execution` 的分类规则：

1. `invocation_kind` 固定为 `COMMAND_EXECUTION`；
2. `tool_name` 固定为空；
3. `provider_item_type` 保存 `command_execution`；
4. `input_json.command` 原样保存命令文本；
5. 不解析命令首词来生成工具名；
6. `bash xxx`、`sh xxx`、`powershell.exe xxx` 都只是命令内容；
7. 当前 normalizer 生成的 `shell` 只继续服务现有前端，不能写入新表的 `tool_name`。

当前只明确支持 `command_execution`，但 extractor 的分派结构应允许后续增加：

- `mcp_tool_call`；
- `web_search`；
- `file_change`；
- 其他 Codex 新增工具 item。

`reasoning` 和 `agent_message` 不属于工具调用。

### 8.3 ChatToolInvocationTracker

按 `(runId, providerCallId)` 维护进行中的调用：

1. 收到 Started：插入 `STARTED`；
2. 收到 InputDelta：内存拼接参数；
3. 收到 Completed：flush 参数并更新结果和终态；
4. run 结束仍未完成：更新为 `INCOMPLETE`；
5. 重复事件：通过 UPSERT 幂等处理；
6. assistant 消息创建后，批量回填 `assistant_message_id`。

事务策略：

- Started、Completed 各使用短事务；
- input delta 先在内存聚合，不逐片段写数据库；
- run 终态统一 flush；
- assistant 消息落库后批量绑定；
- 工具持久化不依赖浏览器 SSE 是否在线。

## 9. Skill 处理设计

### 9.1 Agent 主动 Skill

识别规则：

```text
tool_name == "Skill"
```

参数完整拼接后：

```text
skill_name = input.skill
```

如果输入缺少 `skill`：

```text
invocation_kind = SKILL
skill_name      = null
```

调用本身仍可按结果确定成功或失败，但应记录缺少 Skill 名称的诊断指标。

### 9.2 用户 Slash Skill

当前 `SlashCommandExpander.expandIfCommand()` 只返回字符串，展开后会丢失命令身份。建议改为：

```java
final class SlashExpansionResult {
    String expandedPrompt;
    boolean matched;
    boolean skill;
    String commandName;
    String arguments;
}
```

`ChatRunPromptBuilder` 进一步返回：

```java
final class PreparedChatRunPrompt {
    String prompt;
    Optional<ExplicitSkillInvocation> explicitSkillInvocation;
}
```

`ChatRunExecutor` 在调用 CLI 前记录 `USER_SLASH` Skill。

用户 slash Skill 没有独立 tool result，状态与整个 run 联动：

- 成功展开并进入 run：`STARTED`；
- run 成功：`SUCCEEDED`；
- run 失败：`FAILED`；
- run 取消或中断：`INCOMPLETE`。

### 9.3 重复语义

如果用户输入 `/trace-issue`，展开后 Claude 又显式调用 `Skill(trace-issue)`，保留两条记录：

1. `USER_SLASH`：用户明确要求应用 Skill；
2. `AGENT`：agent 在运行期调用 Skill 工具。

二者语义不同，不能去重。

## 10. 历史迁移评估

对恢复后的当前数据库做 provider 分组只读验证，完整结果见 `docs/chat-tool-invocation-history-validation.md`：

| 项目 | Claude | Codex |
|---|---:|---:|
| 可识别调用开始 | 14,787 | 17,970 |
| 参数完整解析成功 | 14,773 | 17,970 |
| 参数 JSON 不完整 | 6 | 0 |
| 空参数 | 8 | 0 |
| 同消息精确关联结果 | 14,756 | 17,959 |
| 有开始但无结果 | 31 | 11 |
| 无本轮开始的结果 | 7,275 | 0 |
| Skill 调用 | 1,114 | 0 |
| 可恢复 Skill 名称 | 1,113 | 0 |

Codex 的 17,970 条历史调用全部可恢复 `command`，迁移后均应为 `COMMAND_EXECUTION` 且 `tool_name=null`。Claude 的 7,275 个无本轮开始结果，经样本验证主要是 resume 过程中回放的历史 `tool_result`，不能据此创建新调用。

历史数据时间范围：

```text
2026-03-31 ～ 2026-07-22
```

会话 Agent 分布：

- Claude：495；
- Codex：331；
- Cursor：11。

当前恢复库的 `chat_run` 为 0 条，因此历史工具调用无法恢复真实 `run_id`，只能关联：

```text
session_id + assistant_message_id
```

### 10.1 Claude 历史结果回放

Claude 历史中有 7,275 个 `tool_result` 在同一 assistant 消息内没有对应的调用开始。样本验证表明，这些数据主要是 Claude resume 输出回放的历史工具结果。例如某条消息只有 7 个本轮 `tool_use`，却包含 173 个 `tool_result`，其中 166 个属于历史回放。

历史迁移必须以调用开始事件为建行依据：

1. 同消息内按 `provider_call_id` 精确关联结果；
2. 跨消息关联仅限同 session、ID 精确相同、且唯一未完成的既有调用；
3. 没有调用开始的 `tool_result` 默认视为 resume 历史回放，忽略并计入 `replayed_result_count`；
4. 不为孤立结果创建 `tool_name=unknown` 的伪调用；
5. 禁止按“最近一次工具调用”做位置关联，因为并行调用会串联；
6. 有开始但最终无结果的 Claude/Codex 调用分别标记 `INCOMPLETE`。

### 10.2 Codex 历史字段限制

历史 `chat_message.content` 保存的是 Codex 归一化事件，而不是原始 `item.completed`。因此历史迁移可恢复：

- command；
- output；
- `is_error`；
- 基于当前 normalizer 确定映射得到的 `provider_item_type=command_execution`。

历史迁移不能可靠恢复：

- 原始 `exit_code`；
- 原始 provider status。

这两个字段必须保持为空，不能根据 `is_error` 猜测。实时新数据仍直接解析 Codex 原始 item，完整保存这些字段。

### 10.3 历史 Skill 名称

Agent 主动 Skill 可从完整参数的 `skill` 字段恢复。按 Claude provider 精确验证，当前可恢复 1,113/1,114 条。

用户 slash Skill 可以从 user 消息首行提取候选，但历史上存在：

1. Skill 文件已删除或重命名；
2. 同名内容当时可能是 command，现在是 Skill，或相反；
3. 当前文件系统不能证明历史时点的类型。

迁移规则：

- 有明确历史证据：`HIGH`；
- 当前 scanner 能确认、但缺少历史快照：`MEDIUM`；
- 仅根据命名或文本猜测：不写主表，输出迁移诊断；
- 若要求完整迁移历史 slash Skill，需要提供对应工作区 `.claude/skills`、`.codex/skills` 的历史快照，或按消息时间从 Git 恢复。

## 11. 历史迁移实现

### 11.1 迁移状态表

```sql
CREATE TABLE IF NOT EXISTS chat_tool_invocation_migration_state (
    migration_name       TEXT PRIMARY KEY,
    last_message_id      INTEGER NOT NULL,
    scanned_messages     INTEGER NOT NULL,
    inserted_invocations INTEGER NOT NULL,
    parse_failures       INTEGER NOT NULL,
    unmatched_results    INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL
);
```

### 11.2 迁移流程

```text
1. 对当前数据库执行在线备份
2. 执行 integrity_check
3. 创建主表、索引和 checkpoint 表
4. dry-run 分页扫描并输出统计
5. 按 chat_message.id 分页读取 assistant 消息
6. 解析归一化 NDJSON
7. 聚合调用开始、参数 delta 和结果
8. 在 session 范围内处理跨消息精确 ID 关联
9. 批量 UPSERT 工具调用
10. 迁移有可靠证据的用户 slash Skill
11. 每批更新 checkpoint 并提交
12. 输出迁移报告和异常样本
13. 执行数量、Skill、状态和孤立结果校验
```

建议每批读取 200～500 条 `chat_message`，禁止一次性加载整个数据库。

### 11.3 执行形式

不要在每次应用启动时自动扫描约 1.1 GB 的数据库。建议实现显式一次性运维命令：

```bash
java -jar app/agent-web.jar \
  --app.migration.tool-invocations.enabled=true \
  --spring.main.web-application-type=none
```

迁移器必须满足：

- 可中断恢复；
- 重跑幂等；
- 每批短事务；
- 失败保留 checkpoint；
- 支持 dry-run；
- 正式迁移前后输出统计差异。

### 11.4 迁移前备份

数据库处于 WAL 模式，使用 SQLite 在线备份，不直接复制正在使用的主文件：

```bash
sqlite3 data/agent-web.db \
  ".backup 'data/agent-web-before-tool-migration-时间戳.db'"
```

然后压缩备份，并执行：

```sql
PRAGMA integrity_check;
```

## 12. 数据容量与敏感信息

工具参数和输出可能包含：

- API Key；
- Cookie；
- Authorization header；
- Git 凭据；
- 密码和 token；
- 私钥；
- 文件内容；
- 用户隐私数据；
- 大型命令输出。

### 12.1 限长

建议默认：

```text
input_json  最大 64 KiB
output_text 最大 64 KiB 或 128 KiB
```

同时保存：

- `input_truncated`；
- `output_truncated`；
- `output_original_size`。

当前 `StreamChunkHandler` 已将单个工具结果截断到 12,000 字符，因此历史迁移只能恢复现存版本，无法恢复此前被截断的内容。

### 12.2 脱敏

对结构化输入递归处理至少以下键：

```text
password
token
secret
api_key
apiKey
authorization
cookie
private_key
credential
```

自由文本命令和输出无法可靠完全脱敏，因此还需：

1. 新查询遵循现有会话用户隔离；
2. 普通用户不能跨会话查询；
3. 管理接口默认截断输出；
4. 导出能力单独授权；
5. 应用日志只记录 ID、工具名、状态、长度，不记录完整输入输出。

### 12.3 原始事件

第一版不新增 raw event 表：

- `chat_message.content` 已保存归一化流；
- 新 ChatRun 的 `chat_run_event` 也保存 chunk；
- 重复保存会显著增加数据库体积；
- 主表只保存结构化查询需要的字段。

若未来需要法证级审计，应单独设计可配置保留期的 raw event 表。

## 13. 查询接口建议

第一阶段先完成持久化和迁移，查询接口可在后续阶段增加：

```http
GET /api/chat/sessions/{sessionId}/tool-invocations
GET /api/chat/runs/{runId}/tool-invocations
GET /api/admin/tool-invocations
GET /api/admin/tool-invocations/stats
```

支持筛选：

- provider；
- toolName；
- skillName；
- status；
- triggerSource；
- 时间范围；
- sessionId；
- runId。

如果聊天历史接口按 `assistant_message_id` 返回新表内容，需要避免同时返回旧 NDJSON 内完整工具输出和新表完整输出，防止响应体重复膨胀。

## 14. 测试方案

### 14.1 Domain 单测

验证：

- 状态迁移；
- Skill 名称约束；
- Started → Completed；
- 失败和中断；
- 截断元数据；
- 非 Skill 不允许错误填充 `skill_name`。

### 14.2 Application 单测

使用真实领域对象，Mock Repository：

- Started 插入；
- 参数 delta 聚合；
- Completed 更新；
- run 结束标记 `INCOMPLETE`；
- assistant message ID 回填；
- USER_SLASH Skill 记录；
- 重复事件幂等；
- 工具持久化失败时的 run 降级策略。

### 14.3 Infra 轻量集成

真实 SQLite，不启动 Spring：

- UPSERT 幂等；
- 唯一索引；
- 分页迁移；
- checkpoint 恢复；
- 大字段截断；
- 同一 provider ID 跨消息不冲突；
- 跨消息精确 ID 关联；
- 迁移结果计数。

### 14.4 方言 Fixture 测试

Claude 新增脱敏 fixture：

- 单工具；
- 多工具；
- 并行工具；
- 参数多 delta；
- 数组型 tool result；
- Skill；
- 失败；
- 中断；
- 不完整 JSON。

Codex复用并扩充：

- `src/test/resources/codex-fixtures/tool-call.jsonl`；
- `src/test/resources/codex-fixtures/tool-call-fail.jsonl`；
- 新增未知 item 类型兼容 fixture。

### 14.5 全栈测试

最多在现有 chat 流全栈测试中增加一个跨切关注点：

```text
发送消息
→ CLI stub 输出工具调用
→ assistant 消息落库
→ tool invocation 关联 assistant_message_id
```

其余行为应由 Domain、Application 和 Infra 轻量集成测试覆盖，不新增大量 `@SpringBootTest`。

## 15. 发布与回滚策略

### 阶段 1：数据模型与实时双写

1. 新增表、索引和 Repository；
2. 新增统一事件模型；
3. 实现 Claude/Codex extractor；
4. 接入 `ChatRunExecutor`；
5. 支持两类 Skill 调用；
6. 保持 `chat_message.content` 不变；
7. 增加写入成功率、未知 provider item、缺失 Skill 名称指标。

建议增加紧急开关：

```text
agent.chat.tool-invocation.enabled=true
```

关闭时仅停用新表双写，不影响原有聊天流程。

### 阶段 2：历史迁移

1. 在线备份；
2. dry-run；
3. 审核统计与异常样本；
4. 正式分页迁移；
5. 校验总量、Skill 名称、失败和孤立结果；
6. 保留迁移 checkpoint 和报告。

### 阶段 3：查询与统计

1. 新增查询 API；
2. 管理台增加工具、命令执行和 Skill 使用统计；
3. 新表只作为旁路查询、审计和统计数据源；
4. 不修改聊天前端的 NDJSON 解析和工具渲染链路。

### 回滚

- 关闭双写开关即可恢复原数据链路；
- 新表是旁路数据，不影响 `chat_message`；
- 回滚应用版本时可保留新表；
- 历史迁移不修改或删除原消息；
- 如需清除迁移结果，只删除 `source=HISTORY_MIGRATION` 的记录，并保留在线备份。

## 16. 关键决策

1. 一张主表表示一次调用，不为每个流事件建一行；
2. 保留 provider 原生语义：Claude `tool_use`、Codex `command_execution`、Skill 分别建模；
3. Codex `command_execution` 没有工具名，命令中的 `bash` 也不是工具名；现有 `shell` 仅是前端兼容展示名称；
4. Skill 是独立调用类别，并保存具体 `skill_name`；
5. 区分 `AGENT` 与 `USER_SLASH`；
6. 新数据从原始方言事件采集，历史数据从已保存的归一化 NDJSON 回填；
7. 历史记录允许 `run_id` 为空；
8. 迁移必须具有 checkpoint、幂等键和置信度；
9. 永久保留原 assistant NDJSON、SSE 契约和现有前端渲染，新表始终作为旁路双写；
10. 输入和输出限长、脱敏，查询服从用户隔离；
11. 先 dry-run，审核统计后再正式迁移；
12. 不对无法证明的历史 slash Skill 做强推断；
13. 工具调用持久化失败的默认策略应是记录告警并降级，不能阻断 agent run；如果未来用于强审计，再引入可配置 fail-closed 模式。

## 17. 已知限制

1. 当前历史库没有 `chat_run` 数据，无法补齐历史 `run_id`；
2. 7,275 个同消息内未匹配结果需要跨消息精确 ID 匹配或降级；
3. 历史用户 slash Skill 缺少当时的 Skill 定义快照，无法保证全量准确识别；
4. 已被 `StreamChunkHandler` 截断的历史工具输出无法恢复；
5. Codex 当前 fixture 主要覆盖 `command_execution`，未来新 item 类型需要按版本持续补充；
6. Cursor 历史数据量较少，若其事件协议不同，需要独立 fixture 验证后决定复用 Claude extractor 还是新增 extractor。
