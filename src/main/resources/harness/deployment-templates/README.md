# Harness deployment templates

M4 仅从管理员维护的外置只读 Catalog 加载 `local` 模板。每个模板目录使用
`manifest.yml`，命令必须以 YAML token 列表定义 `build`、`deploy`、`healthCheck`、
`acceptance` 和 `rollback`。首版保存 rollback 模板但不会自动执行。

仓库不内置可执行部署模板，避免 Feature Flag 开启后意外执行通用 Shell；受控环境和
E2E 测试必须显式覆盖 `AGENT_HARNESS_DEPLOYMENT_TEMPLATE_ROOT`。
