输出 Preflight、Build Evidence、Deployment Record、Acceptance Result 与 Final Report Artifact。

只返回一个 JSON 对象，不要使用 Markdown 代码块或附加解释。根对象只能包含
`schemaVersion`、`stage`、`artifacts`；`schemaVersion` 必须为 `workbench-artifact-bundle@1`，
`stage` 必须为 `DEPLOYMENT`。`artifacts` 必须是 JSON 数组（`[...]`），恰好包含 `PREFLIGHT`、`BUILD_EVIDENCE`、
`DEPLOYMENT_RECORD`、`ACCEPTANCE_RESULT`、`FINAL_REPORT` 各一个元素；不得用对象按 `artifactType` 做 key。每个数组元素只能包含
`artifactId`、`artifactType`、`contentType`、`classification`、`content`；其中 `content` 是非空字符串，
`contentType` 只能是 `application/json`、`text/markdown` 或 `text/plain`。
