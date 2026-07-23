# Harness M2 自测报告

> 执行日期：2026-07-23
> JDK：OpenJDK 21.0.11
> Node/Vitest：Vitest 3.2.7
> 外部 Agent/MCP/HTTP：未调用

> 设计复核补充（2026-07-23）：本报告的 PASS 是 M2 当时执行结果，不包含“Snapshot 创建后再更新 HarnessRun”的跨 Repository 场景。后续复核确认旧 `HarnessRunRepository.update()` 删除 Attempt 时可能通过外键级联删除 Snapshot；M3.0 已用真实 SQLite 红测复现并修复为增量持久化。M3 Runtime 合同缺口也已完成重新验收，详见 [M3 实现记录](../m3/README.md)和[自测报告](../m3/test-report.md)。

## 1. TDD 红—绿证据

本阶段按门禁顺序获得以下可复现证据：

1. `SkillSelectionPolicyTest` 先因 Manifest、信任、选择、依赖、冲突和能力请求类型不存在而编译失败；最小 Domain Policy 后 9 个初始用例转绿。
2. Prompt/Snapshot 测试先因 Prompt Pack、Assembler、Parts 和 Snapshot 类型不存在而编译失败；完成固定顺序与稳定 Hash 后转绿。
3. Catalog 测试先因端口、异常和文件实现不存在而编译失败；完成 YAML、原始字节 Hash、热发现和路径约束后转绿。
4. Application 测试先因 Snapshot Repository、Command、Service 和 View 不存在而编译失败；完成“已有不重算、没有才解析并 save-if-absent”编排后转绿。
5. SQLite 测试先因 Repository/QueryService 不存在而编译失败；实现后又暴露 Markdown 尾换行被领域对象裁剪、资源 Hash 与 Prompt Hash 不一致，改为保留原文后转绿。
6. Controller 测试先因 M2 Controller 不存在而编译失败；完成 DTO、状态码、404/422 映射和 Feature Flag 后转绿。
7. 授权复核红测证明显式 Grant 曾能越过 ANALYSIS Stage 放行命令；增加 Stage Policy 求交后转绿。
8. 循环依赖复核红测证明两个循环 Skill 同时作为默认项时曾绕过检测；增加已选图循环检测后转绿。
9. Spring 纵向测试首次因 Catalog 多构造器未显式注入而启动失败；修正装配后，旧 Attempt 不变、新 Attempt 使用新 Hash 的全链路转绿。

Domain 测试不 Mock Domain；Application 只 Mock Repository/Catalog，使用真实聚合、Policy 与 Assembler；Infrastructure 使用真实 `@TempDir` 文件系统和 SQLite；普通测试不调用 CLI、MCP 或外部 HTTP。

## 2. M2 聚焦后端测试

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q \
  -Dtest=SkillSelectionPolicyTest,HarnessPromptAssemblerTest,HarnessRunCapabilityTest,\
HarnessCapabilityServiceImplTest,FileSystemPromptPackCatalogTest,FileSystemSkillCatalogTest,\
SqliteCapabilitySnapshotRepositoryTest,HarnessCapabilityControllerTest,\
HarnessCapabilityFeatureFlagTest,ArchitectureTest test
```

结果：PASS。

覆盖信任、阶段/Runtime、选择原因、依赖、冲突、Stage 授权求交、Prompt 顺序、Hash、Catalog 安全、Application 编排、SQLite、CQRS、API、Feature Flag 与 DDD 架构护栏。

## 3. 默认后端快速回归

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q test
```

结果：PASS。

按项目默认配置排除 `live`、`git-integration`、`spring-flow`、`process-integration`；没有调用真实 CLI 或外部 Provider。

## 4. M2 Spring 纵向样例

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  mvn -q \
  '-Dtest.excludedGroups=live,git-integration,process-integration' \
  '-Dgroups=spring-flow' \
  -Dtest=com.example.agentweb.HarnessM2FlowTest test
```

结果：PASS。

真实经过：

```text
HTTP 创建 Run / 启动 ANALYSIS Attempt 1
→ 文件 Catalog 读取 Prompt Pack 与默认 Skill
→ Domain 选择、授权求交与 Prompt 装配
→ SQLite 固化 Snapshot 1
→ 修改临时 Skill 的 SKILL.md
→ GET Snapshot 1，Package/Snapshot Hash 保持不变
→ 上传 Artifact、通过 Gate、批准并 Retry
→ ANALYSIS Attempt 2 热读取修改后的 Skill
→ 新 Package Hash、Prompt Hash、Snapshot Hash 均变化
```

测试中的 Catalog 是从仓库内置资源复制出的临时目录；没有修改源码资源或调用 Agent。

## 5. 前端纯函数回归

首次在新 worktree 运行时缺少本地 `tests/node_modules`，因此先按锁文件执行：

```bash
cd tests
npm ci
npx vitest run
```

结果：PASS，7 个测试文件、102 个测试用例全部通过；其中 `harness-utils.spec.ts` 3 个用例覆盖选择、拒绝、授权原因和 Hash 展示。

## 6. 静态检查与 Diff

执行：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -q pmd:check
git diff --check
```

`git diff --check`：PASS。

`mvn pmd:check`：命令退出码为 0，但不能视为有效扫描。项目当前 `maven-pmd-plugin 3.20.0` 搭配 Alibaba P3C 的旧 ASM 在 `target/pmd.xml` 对包括存量代码和 M2 代码在内的 Java 21 class 报告 `Unsupported class file major version 65`；同时项目配置 `failOnViolation=false`。M2 没有修改 PMD 工具链，避免把质量工具升级扩大到本里程碑范围。残余风险已明确保留，不能将此项误报为 PMD 规则全量通过。

额外核对：

- 所有新增 Java 类包含 `@author zhourui(V33215020)` 和 `@since 2026-07-23`；
- 无通配符导入；
- Harness Domain 不依赖 Spring、Application、Infrastructure、Interface 或 Adapter；
- Harness Application 不依赖 Infrastructure 实现；
- Prompt/Skill 文件读取只发生在 Infrastructure；
- Catalog 不读取用户级 Codex/Claude 认证目录，也不处理 Secret；
- `AGENTS.md`/`CLAUDE.md` 不进入 Harness 自动发现或 Prompt 注入；
- `ArchitectureTest` 通过。

## 7. 未执行项与边界

- 未执行 `mvn package`、服务重启、部署或 Git push。
- 未调用真实 Codex/Claude CLI、OpenAI/Anthropic Provider 或 MCP。
- M2 当时未实现 MCP Registry、Secret Reference、Runtime Adapter、Runtime Execution、取消和对账；这些能力已在 M3 形成主体基线，但 M3 Runtime 合同尚在重新验收，不能写作全部完成。
- M2 管理页完成纯函数单测与后端 API Slice/纵向测试，未新增 Playwright 场景；页面没有复杂交互状态机，残余风险为浏览器布局与视觉回归。
- 默认 Catalog 根是源码开发路径；JAR/生产运行必须显式配置管理员维护的外置目录。
- M2 当时未覆盖 Snapshot 固化后再执行 Artifact/Gate/Approval/取消所触发的 HarnessRun 聚合更新；M3.0 已补真实 SQLite 红测并修复增量持久化，Snapshot 现可作为 RuntimeExecution 的可靠外键基线。
