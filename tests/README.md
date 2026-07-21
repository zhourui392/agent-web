# agent-web E2E 测试

独立的 npm 工程,不进 Maven 构建。仅本地/CI 跑测试时使用。

## 首次安装

```bash
cd tests
npm install
npm run test:install   # 装 Playwright 浏览器
```

## 跑测试

```bash
# 自动启停 Spring Boot (e2e profile) + 跑全部 E2E
npm run e2e
npm run e2e:clean     # 删除 data/agent-web-e2e.db

# 看着浏览器跑 (debug 用)
npm run e2e:headed

# 指定单个用例
npx playwright test chat.spec.ts
npx playwright test -c playwright.qa-prefix.config.ts
```

## 前置条件

- 已安装 Java 8+ 和 Maven
- 端口 18099 空闲 (e2e profile 占用,避开生产 18092)
- 第一次跑前: `mvn package -DskipTests` 加速启动 (可选)

## 工作机制

- Playwright 启动时通过 `webServer` 配置自动 `mvn spring-boot:run -Dspring-boot.run.profiles=e2e`
- e2e profile (`src/main/resources/application-e2e.yml`) 把 Claude CLI 替换成 `cmd /c echo`
- 独立 db: `data/agent-web-e2e.db` (跑完可删)
- 跑完 Playwright 会发 SIGTERM 关 Spring Boot
