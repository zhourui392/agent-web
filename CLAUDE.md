# Claude Code 项目入口

Claude Code 在本仓库工作时，必须先阅读并遵守 [`AGENTS.md`](AGENTS.md)。该文件是代码修改、分层、测试、安全和发布门禁的唯一智能体规则来源；本文件不复制第二套规则。

项目资料按以下顺序查找：

1. [`README.md`](README.md)：项目概览和最短启动路径。
2. [`docs/README.md`](docs/README.md)：使用、配置、开发、运维与当前架构索引。
3. [`docs/development.md`](docs/development.md)：项目结构、最小测试和发布前门禁。
4. [`src/main/resources/application.yml`](src/main/resources/application.yml)：完整运行配置与默认值。

前端源码只位于 `frontend/`；测试工程位于 `tests/`。已完成的计划、迁移记录和历史验证不在根目录长期保留，需要追溯时使用 Git 历史。
