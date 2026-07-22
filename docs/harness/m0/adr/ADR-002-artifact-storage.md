# ADR-002：Artifact 采用 SQLite 元数据与受控文件存储

> 状态：Accepted
> 日期：2026-07-22

## 背景

Harness 需要保存需求、设计、测试和部署文档，也需要保存命令输出、JSONL、Diff 和日志。全部放 SQLite 会放大数据库和查询成本；全部放工作区文件会污染业务仓库、难以授权和清理；只保存聊天输出又无法稳定恢复与审计。

## 决策

1. SQLite 保存 Run、Stage、Attempt、Artifact Descriptor、Hash、依赖、Gate、Approval、Runtime Execution、Evidence 和事件。
2. Artifact 正文保存到配置项 `agent.harness.artifact-root` 指向的受控文件根，默认位于源码工作区之外。
3. Domain 只依赖 `ArtifactRepository` 和 `ArtifactDescriptor`，不感知 SQLite、绝对路径或文件 API。
4. Artifact 更新产生新版本，不原地覆盖；Approval 和下游引用绑定 `artifactId + version + sha256`。
5. 文件写入采用临时文件、同步完成、原子移动，再提交元数据；失败时不得留下可见的半成品版本。
6. Artifact 路径由系统生成，不接受用户提供的相对路径或文件名作为物理路径。
7. Artifact 落盘前执行大小限制和 Secret Redaction；原始外部输出若不能安全保存，只记录脱敏摘要和校验信息。
8. 将需求或设计发布到业务仓库是单独、显式、可审批的动作，不是 Artifact 保存的默认副作用。
9. M0 初始 Descriptor Schema 见 [`artifact.schema.json`](../contracts/artifact.schema.json)。

## 后果

正面：

- 状态查询保持轻量，大型证据不挤占 SQLite。
- Artifact 可以独立做保留、归档、导出和访问控制。
- 业务工作区保持干净，避免 Harness 运行记录混入待交付 Diff。

代价与限制：

- SQLite 与文件系统之间需要补偿和孤儿清理任务。
- 备份与恢复必须同时覆盖数据库和 Artifact Root。
- M1 需要真实 SQLite 与 `@TempDir` 测试原子写、Hash 和路径逃逸。

## 被否决方案

- **全部存 SQLite TEXT/BLOB**：对大日志和长期证据不友好。
- **全部存工作区 `.harness/`**：污染仓库并扩大 Agent 写权限。
- **只保存文件路径**：不能保证 Hash、版本、依赖、Approval 和生命周期一致性。
