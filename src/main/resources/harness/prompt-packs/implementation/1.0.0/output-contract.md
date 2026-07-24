输出 Changed Files、Test Evidence、Implementation Summary 与 Traceability Artifact。

Test Evidence 必须是 JSON，`commands` 按实际执行顺序列出聚焦测试；每项至少包含
`command`、`phase`（`RED` / `GREEN` / `VERIFY`）和 `exitCode`。Harness 会把这些声明
与 Codex `command_execution` 完成事件中的真实命令和退出码逐项对账，不匹配即失败。
