# ADR-003：MCP 与 Skill 采用预注册、动态选择和失败关闭

> 状态：Accepted
> 日期：2026-07-22

## 背景

Codex 能自动发现 Repo/User/Admin/System Skills，也能从 `config.toml` 加载多个 MCP Server。如果 Harness 直接复用用户环境，未选择 Skill、项目配置或强能力 MCP 可能在某个 Stage 中可见，破坏 Capability Snapshot 和最小权限。

## 决策

### Skill

1. Skill 必须先进入可信 Catalog，之后才能被 Stage 选择。
2. Snapshot 保存 Skill ID、版本、入口、包 Hash、来源和选择理由。
3. Stage 默认 Skill、用户显式 Skill和确定性标签匹配共同形成候选，再与 Stage Contract 求交。
4. Runtime 启动前扫描当前 Codex 可发现的 Repo Skill；未选择 Skill 通过 `skills.config` 显式禁用。
5. 未注册的工作区 Skill 默认阻断，而不是自动进入 Snapshot。
6. Skill 中的脚本请求独立命令权限；选择 Skill 不自动授予脚本执行权。
7. MVP 不自动安装、升级或从网络下载 Skill。

### MCP

1. MCP Server 只能来自管理员维护的 Registry；需求正文、Agent 输出和工作区配置不能提供启动命令。
2. MVP 仅启用只读 MCP；外部写 MCP 延后到独立 Approval 能力完成后。
3. 每次 Stage 只挂载已授权 Server，并同时设置 `enabled_tools` 与 `disabled_tools`。
4. 必需 Server 设置 `required=true`；初始化失败必须阻断 Stage。
5. 设置明确的 `startup_timeout_sec` 和 `tool_timeout_sec`。
6. MCP Tool 的 read-only Annotation 只作为附加信号，不能替代 Registry 风险分类。
7. Runtime 无法强制 Tool allowlist 时，整个 Server 不挂载。
8. Secret 只以环境变量名或 Credential Reference 出现在配置中；Snapshot、Prompt、日志和 API 不保存值。
9. 每个 Attempt 使用临时配置，成功、失败、超时和取消后清理。
10. 工作区存在 `.codex/config.toml` 时，MVP Preflight 默认阻断，防止额外 MCP/Hook/配置进入运行时。

## 已验证行为

- Codex 0.145.0 的 `codex mcp add/list/get/remove` 可以在隔离 `CODEX_HOME` 中工作。
- `enabled_tools=["read_fixture"]` 与 `disabled_tools=["write_fixture"]` 会使模型请求只包含 `read_fixture`。
- `required=true` 的无效 Server 使 `thread/start` 失败，不会静默继续。
- `tool_timeout_sec=0.5` 会把慢工具标记为 failed，并在 JSONL 中包含 timeout 原因。
- 未选择 Repo Skill 可以通过 `skills.config` 从模型可见输入中移除。

## 后果

正面：

- Capability Snapshot 与 Runtime 实际可见能力可以对账。
- Prompt Injection 无法仅靠文本添加 MCP 或脚本权限。
- MCP 初始化、超时和取消有确定性处理。

代价与限制：

- Runtime 启动前需要扫描 Repo Skill 和项目配置。
- Codex 版本变化可能改变 Skill/MCP 配置格式，必须运行兼容测试。
- MVP 对包含 `.codex/config.toml` 的工作区采取保守阻断；后续版本再实现受控合并。
