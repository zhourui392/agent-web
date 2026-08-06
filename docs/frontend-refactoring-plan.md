# 前端重构设计方案

> 依据：2026-07-25 前端现状评估（3 路并行探索：主聊天应用 / admin MPA / 构建工具链与测试）。本文保留历史迁移记录；当前实现为 Vite + Vue SFC/TS，最后核对：2026-08-06。
> 原则：每步独立可合、行为不变优先、每步完成跑前端测试全绿再进下一步；**不自动打包/重启服务**。
> 范围：`frontend/` 下前端代码（JS/TS/CSS/Vue/HTML），不含后端 API 契约变更。
> 决策状态：**引入 Vite + 渐进 TS**（决策 A 已定，依据见 §3）。迁移策略为"分功能建测试围栏 -> 改造 -> 迁 TS"，每个功能模块独立可合、独立可回滚。本文件里程碑统一使用 `FE-R*`。
>
> 注：§1～§7 保留迁移前基线和实施记录，不代表当前目录、入口或工具链；当前事实以 `frontend/`、`package.json`、`vite.config.ts` 和本文件的里程碑状态为准。

## 1. 现状诊断

### 1.1 规模与结构

FE-R3 之前的历史基线是 **CDN-only Vue 3 MPA**，无构建、无 ES module、无 TS、无 ESLint、无 CI。共 ~5000 行 JS：

| 文件 | 行数 | 角色 |
|---|---|---|
| `js/app.js` | 876 | 主应用，单 `setup()` 闭包 827 行 |
| `js/components/chat-panel.js` | 1094 | 聊天巨型组件，189 行内联 template + 870 行 setup |
| 其余 ~12 个 JS 文件 | ~1800 | admin pages / lib / utils / base |

模块加载靠 `window.*` 全局挂载 + HTML 里裸 `<script src>` 硬编码顺序；缓存破坏靠手动 `?v=YYYYMMDD`。

### 1.2 三个核心问题

**P1 巨型单文件 setup，无内部分层**
- 以上两项是 FE-R3 之前的历史基线；当前入口已拆为 Vue SFC、composable 和按领域 API 模块。

**P2 大面积逐字重复，已开始脱节**
- `copySegment`/`copyToClipboard` ×3（app.js / chat-panel.js / share.html）。
- `shareSession` ×2（app.js / chat-panel.js）。
- `enrichMessage`+`copySegment` ×2 逐字节复制（conversations.js / refinery.js）。
- `fmtTime` ×3 且格式互斥（conversations 调 `formatBeijingDateTime`、workflows/refinery 用字符串 `replace` 裁剪）。
- 消息映射 ×4（app.js `viewHistory`、chat-panel `reloadMessages`/`applyResume`、share.html `mapMessages`）。
- markdown 渲染 CSS ×3（chat-panel.css / app.css 两处）+ admin.css 里 pre/code 样式 ×4。
- 8 个 admin HTML 的 vendor `<script>` 块纯手工复制，`?v=` 串已不一致：`admin.css` 三个版本号（`2026061101`/`2026061501`/`2026072404`）、`formatters.js` 有无 `v=` 不一。

**P3 无模块契约，隐式耦合**
- ~65 处 `fetch` 散落各文件硬编码字符串，99 个后端路径无集中定义。
- `window.*` 是唯一共享机制。`share.html` 加载了 `formatters.js` 却又内联重写其大部分函数，lib 改了 share 不跟随。
- `chat-panel.css` 的类（`.text-segment`/`.tool-block`/`.recall-card`）被 index 历史抽屉 / share.html / admin 共用，非组件私有。
- 死信号：`chat-panel.js:218` `emits: ['title-changed']` 从未触发。

### 1.3 系统性风险

- **安全债**：Vue 运行时模板编译迫使 `SecurityHeadersFilter` 开 CSP `script-src 'unsafe-eval'`。
- **无 CI 兜底**：`.github/` 不存在，~180 个后端测试 + Vitest + Playwright 只能本地手动跑，重构期间回归全靠人工。
- **依赖无锁**：5 个手动下载的 vendor 文件（vue 3.5.34 / element-plus 2.14.0 / icons 2.3.2 / marked 12.0.0，共 ~2.2MB）无 package.json / lockfile / CVE 审计；仅 dompurify 走 Maven webjars。

## 2. 目标与非目标

### 2.1 目标

1. 补 CI 自动化测试门禁，让后续每步重构有回归兜底。
2. 消除跨文件逐字重复，收敛到 `lib/` 共享模块。
3. 引入 Vite 构建链（多入口 MPA），移除 CSP `unsafe-eval`，vendor 走 npm 锁版本。
4. 分功能渐进迁移 TS：每个功能先建测试围栏、再拆组件 + 迁 TS。
5. 收紧前端与后端 API 契约（集中 endpoint 定义 + 类型）。

### 2.2 非目标

1. **不改后端 API 契约**：所有 `fetch` URL 路径与响应 JSON 结构不变。
2. **不 SPA 化**：保留 MPA（8 admin + index/share/login/git-settings），`AdminAuthFilter`/`ContextPrefix`/`RootRedirectValve` 不动。
3. ~~**不破坏 `/qa` 共享域名部署**~~ —— **已作废**：`/qa` 挂载前缀部署已废弃（`docs/public-deployment.md` §2：应用挂在域名根路径）。FE-R2c-4 已把前后端前缀机制整体删除，见 §7.5。
4. **不改聊天流式时序契约**：ChatRun Submit + GET SSE 的恢复语义不变（见 `docs/resumable-chat-stream-design.md`）。
5. **不一次性全量 TS**：渐进迁移，老文件按"建围栏 -> 改造"节奏触碰时迁。

## 3. 决策

### 决策 A：引入 Vite + 渐进 TS（已定）

**判定依据**（基于 §1 事实，非空谈）：

| 维度 | 现状代价 | 引入收益 |
|---|---|---|
| 安全 | CSP `unsafe-eval` 被迫开 | SFC 预编译后可关，收紧 XSS 防线 |
| 依赖 | 5 个手动 vendor 无 lockfile，`?v=` 已脱节 | package.json + lockfile，可审计可一键升级 |
| 模块 | `window.*` 全局挂载，~65 处 fetch 散落 | ES module + import，天然支持组件/composable 拆分 |
| 重构安全 | 5000 行无类型，~99 后端路径无契约 | TS 类型 + 集中 api client 拦回归 |
| 缓存破坏 | 手动 `?v=YYYYMMDD`，已不一致 | content-hash 文件名自动 |

**成本**：`base.js` 前缀推导机制要重写（构建时 `base` 替代运行时 `currentScript`）、11 个 MPA 入口要配、内联 template 逐页迁 SFC、2 个 Playwright config 适配。

**结论**：无构建的 workaround 已在制造比它解决的问题更多的问题（P2 脱节、P3 隐式耦合、安全债、依赖无锁），引入。迁移策略用"分功能建测试围栏 -> 改造 -> 迁 TS"渐进推进，避免一次性大爆炸。

### 决策 B：保留 MPA（已定）

不 SPA 化。admin / 主应用 / share 是不同用户场景，SPA 化要重写路由 + 鉴权，收益不抵成本。Vite 多入口模式支持 MPA。

### 决策 C：TS 渐进迁移（已定）

新拆出的模块用 TS，老文件在"建围栏 -> 改造"时迁。Vite + TS 支持混用，不一步到位。

## 4. 阶段规划

```
FE-R0  补 CI 门禁 ──────────────  现有测试自动化，不碰前端代码
FE-R1  消除重复 ───────────────  window.* 模式下收敛到 lib/，纯收益
FE-R2  Vite 骨架 ──────────────  构建链切换，代码仍 JS，只换加载/打包方式
FE-R3  分功能渐进迁移 ──────────  每个功能：建围栏 -> 拆组件 -> 迁 TS
FE-R4  契约层 + 类型收尾 ──────  集中 api client + 后端路径常量 + 类型定义
```

**核心方法论（FE-R3 适用）**：每个功能模块改造前，先建该模块的**测试围栏**（Playwright E2E 为主、Vitest 纯函数为辅），改造在围栏保护下进行。改造 = 拆子组件 / composable + 迁 TS。围栏测试全绿才合入。

现有测试覆盖（FE-R3 时是"扩充 + 补缺"，非从零建）：
- Playwright spec：chat / fs / worktree / workflows / admin-auth / admin-conversations / admin-dashboard / admin-recall / admin-refinery / mobile-smoke 等
- Vitest 10 spec：覆盖 `lib/formatters` / admin-utils

## 5. 阶段 0 详细设计：补 CI（FE-R0）

### 5.1 目标

把 `mvn test` + `cd tests && npm test`（Vitest）+ `npm run e2e`（Playwright）跑进 GitHub Actions，每步重构有回归兜底。

### 5.2 步骤

1. **新建 `.github/workflows/frontend-ci.yml`**，触发：`push` 到 `master` + `pull_request`。
2. **后端测试 job**（`mvn test`）：JDK 21 + Maven 缓存。按现有分组跑（`spring-flow` / `process-integration` / `git-integration`）。
3. **前端纯函数单测 job**（Vitest）：`cd tests && npm ci && npm test`。Node 20+。
4. **前端 E2E job**（Playwright）：`cd tests && npm ci && npm run e2e`。自动启停 Spring Boot e2e profile（端口 18099）。Playwright 缓存浏览器二进制。
5. **三个 job 并行**，互不依赖。E2E job 失败不阻塞后端 job。
6. **不设 required check 强制门禁**（先观察 1-2 周跑通率，再考虑分支保护）。

### 5.3 验证

- 手动触发一次 workflow，确认三个 job 全绿。
- 确认 E2E job 在 CI 环境能启停 Spring Boot（e2e profile + TestCliStub）。
- 确认 Vitest job 不依赖 `rg`（见已知坑）。

### 5.4 已知坑

- **Vitest 在缺 `rg` 的环境会失败**：3 个 spec 依赖 `rg`，CI runner 需预装或 spec 需改造。
- **Playwright 必须在 `tests/` 目录跑**：CI 里 `working-directory: tests` 必须设置，否则 config 不生效报假错（CLAUDE.md 已记录）。
- **E2E 串行**：1 worker，CI 单 job 跑全部 E2E 耗时 10-15 分钟，可接受。

## 6. 阶段 1 详细设计：消除重复（FE-R1）

在现有 `window.*` 模式下，把跨文件逐字重复的逻辑收敛到 `lib/`。每项独立提交，行为不变。此阶段不依赖 Vite，先做能减少后续 FE-R3 迁移时的存量。

### 6.1 抽取清单

#### 6.1.1 剪贴板 lib（`lib/clipboard.js`）

| 项 | 内容 |
|---|---|
| 现状 | `copySegment` ×3（`app.js:338-357`、`chat-panel.js:308-327`、`share.html:186-205`）+ `copyToClipboard` ×2（`app.js:600-620`、`chat-panel.js:329-341`），均走 `navigator.clipboard.writeText` + `textarea` fallback |
| 目标 | `window.AgentClipboard.copyText(text)` + `copySegment(parts)` |
| 消费者 | app.js、chat-panel.js、share.html |
| 迁移 | 新建 `lib/clipboard.js`（UMD-lite，同 formatters 模式）；各处替换；删除原内联实现 |
| 风险 | `share.html` 有"刻意自包含不引 lib"注释；需确认 share 是否接受。若坚持自包含，保留内联但注释指向 `AgentClipboard` 为单一真相源 |

#### 6.1.2 分享 lib（`lib/share-session.js`）

| 项 | 内容 |
|---|---|
| 现状 | `shareSession` ×2（`app.js:622-646`、`chat-panel.js:594-611`），app 版多一个 history-drawer 入参兜底 |
| 目标 | `window.AgentShare.shareSession({sessionId, onShared?})` |
| 消费者 | app.js、chat-panel.js |
| 迁移 | 新建 `lib/share-session.js`；两处替换；history-drawer 回调走 `onShared` 钩子 |
| 风险 | 低，两版逻辑近乎一致 |

#### 6.1.3 时间格式化收敛

| 项 | 内容 |
|---|---|
| 现状 | `fmtTime` ×3 格式互斥：`conversations.js:119`（调 `formatBeijingDateTime`）、`workflows.js:212`+`refinery.js:168`（字符串 `.replace` 裁剪）；`pct` 在 `dashboard.js:37` 内联重写 `recall-utils.js:44` 已有实现 |
| 目标 | 全部收敛到 `AgentFormatters.formatTime` / `formatBeijingDateTime`；`pct` 收敛到 `AgentFormatters` 或 `recall-utils` |
| 消费者 | conversations/workflows/refinery/dashboard |
| 迁移 | 先确认 `AgentFormatters.formatTime` 是"正确"格式；逐页替换；删除各页本地 `fmtTime` |
| 风险 | 三份格式不一致，**逐页视觉确认**：conversations/workflows/refinery 时间列。若某页确实需要不同格式，在 `AgentFormatters` 增 `formatTimeShort`/`formatTimeLong` 变体 |

#### 6.1.4 消息视图 lib（`lib/message-view.js`）

| 项 | 内容 |
|---|---|
| 现状 | `enrichMessage`+`copySegment` ×2 逐字节复制（`conversations.js:61-93`、`refinery.js:105-137`）；`ROLE_LABELS`/`roleLabel` ×2（`conversations.js:128`、`refinery.js:175`）；消息映射 ×4（`app.js:viewHistory` 571-584、`chat-panel:reloadMessages` 660-670、`chat-panel:applyResume` 1016-1024、`share.html:mapMessages` 306-319） |
| 目标 | `window.AgentMessageView.enrich(raw)` + `ROLE_LABELS` 常量 + `mapMessages(raws)` |
| 消费者 | conversations/refinery/app/chat-panel/share |
| 迁移 | 新建 `lib/message-view.js`；**先 diff 四版 `mapMessages` 实现**确认字段差异（`_expanded` 命名等）是 bug 还是刻意；逐处替换 |
| 风险 | 四处映射可能有隐式字段差异。若刻意，用参数化 `mapMessages(raws, {expandNested})` |

#### 6.1.5 fetchJson 统一 + loading/error helper

| 项 | 内容 |
|---|---|
| 现状 | `fetchJson` ×2 签名不同（`recall.js:52` 只收 url、`settings.js:23` 收 url+options）；每页重复 `loading.value=true; try{...}catch(e){ElMessage.error(...)}finally{loading.value=false}` 样板 |
| 目标 | `lib/admin-fetch.js`：`fetchJson(url, options)` 统一签名 + `withLoading(loadingRef, fn)` |
| 消费者 | 全部 admin pages |
| 迁移 | 新建 `lib/admin-fetch.js`；逐页替换本地 `fetchJson`；loading 样板改 `withLoading` |
| 风险 | `recall.js` 版只收 url，迁移时确认其调用点无 options 需求 |

#### 6.1.6 markdown CSS 收敛

| 项 | 内容 |
|---|---|
| 现状 | markdown 渲染样式 ×3（`chat-panel.css:196-223`、`app.css:229-233`、`app.css:236-264`）；admin.css 里 pre/code 样式 ×4 |
| 目标 | 抽 `css/markdown.css`（统一的 `.md-body p/code/pre/...` 规则）；各处复用类名 |
| 消费者 | chat-panel / app 历史抽屉 / share / admin 多页 |
| 迁移 | 新建 `css/markdown.css`；各 HTML 引入；逐处替换选择器为 `.md-body`；删重复规则 |
| 风险 | CSS 改动影响面广，**逐页视觉验证**。优先级最低，放 FE-R1 末尾 |

### 6.2 立即可做的低风险动作（无需任何决策，可独立提交）

1. **删死信号**：`chat-panel.js:218` `emits: ['title-changed']` 从未触发，删除。
2. **share.html 去重**：当前加载了 `formatters.js` 却内联重写其大部分函数。二选一：去掉 `<script src>` 或真复用。推荐复用（与 6.1.1/6.1.4 合并做）。
3. **对齐 `?v=` 版本串**：`admin.css` 三个版本号统一、`formatters.js` 有无 `v=` 对齐。
4. **`pct`/`fmtTime` 收敛**：属于 6.1.3，可独立先做。

### 6.3 验证

- 每项抽取独立提交，提交前跑：
  - `cd tests && npm test`（Vitest 纯函数）
  - `cd tests && npx playwright test` 相关 spec（chat / admin-conversations / admin-recall）
- 行为不变验证：抽取前后对同一输入产出相同输出（Vitest 覆盖 `AgentFormatters`，新 lib 补对应单测）。
- 视觉验证：6.1.3 / 6.1.6 逐页人工确认渲染无变化。

## 7. 阶段 2 详细设计：Vite 骨架（FE-R2）

**目标**：切换构建链，代码仍是 JS（`.js`），只是把"CDN + window.* + 手动 script"换成"Vite 多入口 MPA + ES module + npm vendor"。此阶段**不拆组件、不迁 TS**，行为不变。

### 7.1 步骤

1. **建前端 package.json**：在仓库根（或 `frontend/` 子目录，见 7.4）新建，依赖 `vue@3.5.x` / `element-plus@2.14.x` / `@element-plus/icons-vue@2.3.x` / `marked@12.x` / `dompurify@3.2.x`（替代 webjars）+ devDeps `vite` / `@vitejs/plugin-vue` / `typescript`（暂不用，FE-R3 启用）。
2. **Vite 多入口配置**（`vite.config.ts`）：11 个入口（7 admin + index/login/share/git-settings），`build.rollupOptions.input` 逐个指定 HTML。`build.outDir` 指向 `src/main/resources/static-dist/`（见 7.4 部署）。
3. ~~**前缀机制迁移**~~ —— **已作废**：`/qa` 部署废弃，前缀机制整体删除（`base.js` 连同 `deriveBase`/`withBase`/fetch 与 EventSource 包裹一并移除），`vite.config.js` 用 `base: '/'`。详见 §7.5。
4. **vendor 迁移**：`static/vendor/` 删除，改 `import Vue from 'vue'` 等。Element Plus 按需引入减体积。

   > **实施修正（已完成）**：`unplugin-vue-components` 在本项目**用不了**——它靠构建期扫 `.vue`/`.jsx` 模板 AST 推断组件，而本项目 0 个 `.vue`，49 种 `el-*` 全写在 HTML 的 in-DOM 模板里（`index.html` 131 处）由浏览器运行时编译，构建期看不见这些标签。
   > 改为**手工显式注册**：新增 `js/element-plus-setup.js`，枚举 49 个组件 + `ElLoading` 指令（`v-loading` 19 处，全量 `use` 时随包注册，按需注册必须显式补），5 个 entry 统一调 `setupElementPlus(app)` 替代 `app.use(ElementPlus)`。
   > **图标仍全量注册**：`index.html:396` 有 `<component :is="t.enabled ? 'video-pause' : 'caret-right'" />` 按字符串名动态解析，构建期无法枚举。
   > 收益：主 chunk 1225 KB → 868 KB（gzip 395 → 280 KB）。CSS 仍全量（349 KB），同受静态分析限制。
   > 双重守卫：`tests/unit/element-plus-registration.spec.ts` 静态断言「模板 `el-*` ⊆ 注册清单」且双向无冗余；`tests/e2e/component-resolution.spec.ts` 在真实浏览器逐页验证 11 个页面零未解析组件。
   > 待 FE-R3 迁 SFC 后可换回 unplugin 自动推断。
5. **JS 改 ES module**：所有 `window.*` 挂载改为 `export`，HTML `<script src>` 改 `<script type="module">`。加载顺序由 import 依赖图决定，不再手工排。
6. **CSP 收紧**：SFC 暂未用（代码仍 JS + HTML 内联 template），但 Vite 打包后可用 `vite-plugin-vue` 预编译。`unsafe-eval` 移除留到 FE-R3 SFC 迁移后（此阶先验证构建链通）。

### 7.2 部署调整

- Vite 产物输出到 `src/main/resources/static-dist/`（或直接覆盖 `static/`，取决于是否保留源码与产物分离）。
- **推荐源码与产物分离**：`src/main/frontend/` 放源码（.js/.ts/.vue/.html/.css），`vite build` 输出到 `src/main/resources/static/`（gitignore 产物）。Spring Boot 仍 serve `static/`，零后端改动。
- `mvn package` 前 CI 跑 `npm run build` 生成产物（FE-R0 的 CI job 扩展）。

### 7.3 验证

- 1 个 Playwright config 全绿：默认（根路径）。（原 `playwright.qa-prefix.config.ts` 随 `/qa` 废弃已删除。）
- `mvn test` 全绿（后端不受影响，确认静态资源路径未变）。
- 产物体积对比：应显著小于现状 ~2.2MB（tree-shaking + 按需引入 element-plus）。
- CSP 头确认：此阶 `unsafe-eval` 仍在（SFC 未迁），FE-R3 末尾移除。

**实际验收结果（FE-R2c 完成时）**：

| 项 | 结果 |
|---|---|
| 默认 config | 37 passed / 20 failed；20 个失败**与改造前逐条同名**（已 `git stash` 对照 baseline 确认） |
| `mvn test` | 全绿，1331 tests |
| Vitest | 125 passed / 3 failed（3 个失败即 §5.4 已记录的缺 `rg`） |
| 体积 | 旧 vendor 合计 2167 KB → 登录页实际加载 868 KB JS + 349 KB CSS（gzip 327 KB） |

**关于那 20 个既有失败**（已处理，见下）：其中 14 个的根因是 spec 在测**已被删除的功能** —— diagnose / tickets / backfill 三个页面（`tests/e2e/_admin.ts` 的 `MENU_SLUG` 仍指向它们，导航即 404）以及 `/api/diagnose`、`/api/diagnose-history`、`/api/issue-log-backfill` 三组接口（`interfaces` 层已无对应 controller）。这 7 个 spec 文件的全部用例无一通过，已随 FE-R2c-5 删除：

| 删除 | 用例数 |
|---|---|
| `diagnose.spec.ts` / `tickets.spec.ts` / `backfill.spec.ts` | 4 / 1 / 1 |
| `diagnose-api.spec.ts` / `diagnose-stream-api.spec.ts` / `backfill-api.spec.ts` / `issue-log.spec.ts` | 2 / 2 / 3 / 1 |

同时清掉三处随功能删除而失效的配置：`_admin.ts` 的三个死 slug、`AdminProperties` 与 `application{,-e2e}.yml` 里 `/api/issue-log-backfill` 与 `/api/diagnose-history` 两条指向不存在接口的 `protected-prefixes`（YAML 会整体覆盖 Java 默认值，两边都要改）。

**清理后**：默认 config **37 passed / 3 failed**（`mvn test` 1331 全绿）。剩余 3 个为真实的既有问题，与前端改造无关：`admin-auth`、`dashboard`、`git-settings` 各 1。

### 7.4 决策点

- **前端源码位置**：`src/main/frontend/`（推荐，源码与产物分离）vs 保留 `src/main/resources/static/`（源码即产物，Vite dev server 代理）。FE-R2 启动前定。
- **dompurify 是否从 webjars 迁 npm**：推荐迁，统一依赖管理。

### 7.5 `/qa` 挂载前缀机制整体移除（FE-R2c-4）

`/qa` 子路径部署已废弃，应用固定挂在域名根路径（`docs/public-deployment.md` §2）。本计划原先把「不破坏 `/qa` 部署」列为非目标之一并要求 3 个 Playwright config 全绿，该前提整体作废。前后端为它存在的机制已全部删除：

**后端**

| 删除 | 说明 |
|---|---|
| `ContextPrefix` (81 行) | 前缀派生与剥离 |
| `RootRedirectValve` (55 行) + `WebConfig.rootRedirectCustomizer` | 裸根 → `/qa/` 的 Engine 级 Valve；根部署下本就空转 |
| `AdminEntryController` | 它存在的唯一理由是运行时给 302 Location 补 contextPath；退回 `WebConfig.addViewControllers` 静态重定向 |
| `application-qa-prefix.yml` | `context-path: /qa` 的测试 profile |
| `ContextPrefixTest` / `RootRedirectValveTest` / `AdminEntryControllerTest` | |

**保留的一项安全性质**：`ContextPrefix.strip()` 除剥前缀外还 collapse 连续斜杠。这条与前缀无关且必须留 —— 不归一时 `//login.html` 会失配 `PublicPaths` 精确匹配，登录页被当未登录页面、把自身套进 `redirect` 形成无限重定向。已收敘为 `RequestPath.normalized()`（只做斜杠归一），`SessionAuthFilter` / `AdminAuthFilter` / `LoginUrlBuilder` 改调它，用例迁到 `RequestPathTest`。

**前端**

- `js/base.js` 整个删除：`deriveBase` / `makeWithBase` / `APP_BASE` / `window.fetch` 与 `EventSource` 的补前缀包裹、`window.__APP_BASE__` 与 `window.withBase` 契约。13 个 HTML 里的 `<script src="/js/base.js">` 一并移除。
- 各消费者 `withBase(x)` → `x`（`app.js` / `shell.js` / `share.js` / `login.js` / `formatters.js` / `share-session.js`），`index.html` 模板里的 `:action="withBase(...)"` 同步展开（`withBase` 已不在 setup 返回值里，不改会渲染报错）。
- `sanitizeRedirect` 与前缀无关，是独立的 open-redirect 防御（挡 `//host` 与 `/api/`），迁到 `js/lib/redirect.js` 并去掉 `base` 参数；用例迁到 `tests/unit/redirect.spec.ts`。
- `vite.config.js`：`base: './'` → `base: '/'`。相对 base 本是为子路径部署，根部署下用根绝对路径，`/admin/x.html` 与 `/index.html` 引用同一份 `/assets/` 产物，不受页面深度影响。
- `playwright.qa-prefix.config.ts` / `qa-prefix.spec.ts` / `qa-prefix-assets.spec.ts` 删除，`playwright.config.ts` 的 `testIgnore` 随之移除。

**验收**：`mvn test` 1337 全绿；Vitest 125 passed / 3 failed（缺 `rg`）；默认 Playwright config 37 passed / 20 failed，失败集与清理前逐条一致。（那 20 个随后在 FE-R2c-5 收敘到 6 个，见 §7.3。）

## 8. 阶段 3 详细设计：分功能渐进迁移（FE-R3）

**核心方法**：每个功能模块按"建围栏 -> 改造 -> 验证"三步推进，独立提交、独立可回滚。

### 8.1 迁移单元三步法

| 步骤 | 内容 | 产出 |
|---|---|---|
| ① 建围栏 | 针对该功能补 Playwright E2E（主）+ Vitest 纯函数（辅），覆盖关键路径与边界。现有 spec 已覆盖的扩充、未覆盖的补建 | 围栏测试 commit |
| ② 改造 | 在围栏保护下：拆子组件 / composable（.vue SFC）+ 迁 TS（.ts）+ 移除 window.* 挂载改 import | 改造 commit |
| ③ 验证 | 围栏测试全绿 + 人工视觉确认 + `mvn test` 全绿 | 合入 |

### 8.2 迁移顺序（按风险递增、先独立后耦合）

| 子里程碑 | 功能 | 围栏 | 改造目标 |
|---|---|---|---|
| FE-R3.1 | `lib/` 纯函数（formatters/clipboard/share/message-view/admin-fetch） | Vitest（已有 10 spec，扩充） | 最易，迁 TS + 改 export，无 Vue 组件 |
| FE-R3.2 | `app.js` auth + file-system | Playwright auth/fs spec | 拆 `useAuth`/`useFileSystem` composable |
| FE-R3.3 | `app.js` worktree | Playwright worktree spec | 拆 `useWorktree` composable |
| FE-R3.4 | `app.js` history + scheduled-task | Playwright chat/history spec | 拆 `useHistory`/`useScheduledTask` |
| FE-R3.5 | `chat-panel` image-upload + feedback + slash-command | Playwright chat spec（扩充上传/反馈/命令用例） | 已拆为 `useImageUpload`/`useFeedback`/`useSlashCommandInteraction` |
| FE-R3.6 | `chat-panel` resumable-run（最复杂，212 行） | Playwright chat spec（扩充断连/刷新恢复用例，参考 `resumable-chat-stream-design.md` 测试矩阵） | 拆 `useResumableRun` composable |
| FE-R3.7 | `chat-panel` message-rendering + 子组件拆分 | Playwright chat spec + share.html spec | 已由 `ConversationMessage`/`ConversationTimeline`/`ToolBlock`/`CommandPopup`/`InputArea` 承载 |
| FE-R3.8 | admin 小 pages（conversations/refinery/recall/workflows/settings/users/dashboard） | 对应 Playwright admin-* spec | 逐页迁 SFC + TS，复用 FE-R1 lib |
| FE-R3.10 | `share.html` + `login.html` + `git-settings.html` | Playwright share spec | 迁 SFC，share.html 复用 FE-R3.7 消息组件 |

### 8.3 拆分蓝图

#### app.js（FE-R3.2-3.4）

按业务域拆，app.js 只剩编排 + 生命周期：

| 模块 | 来源 | 形态 |
|---|---|---|
| `useAuth` | 401 拦截 + init auth + doLogout | composable (.ts) |
| `useFileSystem` | loadList/handleFileCommand 预览/下载/删除/上传（216-326） | composable |
| `useWorktree` | switch/update/clear/remove + localStorage（368-500） | composable |
| `useHistory` | 分页加载/查看/恢复/删除/分享 + 活跃 run 探测（502-646） | composable |
| `useScheduledTask` | CRUD + toggle/run + cron 预设（648-733） | composable |
| app.js 剩余 | init() + onMounted/watch + 编排 | <200 行 |

#### chat-panel.js（FE-R3.5-3.7）

| 子组件 SFC | 来源 template 片段 |
|---|---|
| `ConversationMessage.vue` | 消息行（user/agent/recall/tool） |
| `ToolBlock.vue` | 工具调用块 |
| `RecallCard.vue` | RAG 召回卡片 |
| `CommandPopup.vue` | 斜杠命令弹窗（118-125） |
| `InputArea.vue` | 输入框 + 发送 + 附件 |
| `PendingImageList.vue` | 待上传图片缩略图 |

| composable | 来源 setup 片段 |
|---|---|
| `useResumableRun` | 可恢复 SSE/ChatRun 编排（684-895，212 行） |
| `useImageUpload` | 图片/附件上传（418-532） |
| `useFeedback` | 分析评价（534-592） |
| `useSlashCommandInteraction` | 命令弹窗交互（已从 Chat 与 Workbench 共用） |

### 8.4 CSP `unsafe-eval` 移除

FE-R3.7（chat-panel 消息渲染 SFC 化）后，所有运行时模板编译消除。在 `SecurityHeadersFilter` 移除 `script-src 'unsafe-eval'`，Playwright 验证所有页面渲染正常（CSP 违规会 console error，E2E 捕获）。

### 8.5 验证

- 每个子里程碑：对应围栏 Playwright spec 全绿 + `mvn test` + Vitest。
- FE-R3.6（resumable-run）额外：参考 `docs/resumable-chat-stream-design.md` §19 测试矩阵，覆盖断连/刷新/多订阅者/terminal 收口。
- FE-R3.10 后：1 个 Playwright config 全绿，CSP 头确认无 `unsafe-eval`。

## 9. 阶段 4 详细设计：契约层 + 类型收尾（FE-R4）

### 9.1 集中 api client

- 新建 `src/main/frontend/api/` 目录，按域分模块（chat / worktree / history / scheduled-task / admin-*）。
- 每个 endpoint 一个类型化函数，~65 处散落 fetch 全部收敛。
- 后端路径常量化，路径变更改一处。

### 9.2 前后端类型共享（可选）

- 评估是否从后端 DTO 生成 TS 类型（如 Spring REST Docs / openapi）。若成本高，FE-R4 先手写类型，后续按需引入生成。

### 9.3 ESLint + Prettier

- FE-R4 当前使用 ESLint + Prettier，类型检查由 `tsc --noEmit` 负责；由于 TypeScript 7 尚未被 typescript-eslint 支持，未启用 `@vue/eslint-config-typescript`。
- CI job 扩展 lint 检查。

## 10. 顺序与里程碑

| 里程碑 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| FE-R0 | 补 CI（GitHub Actions 4 job） | 无 | ✅ 完成 |
| FE-R1.1 | 立即可做（死信号/share.html/?v=/pct 收敛） | FE-R0 | ✅ 完成（FE-R2c 期间） |
| FE-R1.2 | 剪贴板 + 分享 + 消息视图 lib | FE-R1.1 | ✅ 完成（FE-R2c 期间） |
| FE-R1.3 | 时间格式化 + fetchJson 收敛 | FE-R1.2 | ✅ 完成（FE-R2c 期间） |
| FE-R1.4 | markdown CSS 收敛 | FE-R1.3 | ⏸ 低优先级,留后续 |
| FE-R2 | Vite 骨架（多入口 MPA + 前缀迁移 + vendor npm + 源码产物分离） | FE-R1 | ✅ 完成（FE-R2c） |
| FE-R3.1 | lib/ 纯函数迁 TS | FE-R2 | ✅ 完成 |
| FE-R3.2-3.4 | app.js 拆 composable（auth/fs/worktree/history/scheduled-task） | FE-R3.1 | ✅ 完成 |
| FE-R3.5-3.7 | chat-panel 拆 composable + 子组件 SFC | FE-R3.4 | ✅ 完成 |
| FE-R3.8 | admin 小 pages 迁 SFC + TS | FE-R3.7 | ✅ 完成（SFC 化,TS 留后续） |
| FE-R3.10 | share/login/git-settings 迁 SFC + 移除 CSP unsafe-eval | FE-R3.8 | ✅ 完成 |
| FE-R4 | 集中 api client + 类型契约 + ESLint | FE-R3.10 | ✅ ESLint/Prettier/typecheck 完成；未接线的旧 API 包装已于 2026-08-06 删除，领域 API 迁移仍是后续债务 |

## 11. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 无 CI 兜底下重构盲飞 | FE-R0 前置，不补 CI 不动前端代码 | - |
| 重复消除引入行为差异 | 每项独立提交 + Vitest + Playwright 验证 | 单 commit revert |
| 时间格式化改变前端显示 | 6.1.3 逐页视觉确认，需要变体则参数化 | revert 单页 |
| `share.html` 自包含意愿 | 6.1.1/6.1.4 先确认 share 是否接受引 lib | 保留 share 内联 + 注释 |
| CSS 收敛影响渲染 | 6.1.6 放 FE-R1 末尾，逐页视觉验证 | revert CSS commit |
| Vite 多入口配置复杂 | 12 入口逐个配，先 index 跑通再扩 admin | 退回 CDN 模式（git revert FE-R2） |
| 前端源码位置迁移丢失文件 | FE-R2 用 `git mv` 保留历史 | git revert |
| resumable-run 改造破坏恢复语义 | FE-R3.6 参照 `resumable-chat-stream-design.md` 测试矩阵扩充围栏 | revert composable 拆分 |
| CSP 收紧后页面渲染异常 | FE-R3.10 移除 unsafe-eval 后全量 Playwright + console error 捕获 | 恢复 unsafe-eval |
| E2E 在 CI 环境跑不稳 | FE-R0 先观察 1-2 周跑通率 | E2E job 设为 non-blocking |

## 12. 明确不做

- **不改后端 API 契约**：所有 `fetch` URL 与响应 JSON 结构不变。
- **不 SPA 化**：保留 MPA，`AdminAuthFilter`/`ContextPrefix`/`RootRedirectValve` 不动。
- ~~**不破坏 `/qa` 部署**~~：已作废，`/qa` 废弃后前缀机制整体删除（§7.5）。
- **不改聊天流式时序契约**：ChatRun Submit + GET SSE 恢复语义不变。
- **不引入 sqlite-vec / 不拆 Maven module**（沿用 `ddd-refactoring-plan.md` 约束）。
- **不一次性全量 TS**：FE-R3 按"建围栏 -> 改造"节奏渐进。
- **不在 FE-R0/R1 阶段引入构建工具**：FE-R2 才切 Vite，FE-R0/R1 在现有 window.* 模式下做。
