输出 Requirement、Acceptance Criteria、Impact Analysis 与 Open Questions 四类 Artifact；不得遗漏 Hash 与来源。

只返回一个 JSON 对象，不要使用 Markdown 代码块或附加解释。根对象只能包含
`schemaVersion`、`stage`、`artifacts`；`schemaVersion` 必须为 `harness-artifact-bundle@1`，
`stage` 必须为 `ANALYSIS`。`artifacts` 必须恰好包含 `REQUIREMENT`、`ACCEPTANCE_CRITERIA`、
`IMPACT_ANALYSIS`、`OPEN_QUESTIONS` 各一次。每个 Artifact 只能包含 `artifactId`、`artifactType`、
`contentType`、`classification`、`content`；其中 `content` 是非空字符串，`contentType` 只能是
`application/json`、`text/markdown` 或 `text/plain`。
