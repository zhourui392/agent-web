# ADR-001：MVP 使用 Codex CLI Runtime

> 状态：Accepted
> 日期：2026-07-22

## 背景

Harness MVP 只打通一个 Runtime。当前项目已经支持 `codex exec --json`，但 Harness 还需要按 Stage 隔离 Prompt、Skill、MCP、Sandbox 和 Approval，并对超时、取消和事件建立稳定合同。

M0 在本机 `codex-cli 0.145.0` 上完成黑盒验证。官方手册说明 `codex exec` 支持非交互执行、`--ephemeral`、JSONL、输出 Schema、配置覆盖和自定义 Model Provider；本地测试进一步确认了实际参数位置和取消语义。

## 决策

1. MVP 的首发 Runtime 固定为 Codex CLI；Claude Runtime 延后到 M6。
2. 以 `codex-cli 0.145.0` 作为首个已验证兼容基线，不宣称未经契约测试的版本自动兼容。
3. 新建 Harness Runtime 出站端口；Domain/Application 不感知 Codex 参数和 JSONL 私有字段。
4. Harness 使用独立进程组启动 Codex，并管理整个进程树。
5. 每次 Stage Attempt 使用：

```text
codex --ask-for-approval never exec
  --ignore-user-config
  --ignore-rules
  --ephemeral
  --json
  --sandbox <stage-policy>
  -C <approved-working-dir>
  <capability-overrides>
  -
```

6. `--ask-for-approval` 放在 `exec` 子命令之前。虽然 `codex exec --help` 的当前输出把该选项列在 exec Options 中，但本机 0.145.0 放在子命令后会报 `unexpected argument`。
7. MVP 使用受管的临时 `HOME` 和 `CODEX_HOME`，不加载真实用户配置；真实模型鉴权未来通过受控 Credential Reference 注入单次运行。
8. `--ignore-user-config` 只承诺不加载 `$CODEX_HOME/config.toml`，不等同于忽略可信项目的 `.codex/config.toml`。MVP Preflight 遇到工作目录祖先链上的 `.codex/config.toml` 时默认阻断，直至实现显式解析和授权。
9. Stage Attempt 保存 Codex PID/进程组、JSONL Runtime Execution ID、Prompt Hash 和 Capability Snapshot Hash。
10. 取消时先持久化取消意图，再终止进程组。取消后的整体状态由取消意图决定，不依据进程退出码。

## 已验证行为

- Repo Skill 元数据进入模型输入；显式 `$skill` 调用会把完整 `SKILL.md` 注入模型输入。
- `[[skills.config]]` 的等价 CLI 覆盖可以禁用未选择 Skill。
- `enabled_tools`/`disabled_tools` 会把未授权 MCP Tool 从模型可见工具中移除。
- `required=true` 的 MCP 初始化失败会使会话 fail-closed。
- `tool_timeout_sec` 超时产生失败的 `mcp_tool_call` 终态事件，Turn 可以继续完成。
- 进程组 `SIGTERM` 会停止 Codex 和 MCP 子进程，但 Codex 可能返回退出码 0，且 JSONL 没有 `turn.completed/turn.failed`。
- `--output-schema` 与 `--output-last-message` 可以产生可验证的结构化阶段输出。

## 后果

正面：

- 复用现有 Codex CLI 能力，不在 Java 内重造 Agent/MCP Runtime。
- Runtime 适配器可用 Stub 和本地 Responses Fixture 确定性测试。
- Capability Snapshot 可以映射到明确的 CLI 覆盖。

代价与限制：

- 需要维护 Codex 版本兼容矩阵和参数契约测试。
- 必须管理进程组，不能只调用 `Process.destroy()` 后看退出码。
- MVP 遇到项目 `.codex/config.toml` 默认阻断，限制部分工作区。
- 当前本机保存的在线 API Key 被服务端返回 401；这不阻塞 M1—M3，但在真实需求验收前必须修复。

## 被否决方案

- **直接复用现有 WorkflowRunner**：缺少 Stage、Gate、Approval、Capability Snapshot 和取消语义。
- **Java 进程内重写 Codex/MCP Runtime**：MVP 成本与协议风险过高。
- **复用真实用户 CODEX_HOME**：用户 Skill/MCP/Hook 可能越过 Stage Snapshot，无法证明最小权限。
- **以退出码 0 判定成功**：取消实验已经证明该规则错误。
