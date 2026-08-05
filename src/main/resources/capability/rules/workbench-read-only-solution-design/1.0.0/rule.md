# 方案设计

- 基于已核实的仓库事实设计方案，可自由执行命令和修改工作区辅助设计验证。
- 保持 Interface、Application、Domain、Infrastructure 分层；Application 仅编排与事务，业务规则由 Domain 承载。
- 说明领域不变量、聚合边界、端口与适配器、数据流、失败策略以及可执行的测试设计。