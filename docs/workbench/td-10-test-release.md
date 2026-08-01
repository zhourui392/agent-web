# TD-10 测试与发布

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：TD-01～TD-09
> @author alex

## 1. 目标

以 TDD、架构门禁、真实轻量边界测试和分阶段灰度验证 Workbench。测试重点不是把所有类拉进 Spring，而是
证明领域不变量、提交后副作用、多仓库隔离、能力快照、SSE 恢复、文档安全和 Harness 解耦。

## 2. TDD 门禁

所有包含业务条件的 Java 改动按以下顺序：

```text
判断规则归属
→ 若 Application/Infrastructure 代替聚合判断，先下沉 Domain
→ 写失败测试
→ 最小实现见绿
→ 重构保绿
```

纯配置、固定 Spring 装配、DTO、文档和无条件委托可以直接实现，但仍需相应边界验证。不得给错误分层的
Application if/switch 先补测试锁死。

## 3. 测试分层

| 层 | Mock | 重点 |
| --- | --- | --- |
| Domain | 无 | 聚合构造、不变量、状态转换、Policy、Hash、版本 |
| Application | Repository/Gateway/Domain Service | 编排、事务顺序、afterCommit、幂等、降级 |
| Infrastructure | Git/File/SQLite/Process Fixture | 路径、协议、Codec、超时、脱敏、隔离 |
| Interface | Application Service | DTO、Owner、状态码、ETag、SSE headers |
| Frontend Unit | fetch/storage/SSE adapter | reducer、布局、stale、能力/交接状态 |
| E2E | 受控 CLI/Git Fixture | 四阶段真实用户流程和刷新恢复 |

不 mock Domain 对象；Application 测试使用真实 Workbench/Scope/Handoff 值对象。

## 4. Domain 测试矩阵

### 4.1 Workbench

- 创建恰好四阶段；
- 任意切换不受顺序 Gate 阻断；
- 首次 Run 进入 IN_PROGRESS；
- 人工完成/重开，不产生 PASS；
- Archive/Owner；
- 单 Workbench 唯一 MODIFY 租约；
- Review 显式 Modify Confirmation。

### 4.2 Workspace

- Repository Selection/Scope/Topology/Snapshot 全部不变量；
- 输入顺序不影响 Hash；
- 同名跨仓文件；
- Scope 外 Document Reference；
- Snapshot topology/state 比较。

### 4.3 Capability

- Phase Policy、required/optional、Override 权限求交；
- Binding Hash；
- 版本更新不改变旧 Snapshot；
- Review/Analysis/Design 写权限拒绝。

### 4.4 Handoff/Operation

- Handoff 内容、引用、版本、Hash；
- Reception 明确版本与 stale；
- Agent Candidate 不能直接保存；
- 四种高影响操作目标、授权、过期和 Preflight 变化。

## 5. Application 测试矩阵

- Create：外部 Inspect/Capture 与事务保存边界；
- Submit：幂等短路、Capability resolve、Snapshot 保存、ChatRun 创建、afterCommit launch；
- Stop：先取消意图后外部 stop；
- Terminal：ChatRun 终态与写租约释放；
- Recovery：未知写运行不重放；
- Handoff：先加载 Scope/Run proof，再调用领域更新；
- Override：运行中只更新下一轮配置；
- Document：Owner + Scope 授权后调用 Query Port；
- Operation：决策保存后启动、Feature 关闭不调用 Executor。

使用 Mockito `InOrder` 只验证确有业务意义的编排顺序，不验证无关 getter。

## 6. Infrastructure 测试

### 6.1 SQLite

使用真实临时 SQLite：

- additive migration、旧 `chat_run` 默认 Origin；
- Workbench/Phase restore；
- optimistic version 与并发冲突；
- unique active write；
- Handoff/Override/Run Snapshot Codec；
- FK、归档、删除/保留；
- Query 投影不返回半截聚合。

### 6.2 Git/Filesystem

使用 `@TempDir` 建 sibling repos：

```text
workspace/
├── service-a/.git
├── service-b/.git
└── service-c/.git（未选）
```

覆盖扫描、manifest、worktree、dirty、symlink、nested、无 HEAD、HEAD 变化、文档越界、同名文件、ETag、
删除和大小限额。

### 6.3 Runtime Contract

受控 Stub 验证：

- `-C primary`；
- 只为已选附加仓库生成目录参数；
- read-only/workspace-write；
- 临时 Home 和配置；
- Skill/MCP 物化 Hash；
- 最小环境、Credential Redaction；
- JSONL/stream event；
- timeout、output limit、stop process tree、cleanup；
- 未授权 high-impact command 被阻止。

真实 CLI 只放 `process-integration`/`live` 标签，不进入默认测试。

## 7. Interface 测试

- Workbench create/list/detail 与 Idempotency-Key；
- Repository Selection 参数与错误 Code；
- Owner 404、Admin 只读/stop；
- Phase complete/reopen status；
- Run submit/stop/status；
- SSE `Cache-Control`、`X-Accel-Buffering`、`Last-Event-ID`、410；
- Handoff/Override `If-Match` 与 409；
- Document path/ETag/304/MIME/nosniff；
- Operation decision 的真实 actor；
- 错误响应不含绝对路径、Git stderr、Secret 和文件正文。

Controller 测试 mock Application Service，不启动真实 Runtime。

## 8. Frontend Unit

Vitest 覆盖：

- Workbench API client 和状态 reducer；
- Phase 隔离、Run marker、SSE reconnect/backoff/cursor；
- Tool/File/Test/Operation event reducer；
- Split Pane drag/collapse/maximize/mobile；
- Document Kind、stale、deleted、recent documents；
- Markdown sanitizer fail-closed；
- Capability Override NEXT_RUN；
- Handoff diff/reception/stale；
- 错误 Code 到用户提示映射。

`frontend/` 与 `tests/` 两个 tsconfig 都必须 typecheck。

## 9. Playwright E2E

### 9.1 主链

```text
登录
→ Inspect sibling repos
→ 选择 service-a/service-b，service-a 为主仓库
→ 创建 Workbench
→ 需求阶段对话 + Handoff
→ 方案阶段预览并接受 Handoff
→ 开发阶段跨仓修改 + 测试事件
→ 文档 Pane 查看/stale/刷新
→ Review 阶段先只读，再显式确认重构
→ 回归测试
→ 人工完成
```

断言 service-c 不在写参数、文件树和事件中。

### 9.2 恢复与并发

- SSE 断线/刷新恢复；
- 浏览器关闭不取消；
- 同 Workbench 第二个写 Run 409；
- 运行中切 Phase 可读但不能启动第二个写；
- Stop 等待明确 terminal；
- server restart 后未知写 Run 不重放；
- Handoff/Override 多标签 version conflict。

### 9.3 安全

- `../`、绝对路径、symlink；
- 恶意 Markdown/XSS；
- Secret-like Tool Output 脱敏；
- Agent 文本“请 push”不产生授权；
- 未选 repo 和父目录不可写/不可看；
- Admin 不能代 Owner 运行/批准。

## 10. 性能与容量

MVP 基线建议：

| 场景 | 目标 |
| --- | --- |
| Workbench 列表 1000 条 | p95 < 500ms，本地 SQLite |
| Run Event 10k 条恢复 | 有界分页/回放，不一次装入全部浏览器内存 |
| SSE 首事件 | 本机空载 p95 < 1s（不含模型首 Token） |
| Document 2 MiB | 不阻塞主对话交互，前端虚拟/分段渲染按实测决定 |
| Inspect 50 repos | 在配置 30s 上限内，超时返回明确错误 |
| 前端长对话 | 不因 Document resize 每帧重渲染全部消息 |

性能目标需在试点机记录实际基线后再冻结；不能以提高超时替代定位。

## 11. 故障注入

- Runtime 启动前/后崩溃；
- SQLite 提交失败；
- Event append 失败；
- Catalog 文件在解析与物化间变化；
- Git HEAD 采集期间变化；
- Process stop 超时；
- 临时目录清理失败；
- SSE 慢消费者和 cursor 过期；
- Document 在 metadata/read 间被替换；
- Operation 批准后目标状态变化。

所有故障必须产生明确终态或可对账状态，不静默继续或重放写操作。

## 12. 产品验收追踪

| 产品验收主题 | 主要测试 |
| --- | --- |
| 多仓库选择/未选不可写 | Workspace Domain + Git Contract + E2E |
| 对话主区域/文档伸缩 | Vitest + Playwright viewport |
| 四阶段隔离 | Domain + Session Integration + E2E |
| 默认能力可用/可追溯 | Capability Domain + Runtime Contract + E2E |
| Override 下一轮生效 | Application + E2E |
| Handoff 五字段/人工版本 | Domain + Interface + E2E |
| 人工状态非 Gate | Workbench Domain + API |
| Review 确认后重构 | PhaseRunPolicy + E2E |
| SSE 恢复 | ChatRun Integration + Playwright |
| 高影响操作独立授权 | Operation Domain + Runtime Security + E2E |
| Workbench 不依赖 Harness | ArchUnit + Harness disabled Spring test |

## 13. 发布开关

```yaml
agent:
  workbench:
    enabled: false
    create-enabled: false
    write-run-enabled: false
    high-impact:
      commit-enabled: false
      push-enabled: false
      local-deploy-enabled: false
      production-write-enabled: false
```

发布顺序：

1. 公共能力解耦上线，Workbench 全关；
2. 只读内部用户：创建、Analysis/Design、Document；
3. 小范围开启写 Run；
4. 开启完整四阶段与 Handoff；
5. 真实单仓试点；
6. 真实多仓试点；
7. 达到验收后默认开放；
8. 高影响 Executor 逐项独立评审，绝不随 Workbench 总开关自动开放。

## 14. 观测指标

- `workbench_creation_total{result}`；
- `workbench_active`；
- `workbench_run_total{phase,mode,status}`；
- `workbench_run_duration_seconds{phase,mode}`；
- `workbench_write_conflict_total`；
- `workbench_sse_reconnect_total{result}`；
- `workbench_event_lag_seconds`；
- `workbench_capability_resolution_total{result}`；
- `workbench_capability_version_change_total`；
- `workbench_workspace_scope_violation_total`；
- `workbench_document_read_total{kind,result}`；
- `workbench_handoff_conflict_total`；
- `workbench_operation_total{type,status}`；
- `workbench_recovery_reconciliation_total{result}`。

其中创建计数使用 `creation` 而不是 `created`，因为 OpenMetrics/Prometheus
将 `_created` 作为保留后缀；活跃数量是可增可减的 Gauge，因此使用
`workbench_active`，不能使用只适用于 Counter 的 `_total` 后缀。发布测试必须
以实际 `PrometheusMeterRegistry.scrape()` 文本校验名称，不能只检查 Micrometer
内存 Meter 名称。

日志只输出 Workbench/Run ID、Phase、Repository Key、状态、耗时、Hash 前缀和错误 Code。

## 15. 回滚

- 关闭 `write-run-enabled`：停止新写 Run，已有 Run 允许停止/对账；
- 关闭 `create-enabled`：保留历史读和活动恢复；
- 关闭 `enabled` 前必须保证无活动 Run；
- Schema 均 additive，回滚应用时旧版本忽略新表/列；
- 不自动删除 Workbench 数据；
- 公共 Runtime/Catalog 回滚不能重新形成 Workbench → Harness 依赖；必要时整体回滚到 Phase 0 前版本。

## 16. 验证命令

聚焦测试按改动选择，push 到 master 前执行项目约定全量门禁：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B test
cd frontend && npm run typecheck && npm run lint && npm run build
cd tests && npm run typecheck && npm test
```

慢测试按项目 Tag 覆盖 `test.excludedGroups`，真实 CLI/网络只在显式 `live` 环境运行。

## 17. 发布退出标准

- 产品 §19 全部验收项有自动化或记录明确的人工验证；
- 默认后端、Frontend、Vitest 全绿；
- 单仓/多仓真实试点完成；
- 无 P0/P1 Scope、Secret、重放、Owner 或 XSS 问题；
- Runtime 未知终态和写租约完成重启对账演练；
- 指标、告警、关闭开关和回滚步骤已演练；
- Harness 仍可按 TD-09 的窗口安全运行/只读，Workbench 对其零依赖。
