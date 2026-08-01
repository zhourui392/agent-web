# TD-07 阶段上下文包

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-02](td-02-workbench-domain-persistence.md)、[TD-03](td-03-phase-chatrun-sse.md)
> @author alex

## 1. 目标

为独立 Phase Session 提供结构化、人工可编辑、版本化的交接边界。系统可以生成候选，但最终 Handoff 始终
是人工版本；上游更新只产生提示，下游必须明确接受一个版本，不能自动失效或覆盖。

## 2. 领域模型

```text
PhaseHandoff
├── HandoffId(workbenchId, sourcePhase)
├── summary
├── decisions[]
├── openQuestions[]
├── pinnedFiles[]: DocumentReference
├── referencedRuns[]: WorkbenchRunReference
├── contentHash
├── updatedBy / updatedAt
└── version
```

值对象：

- `Decision(text, rationale?, status)`；MVP status 固定 `CONFIRMED`，不引入审批状态机；
- `OpenQuestion(text, ownerHint?)`；不自动判断 resolved；
- `DocumentReference(repositoryKey, relativePath)`；
- `WorkbenchRunReference(runId, phase, safeSummary)`；
- `HandoffReception(sourcePhase, sourceVersion, sourceHash, acceptedBy, acceptedAt)`。

## 3. 不变量

1. Handoff 必须属于一个已存在 Workbench 和固定 Phase。
2. Pinned File 必须在 Repository Scope 内，不能重复。
3. Referenced Run 必须属于同一 Workbench，且不能重复。
4. 所有文本有长度/条数上限并做控制字符校验；不接受 HTML 作为可信内容。
5. 更新必须匹配 expected version。
6. contentHash 对规范化字段和稳定顺序计算；updatedAt/updatedBy 不进入 Hash。
7. Agent Candidate 不能直接覆盖 Handoff。
8. 下游 Run 只注入已记录的 Reception 版本；无 Reception 时首次运行必须先预览并确认默认来源。

## 4. 限额

| 字段 | 推荐默认 |
| --- | ---: |
| Summary | 8000 chars |
| Decisions | 50 项，每项 2000 chars |
| Open Questions | 50 项，每项 2000 chars |
| Pinned Files | 100 |
| Referenced Runs | 50 |
| 整体序列化大小 | 256 KiB |

超过上限返回字段级错误，不静默截断人工内容。

## 5. Agent 候选

候选生成是独立 Application 用例：

```text
GenerateHandoffCandidate
→ 读取当前 Phase 对话的有界投影
→ 使用只读 Agent/确定性解析生成 Candidate
→ 保存 candidateId + baseHandoffVersion + payload + expiresAt
→ 返回 UI 逐项对比
```

用户可以逐项接受、编辑、忽略，然后调用正常 `PUT handoff`。Candidate 不是聚合事实，不触发下游 stale，
过期可删除。候选失败不影响人工编辑。

MVP 也可先不持久化 Candidate，只在浏览器确认前保持；无论采用哪种方式，都不能由 Agent 直接保存最终版本。

## 6. 上游版本接收

固定默认上游：

```text
REQUIREMENT_ANALYSIS → SOLUTION_DESIGN
SOLUTION_DESIGN → IMPLEMENT_TEST
IMPLEMENT_TEST → REVIEW_REFACTOR
```

Phase 可任意导航，但默认 Handoff 来源按上述关系。额外来源后续可显式选择，不在 MVP 自动拼所有上游。

### 6.1 首次运行

```text
目标 Phase 尚无 Reception
→ UI 展示上游最新 Handoff Preview
→ 用户发送第一条消息时携带 sourceVersion
→ 事务内记录 Reception
→ Run Snapshot 固化 sourceVersion/sourceHash
```

如果预览后上游 version 已变化，返回 409 并要求重新预览，不静默注入新内容。

### 6.2 上游更新

```text
source latestVersion > reception.sourceVersion
→ Query 投影返回 stale=true
→ UI 展示版本差异
→ 用户选择“接受新版本”或“保留当前版本”
```

接受只更新 Reception，不自动修改目标 Phase 自己的 Handoff，也不删除下游消息或状态。保留当前版本时提示
继续存在，但不阻塞 Run。

## 7. Prompt 注入

只注入人工保存的规范内容：

```text
[上游阶段交接]
来源阶段 / 版本 / Hash
Summary
Decisions
Open Questions
Pinned Files（repositoryKey/path）
Referenced Runs（runId + 安全摘要）
```

- 不复制完整聊天；
- 不展开 Tool Output；
- Referenced Run 只带安全摘要，需要详情时 Agent 通过受控查询读取；
- Pinned File 只带引用，正文按 Capability/Document 权限读取；
- Prompt Part 有独立 Hash 和大小，进入 WorkbenchRunSnapshot。

## 8. API

### 8.1 读写 Handoff

```http
GET /api/workbenches/{id}/phases/{phase}/handoff

PUT /api/workbenches/{id}/phases/{phase}/handoff
If-Match: 3
```

```json
{
  "summary": "...",
  "decisions": [{"text": "...", "rationale": "..."}],
  "openQuestions": [{"text": "..."}],
  "pinnedFiles": [{"repositoryKey": "agent-web", "relativePath": "README.md"}],
  "referencedRuns": [{"runId": "..."}]
}
```

### 8.2 Source Preview

```http
GET /api/workbenches/{id}/phases/{targetPhase}/handoff-source
```

返回 current reception、latest source、stale、字段级 diff 摘要和可接受版本。

### 8.3 Accept

```http
POST /api/workbenches/{id}/phases/{targetPhase}/handoff-receptions

{
  "sourcePhase": "SOLUTION_DESIGN",
  "sourceVersion": 4,
  "sourceHash": "..."
}
```

## 9. Diff

Diff 是查询能力，不进入 Domain 状态：

- Summary：文本行级 diff；
- Decisions/Open Questions：按规范化内容 Hash 做 added/removed；
- Pinned Files：按 Document Reference；
- Referenced Runs：按 Run ID。

返回 DTO，不把 Diff 结果持久化。前端使用成熟 diff 库或简单集合投影，不实现语义自动合并。

## 10. 并发与安全

- Handoff `PUT` 使用 version，409 返回当前安全投影；
- Reception Accept 校验 source version/hash 仍存在；
- Owner 才能编辑/接受；Admin 只读；
- 文本按普通不可信用户内容处理，展示必须净化；
- Handoff 不能包含 Secret；保存前运行轻量 Secret Pattern 检测，命中时拒绝并只返回字段名/规则，不回显值；
- Pinned File 校验结构引用，不读取正文做业务判断。

## 11. 测试

Domain 无 Mock：

- 所有字段、重复引用、Scope/Run 归属证明；
- Hash 稳定、版本递增；
- Candidate 不可直接成为最终状态；
- Reception 绑定明确版本。

Application Mockito：

- 加载 Repository Scope/Run metadata 后调用领域工厂；
- 上游更新只改变查询 stale，不修改下游；
- Run Snapshot 使用 Reception 版本；
- 409 不产生部分保存。

Infrastructure SQLite：

- JSON round-trip、乐观锁、Reception 唯一约束；
- 大小上限、历史版本读取策略；
- Workbench 归档后的只读恢复。

Vitest/Playwright：

- 人工逐项编辑、版本冲突提示；
- 首次 Run 预览；
- 上游更新 stale 和差异；
- 接受/保留不会自动改变 Phase 状态；
- Pinned File 点击打开 Document Pane。

## 12. 验收标准

- 五类字段完整持久化并可人工编辑；
- Agent 只能生成候选；
- 下游注入版本可追溯；
- 上游更新不自动覆盖、不自动失效；
- Handoff 不复制大段聊天/工具输出；
- 并发编辑不会静默丢失。
