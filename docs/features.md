# 功能说明

## 对话与会话

- **多 CLI 后端** — 支持 Claude CLI、Codex CLI（`codex exec --json`），通过 `CliDialect` 策略路由。
- **Web 化交互与实时流式** — 聊天界面直连 CLI 代理，SSE 流式推送，Codex 事件自动归一化到统一前端契约。
- **可恢复聊天流** — 页面统一通过 `ChatRun` 后台执行，和浏览器连接解耦；支持 `Last-Event-ID` 回放、刷新恢复、断网重连、多标签页订阅和显式停止。
- **会话管理** — 多会话隔离，每个会话绑定独立工作目录与 Agent 类型，支持 resume 续接、回退重开。
- **会话持久化** — SQLite 落库并使用内存 L1 缓存，服务重启不丢历史。
- **会话反馈** — 对结果打分和评论，作为知识精炼与召回的质量信号。
- **图片与文件上下文** — 聊天框支持粘贴或上传图片与文件，内容落入工作空间 `upload_pic/`，并自动加入消息上下文供 Agent 读取。
- **文件系统浏览** — 支持目录浏览、文件上传、下载和删除，统一受 `agent.fs.roots` 白名单约束。
- **只读对话分享** — 可生成公开链接免登录查看历史；分享页不能续聊、不能启动 Agent，也不暴露工作目录。
- **定时任务** — Cron 表达式驱动定时 Agent 任务，每次触发产出一条独立会话。
- **Git Worktree** — 按分支切换工作空间，支持嵌套工作空间布局、一键更新全部 worktree 和分支名校验。
- **Slash Commands** — 自动扫描工作目录下 `.claude/commands`、`.claude/skills`、`.codex/skills` 的自定义命令并展开。
- **AgentRun 统一 Prompt 组装** — 各执行入口经 `app/agentrun/` 应用层管线装配 prompt：环境约束 → workspace 上下文 → 知识预召回 → 历史 RAG → 用户问题 → 输出格式；六段可开关、可降级、可观测，并记录 SHA-256 prompt hash。
- **NATIVE 进程内诊断 Agent** — 用户可在普通聊天手动选择只读诊断运行时；以 AgentKit 0.2.1 普通 JAR 集成，复用 ChatRun、SQLite checkpoint 与可恢复 SSE，不启动 AgentKit CLI。

## Local Development Workbench

- **四阶段研发主流程** — `/workbench.html` 提供 `REQUIREMENT_ANALYSIS → SOLUTION_DESIGN → IMPLEMENT_TEST → REVIEW_REFACTOR` 四阶段工作台，每个阶段使用独立会话。
- **多仓 Repository Scope** — 从受信 Workspace Root 扫描 Git 仓库，由用户选择仓库集合、主仓和精确的 `READ` / `MODIFY` 边界；Scope 在 Workbench 创建后冻结。
- **可审计能力绑定** — 每阶段有默认 Phase Capability，可为下一轮配置 Override；每个 Run 使用不可变 Snapshot 冻结 Rules、Skills、MCP、Runtime、Repository Scope、Prompt 与 Handoff Reception。
- **结构化阶段交接** — Agent 只生成 Handoff Candidate，由用户采用、编辑或拒绝；正式 Handoff 固定为 Summary、Decisions、Open Questions、Pinned Files、Referenced Runs 五个字段，首次下游 Run 在提交事务内接收最新版本。
- **Review 与受控修改** — Review Candidate 支持逐项采用或忽略；只有人工保存 Review Opinion 并精确确认后才能发起 `MODIFY`，同时保留受影响测试建议与执行状态。
- **文档与附件上下文** — 只读文档 Pane 支持折叠、最大化、布局记忆和 stale/manual refresh；仓内文档引用可与浏览器上传附件联合提交，附件正文只进入 Git 忽略的受控存储。
- **后台运行与恢复** — Run 与浏览器连接解耦，支持事件续传、显式 Stop、刷新恢复和服务重启后的状态恢复或对账。
- **高影响操作默认不授权** — `GIT_COMMIT`、`GIT_PUSH`、`LOCAL_DEPLOY`、`PRODUCTION_WRITE` 使用类型化 Operation Proposal；创建 Proposal 只进入 `PROPOSED`，四类 Executor 发布开关均默认关闭。
- **Admin 安全投影** — `/admin/workbenches.html` 只提供安全裁剪后的查询、Stop 和单 Run Reconcile；管理员不能代 Owner 对话、修改 Handoff/Override 或批准 Operation。

Workbench 的页面、创建、写 Run 和公共 Runtime 通过独立开关分级发布，实际默认值以当前 [`application.yml`](../src/main/resources/application.yml) 为准；四类高影响 Executor 默认关闭。当前仅完成自动化和真实 Spring + SQLite + Runtime Stub 边界验证，尚未达到真实用户、真实 Codex/Claude CLI 试点退出标准。产品设计、技术方案与发布证据分别见 [MVP 产品设计](local-development-workbench-mvp-design.md)、[Workbench 技术方案](workbench/README.md)和[发布就绪快照](workbench/release-readiness-2026-08-01.md)。

## 知识精炼与召回

Knowledge Refinery 默认关闭：

- **自动沉淀** — 静默会话自动评分后通过 embedding 写入向量库。
- **RAG 召回** — 前端“RAG 召回”开关开启时，每条消息自动召回历史结论并加入上下文；使用余弦硬闸和向量、triggerSignals Jaccard、时间衰减三维融合重排。
- **召回指标分析** — 保存逐次召回明细和命中 chunk 统计，供阈值校准。

## 平台运维

- **管理台** — `/admin` 使用数据库 `ADMIN` 角色鉴权，提供使用概览、对话浏览、用户账号创建、工作流管理、RAG 语料维护、运行时设置，以及 Workbench 查询、Stop 和 Reconcile 运维。
- **工作流编排** — 定义可复用的多步 workflow，每步使用独立 prompt 模板，并按步记录执行结果。
- **每用户 Git 身份** — 各用户配置自己的 Git identity 与 SCM 凭据；密码加密存储且不回显，用于交付提交归属与解析凭据链。
