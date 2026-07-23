# Harness M1 自测报告

> 执行日期：2026-07-23  
> JDK：OpenJDK 21.0.11  
> 外部 Agent/MCP/HTTP：未调用

## 1. TDD 证据

本阶段按以下顺序获得红—绿证据：

1. Domain 测试先因 `HarnessRun`、`StageExecution`、Artifact/Gate/Approval 类型不存在而编译失败。
2. 最小 Domain 实现后，测试暴露 Artifact SHA-256 断言和生命周期时间样例错误；修正样例后领域测试转绿。
3. SQLite/Artifact Store 测试先因 Repository、Store 和存储异常类型不存在而编译失败，完成实现后转绿。
4. Application 测试先因编排端口不存在而编译失败，完成事务编排后转绿。
5. Query/Controller 测试先因 QueryService、详情 DTO、Controller 不存在而编译失败，完成接口后转绿。
6. 取消 DRAFT Run 的恢复测试先以 `started stage must have attempts` 失败，修正 Stage 恢复不变量后转绿。

测试均使用真实 Domain 对象；Application 只 Mock Repository、Artifact Store、路径端口和当前用户。普通单测没有启动真实 CLI、MCP、HTTP Provider、Redis 或 MQ。

## 2. 默认后端快速回归

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

结果：PASS。

该命令覆盖 Domain、Application、真实 SQLite/`@TempDir`、MVC Slice、Feature Flag、ADMIN Filter 和 `ArchitectureTest`；按项目默认配置排除 `live`、`git-integration`、`spring-flow`、`process-integration`。

## 3. M1 Spring 纵向样例

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  mvn -q \
  '-Dtest.excludedGroups=live,git-integration,process-integration' \
  '-Dgroups=spring-flow' \
  -Dtest=com.example.agentweb.HarnessFlowTest test
```

结果：PASS。

该样例真实经过：

```text
HTTP 创建 Run
→ SQLite 保存 DRAFT
→ 启动 ANALYSIS Attempt
→ Artifact Store 保存 4 个正文
→ SQLite 保存 Artifact 元数据
→ 记录 5 个确定性 Gate
→ 请求 Approval
→ QueryService 返回 Artifact 基线 Hash
→ 批准 ANALYSIS
→ QueryService 返回 PASSED、Artifact、Gate、Approval 和事件时间线
```

路径授权和当前管理员在测试中使用端口 Mock；Controller、Application、Domain、SQLite Repository、Schema 初始化、Artifact Store 和 QueryService 均为真实实现。

## 4. 质量检查

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q pmd:check
git diff --check
```

结果：PASS。

额外静态核对：

- 所有新增 Java 类包含 `@author zhourui(V33215020)` 和 `@since`。
- Harness 新代码没有通配符导入。
- Domain Harness 不依赖 Spring、Application、Infrastructure、Interface 或 Adapter。
- Application Harness 不依赖 Infrastructure 实现。
- Harness 与 Workflow 领域包双向无依赖。
- Artifact Root 已确认处于 `/data/` Git 忽略规则内；POSIX 权限测试覆盖目录 `700`、文件 `600`。

## 5. 未执行项与边界

- 未执行 `mvn package`、服务重启或部署。
- 未调用真实 Codex/OpenAI Provider；M0 记录的在线凭据 HTTP 401 仍待 M4 前修复。
- 未运行真实部署或回滚；属于 M4。
- 未实现 Prompt Pack/Skill/Capability Snapshot；属于 M2。
- 未实现 Codex Runtime Adapter、MCP 挂载、进程取消和 Runtime 对账；属于 M3。
