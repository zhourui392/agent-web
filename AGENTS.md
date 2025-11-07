# Repository Guidelines

## Project Structure & Module Organization
- Java Spring Boot (JDK 8). DDD 分层：`interfaces`(Web 接口) / `app`(应用服务) / `domain`(领域模型) / `infra`(基础设施) / `adapter`(网关接口)。
- 源码：`src/main/java/com/example/agentweb/**`
- 配置与静态资源：`src/main/resources/application.yml`，`src/main/resources/static/`
- 测试：`src/test/java/**`，示例：`FsControllerTest.java`、`ChatFlowTest.java`

## Build, Test, and Development Commands
- 构建 Jar：`mvn clean package`
- 本地运行：`mvn spring-boot:run`
  - 端口覆盖：`mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"`
- 运行 Jar：`java -jar target/agent-web-0.1.0-SNAPSHOT.jar`
- 单测：`mvn test`
- P3C 规则（PMD）：`mvn pmd:check`

## Coding Style & Naming Conventions
- 遵循 Alibaba-P3C 规范（见 `pom.xml` 中 `p3c-pmd`）。
- 缩进 4 空格；包名小写；类 `UpperCamelCase`；方法/变量 `lowerCamelCase`；常量 `UPPER_SNAKE_CASE`。
- 控制器仅编排流程，领域逻辑在 `app/domain`，外部交互在 `infra/adapter`。

## Testing Guidelines
- 框架：JUnit 5（`spring-boot-starter-test`）。
- 测试命名：`*Test.java`，放于 `src/test/java` 中对应包路径。
- 覆盖关键路径：控制器、应用服务与异常处理。CI 可执行 `mvn -q test`。

## Commit & Pull Request Guidelines
- 当前历史无明确约定（仅 `Initial commit`）。建议采用 Conventional Commits（如 `feat: xxx`、`fix: yyy`）。
- PR 要求：清晰描述、关联 Issue、包含测试或截图（如前端静态页变更）、列出影响范围与回滚方案。

## Security & Configuration Tips
- 默认端口：`18092`（见 `src/main/resources/application.yml:2`）。
- 限定可访问目录：`agent.fs.roots`，生产环境请缩小到必要工作区。
- Windows 11 示例：PowerShell 设置端口 `mvn spring-boot:run -Dspring-boot.run.arguments='--server.port=8081'`。

## Agent-Specific Instructions
- CLI 代理配置：`agent.cli.codex/*`、`agent.cli.claude/*`。
  - 可用环境变量覆盖：`CODEX_CMD`、`CLAUDE_CLI_CMD`（例如：`$env:CODEX_CMD = "C:\\Tools\\codex.exe"`）。
  - `args` 支持 `${MESSAGE}` 占位符；`stdin` 控制是否从标准输入传递消息。
