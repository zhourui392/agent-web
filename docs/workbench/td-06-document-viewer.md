# TD-06 文档查看器

> 状态：Draft v0.1
> 日期：2026-08-01
> 前置：[TD-04](td-04-multi-repository-workspace.md)
> @author alex

## 1. 目标

提供受 Repository Scope 约束的只读文档树、内容读取、类型化渲染、stale 提示和 Split Pane。文档查看器
不能复用 `/api/fs/download?path=<absolute>` 作为读取入口，也不实现在线编辑、保存、Diff 或多 Tab。

## 2. 授权模型

客户端只提交：

```text
workbenchId + repositoryKey + relativePath
```

服务端处理：

```text
加载 Owner 可见的 Workbench
→ RepositoryScope.requireRepository(repositoryKey)
→ ScopedPathResolver.resolve(relativePath)
→ reject absolute / .. / control char / symlink
→ candidate.toRealPath()
→ require candidate startsWith repositoryRoot
→ 按类型和大小读取
```

全局 Workspace 白名单是外层门禁，Repository Scope 是更窄的 Workbench 门禁。两者都必须通过。

## 3. Application Query Port

纯 SELECT/文件读取使用 Application CQRS 接口：

```text
WorkbenchDocumentAppService
  listTree(actor, workbenchId, DocumentDirectoryQuery): DocumentDirectoryView
  readContent(actor, workbenchId, DocumentReference): DocumentContentView
  download(actor, workbenchId, DocumentReference): DocumentDownloadView
```

返回 DTO/View，不返回 `Path`、`File`、Resource 或半截领域聚合。Infrastructure Adapter 负责真实文件读取、
MIME、编码和 Hash；业务授权由 Application 先完成。

## 4. API

### 4.1 Tree

```http
GET /api/workbenches/{id}/documents/tree
    ?repositoryKey=service%2Fapi
    &path=src/main
    &limit=1000
```

```json
{
  "repositoryKey": "agent-web",
  "path": "src/main",
  "entries": [
    {
      "name": "java",
      "relativePath": "src/main/java",
      "kind": "DIRECTORY",
      "size": null,
      "lastModified": 0
    }
  ],
  "truncated": false
}
```

目录按 directory-first、名称稳定排序；不返回 `..` 伪节点，由前端通过相对路径计算面包屑。

### 4.2 Content

```http
GET /api/workbenches/{id}/documents/content
    ?repositoryKey=service%2Fapi
    &path=README.md
If-None-Match: "content-version"
```

```json
{
  "reference": {"repositoryKey": "agent-web", "relativePath": "README.md"},
  "kind": "MARKDOWN",
  "mediaType": "text/markdown",
  "encoding": "UTF-8",
  "size": 1024,
  "lastModified": 0,
  "contentVersion": "sha256-or-framed-metadata-hash",
  "content": "...",
  "truncated": false,
  "deleted": false
}
```

- 命中 ETag 返回 304；
- 文件删除返回 404，前端保留已加载内容并标记 deleted；
- 二进制返回 Metadata View 和受控下载入口，不把字节塞 JSON；
- 图片使用专用 scoped inline endpoint，并设置 `nosniff`、CSP 和正确 MIME。

下载使用同一组 query 参数，不把可能含 `/` 的逻辑 Repository Key 放入 Path Segment：

```http
GET /api/workbenches/{id}/documents/download
    ?repositoryKey=service%2Fapi
    &path=README.md
```

## 5. Document Kind

```text
MARKDOWN
SOURCE_CODE
STRUCTURED_TEXT
PLAIN_TEXT
LOG_OR_REPORT
IMAGE
BINARY_METADATA
UNSUPPORTED
```

映射集中在 `DocumentTypeResolver`，依据扩展名、MIME 和有限字节嗅探。Controller/前端不散落 if 链。

默认支持：

- Markdown；
- Java、Vue、JS、TS、Python、SQL；
- JSON、YAML、XML；
- txt/log/test report；
- PNG/JPEG/GIF/WebP；
- 其他文件只展示元信息和下载。

语法高亮使用成熟前端库（建议 `highlight.js` 按语言按需引入），不手写 Lexer。

## 6. 限额

| 配置 | 推荐默认 |
| --- | ---: |
| `max-directory-entries` | 1000 |
| `max-text-bytes` | 2 MiB |
| `max-log-preview-bytes` | 2 MiB |
| `max-image-bytes` | 10 MiB |
| `max-path-length` | 4096 |
| `max-recent-documents` | 20 |

超限文本返回 metadata、前后有界 preview 或明确不可预览状态；不无界读入内存。日志默认读取有界内容，不提供
任意 tail 命令。大小策略是 Infrastructure 技术规则，不承载业务判断。

## 7. 文件变化与 stale

打开文档时记录 `contentVersion`。收到 `file_changed` 事件：

```text
event.reference == currentDocument.reference
AND event.contentVersion != loadedVersion
→ mark stale
→ 不替换正文/不改变 scrollTop
```

用户点击刷新才使用 If-None-Match 拉取新版本。Run terminal 再做一次当前文档 metadata 检查，弥补 Provider
未产生结构化 FILE_CHANGED 的情况。

文件删除：

- 保留浏览器内已加载正文；
- 状态显示“源文件已删除”；
- 禁止再次下载；
- 用户切走后不保证缓存长期存在。

## 8. Agent 输出中的文件链接

可靠入口优先级：

1. Runtime/Tool 的结构化 `DocumentReference`；
2. FILE_CHANGED/TEST_REPORT 事件；
3. Agent 文本的 best-effort linkification。

文本 linkification 只生成候选 `repositoryKey + relativePath`，点击后仍走服务端完整授权。不要把任意
`/absolute/path` 直接生成 `/api/fs` 链接。无法确定仓库时展示普通代码文本，不猜第一个仓库。

## 9. 前端 Split Pane

```text
WorkbenchPage
└── SplitPane
    ├── PhaseConversation（flex: 1）
    ├── ResizeHandle
    └── DocumentPane（默认 35%）
```

状态：

```text
NORMAL(widthPercent)
COLLAPSED
MAXIMIZED
MOBILE_DRAWER
```

- 拖动宽度限制建议 25%～70%；
- 双击恢复 35%；
- 收起后对话占满；
- 最大化保留返回前宽度；
- 窄屏转全屏 Drawer；
- Resize 使用 Pointer Events 与 `requestAnimationFrame`，避免每像素触发大规模响应式更新。

`localStorage` key：

```text
agent-web:workbench-layout:<userId>:<workbenchId>:<phase>
agent-web:workbench-documents:<userId>:<workbenchId>:<phase>
```

只保存宽度、状态、Document Reference、scroll position 和最近列表；不保存文件正文、绝对路径或 Secret。

## 10. Markdown 与代码安全

- Markdown 继续使用 `marked + DOMPurify`，净化器不可用时 fail-closed 转义；
- 禁止脚本、事件属性、危险 URI、iframe/object/embed；
- 外部链接加 `rel="noopener noreferrer"`；
- 图片默认只走 scoped image endpoint；
- 代码内容使用 textContent，不拼原始 HTML；
- Response 设置 `X-Content-Type-Options: nosniff`；
- 只读下载文件名使用服务端安全编码。

## 11. 缓存与一致性

- 内容响应 `Cache-Control: private, no-cache` + ETag；
- 服务端不维护长生命周期文件正文缓存；
- 浏览器同一文档可保留当前内存内容；
- API 每次读取重新做真实路径检查，避免仓库目录被替换后的 TOCTOU；
- Repository Root fingerprint 与 Scope 不匹配时返回 `WORKSPACE_TOPOLOGY_CHANGED`，停止后续读取。

## 12. 测试

Infrastructure `@TempDir`：

- 相对路径、`..`、绝对路径、symlink 文件/目录、仓库替换；
- UTF-8/BOM/未知编码、二进制嗅探、大文件、图片 MIME；
- ETag/304、删除、mtime 相同但内容变更；
- 未选 sibling 拒绝。

Interface：

- Owner 404、参数校验、Content-Type、CSP/nosniff、大小状态；
- API 响应不含 repositoryRoot 绝对路径。

Vitest：

- Document Kind renderer、stale reducer、布局状态、localStorage 隔离；
- Markdown sanitizer fail-closed；
- 同名文件按 Repository Key 区分。

Playwright：

- 点击事件路径打开；
- 拖动/双击/收起/最大化/刷新恢复；
- 文件变化只提示不跳滚动；
- 窄屏 Drawer；
- 删除文件保留旧正文提示。

## 13. 验收标准

- Document API 不接受绝对路径；
- 未选仓库不可浏览；
- 右侧 Pane 所有布局行为符合产品验收；
- 文件变化不打断阅读；
- Markdown/代码渲染无未净化 HTML；
- Workbench 不依赖 `/api/fs` 的全局绝对路径授权。
