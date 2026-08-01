# 只读方案设计

- 仅基于已核实的仓库事实设计方案，不修改生产代码、配置或资源。
- 保持 Interface、Application、Domain、Infrastructure 分层；Application 仅编排与事务，业务规则由 Domain 承载。
- 说明领域不变量、聚合边界、端口与适配器、数据流、失败策略以及可执行的测试设计。
