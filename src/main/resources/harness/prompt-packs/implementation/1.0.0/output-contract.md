输出 Changed Files、Test Evidence、Implementation Summary 与 Traceability Artifact。

Test Evidence 必须是 JSON，`commands` 按实际执行顺序列出聚焦测试；每项至少包含
`command`、`phase`（`RED` / `GREEN` / `VERIFY`）和 `exitCode`。Harness 会把这些声明
与 Codex `command_execution` 完成事件中的真实命令和退出码逐项对账，不匹配即失败。

只返回一个 JSON 对象，不要使用 Markdown 代码块或附加解释。根对象只能包含
`schemaVersion`、`stage`、`artifacts`；`schemaVersion` 必须为 `harness-artifact-bundle@1`，
`stage` 必须为 `IMPLEMENTATION`。`artifacts` 必须恰好包含 `CHANGED_FILES`、`TEST_EVIDENCE`、
`IMPLEMENTATION_SUMMARY`、`TRACEABILITY` 各一次。每个 Artifact 只能包含 `artifactId`、
`artifactType`、`contentType`、`classification`、`content`；其中 `content` 是非空字符串，
`contentType` 只能是 `application/json`、`text/markdown` 或 `text/plain`。
