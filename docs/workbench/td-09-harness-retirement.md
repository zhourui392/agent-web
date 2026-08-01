# TD-09 Harness 退役

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：Workbench Phase 4 真实试点通过
> @author alex

## 1. 目标

在 Workbench 完成替代验收后安全停止 Harness 新增运行，并最终移除 Harness 页面、写 API、专用领域、配置、
适配器和数据。退役不把历史 Harness Run 自动转换成 Workbench，也不删除已经中性化的 Runtime、Capability、
Workspace 公共能力。

## 2. 退役对象清单

### 2.1 保留并中性化

- Agent Process Kernel、Provider Adapter 公共组件；
- Rule/Skill/MCP Catalog 定义与文件适配器；
- Workspace/Repository Scope/Snapshot 公共模型；
- 通用 Redaction、Credential Reference、Process Watchdog；
- Workbench/Chat 使用的可恢复 Run/SSE 底座。

### 2.2 最终删除

- `domain.harness` 中 Run/Stage/Attempt/Artifact/Gate/Approval/Deployment 专用模型；
- `app.harness` 服务与端口；
- `interfaces` 的 Harness Controllers/DTO；
- `infra.harness` 中未迁为公共能力的 Repository、Artifact/Evidence 和专用 Adapter；
- `config.harness` 与 `agent.harness.*` 配置；
- `frontend/admin/harness.html`、Harness Vue/composables/utils；
- `src/main/resources/harness` 中专用 Stage/Gate/Deployment 资源；
- Harness 专用测试和 Fixture（公共 Runtime Contract Fixture 保留并迁名）；
- 用户确认后的 `harness_*` 表和 Artifact/Evidence 文件。

## 3. 明确不做

- 不把 Harness Stage 状态映射成 Workbench HUMAN_COMPLETED；
- 不把 Artifact 自动转换成 Handoff；
- 不把 Gate/Approval 转换成高影响 Operation；
- 不双写 Harness 与 Workbench；
- 不为了历史数据让 Workbench 长期依赖 Harness Codec/表；
- 不在应用启动时自动 DROP 表或删除 Artifact。

## 4. Feature Flag

退役窗口拆分开关：

```yaml
agent:
  harness:
    enabled: true             # 历史读与组件总开关，最后关闭
    creation-enabled: true    # 先关闭，禁止新 Run
    mutation-enabled: true    # 再关闭，历史只读
    export-enabled: true      # 只读窗口保留
  workbench:
    enabled: false            # 分阶段灰度
```

不能只靠前端隐藏按钮；Controller/Application 同时 fail-closed。错误分别使用：

- `HARNESS_CREATION_DISABLED`；
- `HARNESS_MUTATION_DISABLED`；
- `HARNESS_DISABLED`。

## 5. 退役阶段

### Stage R0：依赖盘点

- 使用 `rg` 和 ArchUnit 列出所有 Harness import、Bean、Controller、表、配置和前端入口；
- 标记每项为公共迁移、历史读取或最终删除；
- 确保 Workbench/Chat 对 Harness 零依赖；
- 固化当前历史导出格式。

退出条件：TD-01 架构规则全通过，公共能力在 `agent.harness.enabled=false` 时仍可用。

### Stage R1：停止新增

- `creation-enabled=false`；
- UI 移除新建入口，保留历史入口；
- 正在运行的 Harness Run 允许完成/取消；
- 指标观察是否仍有创建请求和外部调用方。

退出条件：至少一个约定观察窗口无合法新建需求，所有调用方已迁移或明确下线。

### Stage R2：历史只读

- 等待活动 Runtime/Deployment 全部终态或人工对账；
- `mutation-enabled=false`；
- 保留列表、详情、时间线、Artifact 下载和导出；
- 生成数据/文件完整性报告。

退出条件：无活动执行；Owner/管理员确认无需继续 Harness 写操作。

### Stage R3：导出与备份

- SQLite 备份；
- Artifact/Evidence 目录按 Manifest 导出；
- 每个 Run 导出 JSON metadata、事件、Artifact descriptor/hash、Approval/Gate、Execution summary；
- 导出不包含 Secret 明文、credential reference 解析值或临时 Runtime Home；
- 计算整体 Manifest Hash 并验证可读。

退出条件：备份位置、Hash、恢复演练和数据 Owner 确认齐全。

### Stage R4：删除入口和代码

- 删除 Harness 前端、写 API、Controller、Application、Domain 和专用 Infra；
- 删除 Spring 配置和资源；
- 更新 Security Protected Prefix、README、测试和 CI Fixture；
- 保留独立离线导出查看工具或静态 JSON，不让主应用继续编译 Harness Domain。

退出条件：应用无 Harness Bean/API/package，Chat/Workbench/普通文件与 workspace 功能回归通过。

### Stage R5：显式数据清理

- 默认只保留废弃表，不影响运行；
- 用户/数据 Owner 另行确认后执行版本化清理脚本；
- 清表前二次备份和 Hash；
- 删除 Artifact/Evidence 目录前检查路径必须在配置的 Harness Root 内；
- 清理脚本支持 dry-run、行数/文件数报告，不使用通配任意删除。

## 6. 导出格式

```text
harness-export-<timestamp>/
├── manifest.json
├── runs/<runId>/run.json
├── runs/<runId>/events.jsonl
├── runs/<runId>/artifacts.json
├── runs/<runId>/executions.json
├── blobs/...（按原 classification/权限处理）
└── checksums.sha256
```

Manifest：schemaVersion、appVersion、exportedAt、runCount、blobCount、缺失/损坏列表和整体 Hash。导出失败不得
自动进入删除阶段。

## 7. Schema 清理

待清理表以实际 schema 盘点为准，当前至少包括：

```text
harness_run
harness_stage_execution
harness_stage_attempt
harness_artifact
harness_gate_result
harness_approval
harness_question
harness_event
harness_capability_snapshot
harness_runtime_execution
harness_runtime_event
harness_deployment_execution
```

公共迁移后新建的 `workspace_*`、Capability Catalog 资源和 Runtime 公共数据不属于清理范围。清理脚本必须按
外键顺序或事务内关闭/恢复安全设置，先在 SQLite 副本验证。

## 8. 回滚

| 阶段 | 回滚方式 |
| --- | --- |
| R1 停新建 | 重新打开 creation flag，前提版本仍含写能力 |
| R2 历史只读 | 重新打开 mutation flag，前提无 schema/code 删除 |
| R3 导出 | 无业务变更，修复后重导 |
| R4 删除代码 | 回滚应用版本；数据库/Artifact 尚未删除 |
| R5 清数据 | 只能从已验证备份恢复，不把旧主应用直接连新模型猜测恢复 |

因此数据清理必须晚于代码删除至少一个发布周期。

## 9. 观测

- `harness_creation_rejected_total`；
- `harness_mutation_rejected_total`；
- `harness_active_execution_count`；
- `harness_export_total{result}`；
- `harness_export_corruption_total`；
- `harness_read_api_request_total`；
- `workbench_runtime_public_component_error_total`，验证公共迁移没有受 Harness Flag 影响。

日志只记录 Run ID、计数、Hash 前缀和错误 Code。

## 10. 测试与门禁

- ArchUnit：Workbench/Chat/Public Capability/Public Runtime 对 Harness 零依赖；
- Feature Flag：停新建但历史读可用；停 mutation 后所有写 API fail-closed；
- Export：完整/缺失 Blob、Hash 错误、权限、重复执行；
- Migration：`agent.harness.enabled=false` 时 Workbench Runtime/Catalog 可装配；
- E2E：历史页只读、无新建、WorkBench/Chat 正常；
- Cleanup：仅对数据库副本和临时 Artifact Root 运行；生产清理必须手工确认。

## 11. 最终验收

- 主代码不包含 Harness 包、API、UI、配置或专用资源；
- 主数据库可不含 Harness 表，清理有可验证备份；
- Workbench 和 Chat 不受 Harness Flag/删除影响；
- Runtime/Catalog/Workspace 只有一套公共实现；
- 历史导出可离线读取且 Hash 验证通过；
- 正式交付状态由 requirement-flow 承担，不残留空壳 Harness 状态机。
