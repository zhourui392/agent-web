# 使用指南

## 普通对话

1. 登录后在左侧边栏选择工作目录。
2. 点击“开始会话”创建 Agent 会话；可以在顶栏切换 Agent 类型和分支。
3. 在输入框输入指令，按 `Enter` 发送，按 `Ctrl+Enter` 换行；支持粘贴或上传图片与文件。
4. 实时查看 Agent 的流式响应；浏览器刷新、短暂断网或多标签页不会自动取消后台 Run。
5. 在历史列表查看或继续已有对话；删除操作只允许会话创建者执行。
6. 点击“分享”生成只读公开链接。链接持有者可以查看历史和消息中明确引用的图片，但不能续聊、启动 Agent 或获得 owner 的工作目录和 Git 凭据。

页面聊天统一通过后台 `ChatRun` 执行。提交、停止、SSE 恢复和游标过期语义见 [API 指南](api.md)。

## Local Development Workbench

Workbench 页面、创建、写 Run 和公共 Runtime 使用独立开关分级发布，默认值以当前 [`application.yml`](../src/main/resources/application.yml) 为准。需要显式开启基础 Workbench 主流程时，可以设置：

```bash
AGENT_WORKBENCH_ENABLED=true \
AGENT_WORKBENCH_CREATE_ENABLED=true \
AGENT_WORKBENCH_WRITE_RUN_ENABLED=true \
AGENT_COMMON_RUNTIME_WORKBENCH_ENABLED=true \
mvn spring-boot:run
```

登录后访问 `/workbench.html`：

1. 输入受 `agent.fs.roots` 约束的 Workspace Root。
2. 扫描 Git 仓库并选择 Repository Scope、主仓和各仓的 `READ` / `MODIFY` 边界；Scope 在 Workbench 创建后不可变。
3. 选择管理员已发布的 Stage Definition 集合，服务端按发布顺序冻结 Stage Snapshot；后续配置变化不会改写已有 Workbench。
4. 在每个 Stage 内独立对话和运行；Stage 之间不隐式增删、重排或自动流转。

Run 与浏览器连接解耦。关闭页面不会取消后台 Run；页面支持 SSE 续传、显式 Stop、刷新恢复以及服务重启后的状态恢复或对账。已经启动的 Run 使用不可变 Snapshot，后续 Capability Source 变化不会改变它的 Repository Scope、Command、Skill、MCP、Runtime 或 Prompt。

Runtime Command Policy 直接拒绝 commit、push、部署或生产写入等高影响命令；`MODIFY_WORKSPACE` 只授权冻结 Repository Scope 内的普通文件修改。

ADMIN 可以在 `/admin/workbenches.html` 查询安全投影、停止 Run 或对单个 Run 执行 Reconcile，但不能代 Owner 对话或修改 Owner 业务状态。

Workbench 的 Runtime Stub 自动化不代表真实 Codex/Claude CLI 试点已经完成。使用前应阅读 [Workbench 技术设计总览](workbench/README.md)。
