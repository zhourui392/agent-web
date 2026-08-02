输出 Requirement、Acceptance Criteria、Impact Analysis 与 Open Questions 四类 Artifact；不得遗漏 Hash 与来源。

只返回一个 JSON 对象，不要使用 Markdown 代码块或附加解释。根对象只能包含
`schemaVersion`、`stage`、`artifacts`；`schemaVersion` 必须为 `workbench-artifact-bundle@1`，
`stage` 必须为 `ANALYSIS`。`artifacts` 必须是 JSON 数组（`[...]`），恰好包含 `REQUIREMENT`、`ACCEPTANCE_CRITERIA`、
`IMPACT_ANALYSIS`、`OPEN_QUESTIONS` 各一个元素；不得用对象按 `artifactType` 做 key。每个数组元素只能包含 `artifactId`、`artifactType`、
`contentType`、`classification`、`content`；其中 `content` 是非空字符串，`contentType` 只能是
`application/json`、`text/markdown` 或 `text/plain`。
ACCEPTANCE_CRITERIA 的 content 必须是 JSON 对象，含 `acceptanceCriteria` 数组；每条含 `acceptanceCriteriaId`（唯一）、`relatedRequirementIds`（非空数组）、`expectedObservableResult`（可观察的预期结果）、`observability`（验证方式），可附 `precondition`/`action`/`status`。不得用领域建模审计报告格式。
