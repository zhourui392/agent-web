# TD-08 Workbench 高影响命令边界

> 状态：由 TD-11 收敛，当前在线模型不提供类型化高影响操作 API
> 日期：2026-08-05
> 权威后续：[TD-11 Workbench 动态阶段与全局上下文](td-11-dynamic-stages-global-context.md)
> @author alex

## 1. 当前结论

Workbench Runtime 直接拒绝高影响命令。当前 Dynamic Stage 单模型不提供旧工作单元绑定的类型化操作提案、批准或执行 API，也不创建相应数据库表。

以下事实都不能构成高影响操作授权：

- Stage 名称或 Definition Identifier；
- `DISCUSS_READ_ONLY` 或 `MODIFY_WORKSPACE` Run Mode；
- 用户或 Agent 在对话中表达“可以提交、推送或部署”；
- Agent 输出的工具调用、命令建议或文件修改；
- Owner 身份、管理员身份或 Workbench 已人工完成。

## 2. 当前强制边界

公共 Runtime Command Policy 在进程启动前识别并拒绝至少以下类别：

- Git commit；
- Git push；
- 本地部署；
- 生产写入；
- 通过绝对路径、Shell Wrapper、复合命令或别名表达的同类命令。

拒绝必须发生在外部副作用之前，且只返回安全错误码与摘要；不得回显凭据、环境变量、完整命令、绝对路径或原始输出。

`MODIFY_WORKSPACE` 只授权在冻结 Repository Scope 内修改普通工作区文件，不授权提交、推送、部署或生产写入。

## 3. 当前不提供的能力

在线合同中不存在：

- High Impact Operation 聚合；
- Operation Proposal、Approval、Execution 状态机；
- Owner 或 Admin 的批准、执行接口；
- `workbench_high_impact_operation` 或提案表；
- Operation 专属 Telemetry 指标；
- 按特殊 Stage 名称自动开放操作的规则。

新建 SQLite 数据库不会创建上述表；Workbench Owner、Admin 和前端均不暴露相应入口。

## 4. 未来扩展条件

如果以后确需开放高影响操作，必须作为独立、显式授权的能力重新设计，至少满足：

1. 不依赖 Stage 名称和历史工作单元枚举。
2. Target 使用类型化值对象，不能接受任意命令字符串。
3. Proposal、Authorization 与 Execution 分离。
4. 每次授权绑定精确 Workbench、Run、Repository Scope、目标 Hash、有效期和幂等键。
5. Runtime 仍以拒绝为默认；只有受管 Executor 能消费有效授权。
6. Owner 授权与 Admin 运维权限分离，管理员不能代 Owner 创建业务授权。
7. 审计只记录安全摘要和不可逆 Hash，不记录 Secret 或原始命令。
8. 在正式实现前先增加副作用前拒绝、并发、幂等、过期、重放和路径逃逸测试。

这属于未来新功能，不是恢复旧接口，也不需要任何历史数据迁移或兼容读取。

## 5. 验收

- Runtime Policy 对高影响命令失败关闭。
- 普通 Stage Run 不能通过文本、名称或 Run Mode 获得额外权限。
- Owner 与 Admin API 不存在高影响操作提案、批准或执行路径。
- Stage-only Schema 不创建高影响操作表。
- Telemetry 不暴露已移除的 Operation 指标。
- 相关自动化不得调用真实生产写入、真实部署或真实推送。
