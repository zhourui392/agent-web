# Claude Code 与 Codex 历史工具调用验证报告

> 状态：历史验证报告（2026-08-06 归档）。数据量和本地数据库快照只代表报告生成时的基线，不构成当前运行时设计或发布门禁。

## 1. 验证范围

本次对当前 `data/agent-web.db` 做只读验证，不创建新表、不修改消息、不执行迁移。

“两个历史数据”按以下两类验证：

1. `chat_session.agent_type = CLAUDE` 的 Claude Code 历史消息；
2. `chat_session.agent_type = CODEX` 的 Codex 历史消息。

验证目标：

- 工具调用开始事件是否可识别；
- 参数增量是否能重建为完整 JSON；
- 调用结果能否按 provider call ID 关联；
- Claude Skill 名称是否可恢复；
- Codex `shell` 兼容事件是否能还原为 `COMMAND_EXECUTION`；
- 历史数据中是否存在回放、截断、不完整或无法关联的数据。

## 2. 总体结论

两类历史数据都可以迁移，但迁移规则必须按 provider 分开：

- Claude Code：以 `content_block_start.tool_use` 为调用事实，只关联同消息或可被精确 ID 证明属于该调用的 `tool_result`。不能把所有 `tool_result` 迁移成调用，因为 Claude resume 输出包含大量历史结果回放。
- Codex：当前历史中所有调用开始均表现为归一化展示名 `shell`，参数均可恢复 `command`。迁移时应写成 `COMMAND_EXECUTION`，且 `tool_name=null`；不能写成 `Bash` 或 `shell`。
- 历史 `chat_message.content` 只有 Codex 归一化事件，没有原始 `item.completed`，因此可恢复命令、输出和 `is_error`，但不能可靠恢复原始 `exit_code` 与 provider status。
- 原有 `chat_message.content`、SSE 和前端渲染逻辑不需要也不应该改变。

## 3. Claude Code 验证结果

### 3.1 调用统计

| 项目 | 数量 |
|---|---:|
| 工具调用开始 | 14,787 |
| 同消息精确 ID 匹配结果的调用 | 14,756 |
| 没有匹配结果的调用 | 31 |
| 参数 delta 成功解析 | 14,773 |
| 空参数 | 8 |
| 不完整参数 JSON | 6 |
| 同一调用匹配多个结果 | 0 |
| 结果标记 `is_error=true` | 518 |
| 已关联结果文本规模 | 约 39.4 MiB |

调用结果精确关联率：

```text
14,756 / 14,787 = 99.79%
```

参数完整解析率：

```text
14,773 / 14,787 = 99.91%
```

### 3.2 主要工具名称

| 工具 | 调用数 |
|---|---:|
| Bash | 7,864 |
| Read | 2,815 |
| Grep | 1,830 |
| Skill | 1,114 |
| Glob | 290 |
| Agent | 271 |
| TodoWrite | 185 |
| Edit | 138 |
| Write | 96 |
| mcp__llm-wiki__query_graph | 37 |
| TaskOutput | 34 |
| TaskUpdate | 31 |
| TaskCreate | 21 |

这些是 Claude Code 事件明确提供的真实 `tool_use.name`，可以写入新表的 `tool_name`。

### 3.3 Skill 名称

| 项目 | 数量 |
|---|---:|
| Claude `Skill` 调用 | 1,114 |
| 可恢复 Skill 名称 | 1,113 |
| 名称无法完整恢复 | 1 |

Skill 名称恢复率：

```text
1,113 / 1,114 = 99.91%
```

唯一不完整样本：

```text
message_id = 2101
provider_call_id = toolu_01DQ4MvXA4KvKCGwFwFaY3LZ
partial_json = {"skill": "trace-issue"
```

该记录能人工看出候选名称为 `trace-issue`，但 JSON 缺少闭合符号。迁移器不应静默修复，建议：

```text
invocation_kind      = SKILL
skill_name           = null
status               = INCOMPLETE
migration_confidence = LOW
```

同时在迁移报告中记录原始片段。若业务允许确定性容错，可单独实现“仅缺尾部 `}`”的受控修复，并标记 `MEDIUM`，但不建议作为默认规则。

### 3.4 Claude 历史结果回放

Claude 历史中共扫描到 22,031 个 `tool_result`，其中 14,756 个能与同一 assistant 消息中的调用开始精确匹配，另有 7,275 个没有对应的本轮调用开始。

进一步验证表明，这些额外结果主要不是孤立的新调用，而是 Claude resume 输出中回放的历史工具结果。例如某些单条 assistant 消息中：

```text
本轮 tool_use 开始：7
消息内 tool_result：173
其中本轮精确匹配：7
额外历史回放结果：166
```

相关事件仍携带原历史 `tool_use_id`，但当前数据库中不一定保存其最初调用开始，或者最初调用属于 CLI resume 历史而非当前 agent-web turn。

因此，历史迁移规则必须修订为：

1. `content_block_start.tool_use` 是创建调用记录的必要事实；
2. 同消息内相同 `tool_use_id` 的 `tool_result` 可关联；
3. 跨消息关联只有在同 session、ID 精确相等且唯一未完成时才允许；
4. 没有调用开始的回放 `tool_result` 默认忽略并计入 `replayed_result_count`；
5. 不再为这些结果创建 `tool_name=unknown` 的伪调用；
6. 不按“最近工具调用”做位置关联。

这是本次验证得到的关键修订。原方案中“无法匹配结果时建立 unknown 调用”的策略会把 Claude 历史回放误计为本轮调用，必须删除。

### 3.5 不完整调用

31 个 Claude 调用有开始事件但未找到结果。这些记录应保留：

```text
status = INCOMPLETE
```

可能原因包括：

- run 中断；
- 消息保存时结果尚未返回；
- 历史内容截断；
- CLI 流提前退出。

不能仅因为缺少结果将其判为失败。

## 4. Codex 验证结果

### 4.1 调用统计

| 项目 | 数量 |
|---|---:|
| 归一化 `shell` 调用开始 | 17,970 |
| 成功恢复 `command` | 17,970 |
| 缺失命令 | 0 |
| 同消息精确 ID 匹配结果 | 17,959 |
| 没有匹配结果的调用 | 11 |
| 参数 JSON 解析失败 | 0 |
| 无调用开始的孤立结果 | 0 |
| 同一调用匹配多个结果 | 0 |
| 结果标记 `is_error=true` | 878 |
| 已关联结果文本规模 | 约 259.7 MiB |

命令恢复率：

```text
17,970 / 17,970 = 100%
```

结果精确关联率：

```text
17,959 / 17,970 = 99.94%
```

### 4.2 `shell` 不是工具名

17,970 个 Codex 调用在历史 `chat_message.content` 中均显示为：

```text
tool_name = shell
input.command = /bin/bash ...
```

所有命令的首 token 均为：

```text
/bin/bash
```

这验证了：

- `shell` 是 `CodexEventNormalizer` 生成的前端兼容展示名；
- `/bin/bash` 是命令执行程序；
- 二者都不是 Codex 提供的真实工具名；
- Codex 原生调用事实是 `command_execution`。

历史迁移必须写成：

```text
provider             = CODEX
invocation_kind      = COMMAND_EXECUTION
tool_name            = null
provider_item_type   = command_execution
input_json.command   = 原完整命令
```

禁止写成：

```text
tool_name = Bash
tool_name = shell
tool_name = /bin/bash
```

### 4.3 Codex 历史字段可恢复范围

从归一化历史内容可以恢复：

- `provider_call_id`：由兼容事件中的 `content_block.id` 恢复；
- `command`：由 `input_json_delta.partial_json` 恢复；
- `output_text`：由 `tool_result.content` 恢复；
- `is_error`：由 `tool_result.is_error` 恢复；
- 统一状态：有结果时可映射为 `SUCCEEDED` 或 `FAILED`；
- 无结果时映射为 `INCOMPLETE`。

不能可靠恢复：

- 原始 `item.status`；
- 原始 `exit_code`；
- 原始 `item.type` 的直接证据。

`provider_item_type=command_execution` 可基于当前 normalizer 的确定映射规则回填，标记：

```text
migration_confidence = HIGH
```

但历史 `exit_code` 必须为空，不能根据 `is_error` 猜测具体数值：

```text
exit_code = null
```

### 4.4 不完整 Codex 调用

11 个 Codex 调用有开始事件但无结果，分布在 8 条 assistant 消息中。这些调用应迁移为：

```text
invocation_kind = COMMAND_EXECUTION
status          = INCOMPLETE
```

不能假设它们失败，也不能伪造 `exit_code`。

## 5. 两类历史迁移规则

### 5.1 Claude Code

```text
content_block_start.tool_use
  → 创建 TOOL_USE 或 SKILL

input_json_delta
  → 按调用顺序拼接输入

同消息、相同 tool_use_id 的 tool_result
  → 更新结果和状态

唯一且可证明的跨消息相同 ID 未完成调用
  → 允许关联

没有调用开始的 tool_result
  → 视为 resume 历史回放，忽略并统计
```

### 5.2 Codex

历史归一化规则：

```text
tool_use.name = shell
且 input JSON 含 command
且所属 session.agent_type = CODEX
  → COMMAND_EXECUTION
```

迁移结果：

```text
invocation_kind    = COMMAND_EXECUTION
tool_name          = null
provider_item_type = command_execution
```

实时新数据不能依赖这条历史规则，应直接解析 Codex 原始 `item.started/item.completed`。

## 6. 迁移校验指标

正式 dry-run 和迁移报告至少输出：

```text
scanned_assistant_messages
recognized_call_starts
inserted_tool_uses
inserted_command_executions
inserted_skills
parsed_inputs
invalid_inputs
matched_results_same_message
matched_results_cross_message
replayed_results_ignored
incomplete_calls
failed_calls
resolved_skill_names
missing_skill_names
truncated_inputs
truncated_outputs
```

迁移验收基线：

### Claude

```text
recognized_call_starts          = 14,787
inserted total                  = 14,787
matched_results_same_message    = 14,756
incomplete_calls                = 31（跨消息精确关联前）
replayed_results_ignored        = 7,275（跨消息精确关联前）
Skill                           = 1,114
resolved_skill_names            = 1,113
```

### Codex

```text
recognized_call_starts          = 17,970
inserted command executions     = 17,970
command resolved                = 17,970
matched_results_same_message    = 17,959
incomplete_calls                = 11
orphan results                  = 0
tool_name non-null              = 0
```

跨消息精确 ID 关联可能降低 `INCOMPLETE` 和回放计数，但必须同时保持调用总数不变，且不能产生新调用。

## 7. 对设计方案的修订建议

1. 删除“孤立 tool_result 创建 unknown 调用”的规则；
2. 新增 `replayed_result_count` 迁移指标；
3. 历史迁移以调用开始事件为唯一建行依据；
4. Claude resume 回放结果默认忽略；
5. Codex 历史 `exit_code` 和 `provider_status` 允许为空；
6. Codex 历史分类依据是 agent type + normalizer 的确定映射，不是命令首词；
7. 迁移完成后必须验证 Codex `tool_name IS NOT NULL` 数量为 0；
8. Skill 名称不完整时保留调用，但不强行修复名称；
9. 31 个 Claude 和 11 个 Codex 无结果调用先标记 `INCOMPLETE`；
10. 永久保持 `chat_message.content`、SSE 与前端渲染链路不变。

## 8. 验证结论

历史数据质量足以支持独立工具调用表：

- Claude 调用开始可识别 14,787 次，结果精确关联率 99.79%；
- Claude Skill 名称恢复率 99.91%；
- Codex 命令调用可识别 17,970 次，命令恢复率 100%，结果精确关联率 99.94%；
- Codex 可以稳定分类为 `COMMAND_EXECUTION`，无需也不应伪造工具名；
- 最大迁移风险是 Claude resume 历史结果回放，必须采用“调用开始驱动”的迁移算法。

在按本报告修订迁移规则后，可以进入新表和迁移器的实现阶段。
