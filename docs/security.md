# 安全指南

本服务能够启动本机 CLI、浏览授权目录并操作工作区。公网入口的实际权限等同于服务进程在宿主机上的权限，必须同时控制应用授权、进程用户权限和网络边界。

## 已实现的安全控制

- 路径验证防止目录穿越；文件接口和 worktree 操作统一受 `agent.fs.roots` 约束。
- 数据库用户名密码登录使用 BCrypt 哈希、256-bit 随机会话以及 `HttpOnly` / `SameSite=Strict` Cookie；会话默认 7 天，登录失败按来源和用户名限流。
- 公网模式在 Web Server 接收请求前检查 `admin` 种子哈希；未提供新密码或继续使用公开种子密码时拒绝启动。公网 Cookie 默认使用 `Secure` 和 `__Host-` 前缀。
- 会话可见性默认按用户隔离；删除只允许会话创建者，删除他人会话返回 403；管理接口在普通会话认证后继续校验 `ADMIN` 角色。
- 分享链接使用随机 Token，只允许查看历史；公开分享不能续聊、启动 Agent、使用 owner 的 Git 身份或凭据。
- 用户路径经过真实路径白名单校验，拒绝符号链接逃逸；上传限制文件名、扩展名和大小，并禁止覆盖已有文件。
- Workbench 在 Workspace Root 白名单之上冻结 Repository Scope；文档、附件引用、Run 写根和 Operation Target 再按 Scope 授权，未选择的 sibling 仓库不可写。
- Workbench 上传附件保存在受控 Git 忽略目录，使用内容 Hash、大小/数量/TTL 上限和 `NOFOLLOW` 路径检查；附件不能通过文件路径声明伪造。
- Workbench Runtime Snapshot 冻结 Exec Policy、能力绑定和写根；类型化 Operation 默认只创建 Proposal，高影响 Executor 独立发布并要求 Owner 明确决定。
- Admin Workbench 只返回安全投影，不返回消息正文、Prompt、Secret 或原始命令；管理员运维动作写入独立审计记录。
- Markdown 使用 DOMPurify 白名单净化；响应统一发送 CSP、`nosniff`、拒绝 framing 等浏览器安全头。
- Agent 子进程使用受控环境变量集合、超时和输出上限；Claude 不使用 `--dangerously-skip-permissions`。
- 用户 SCM 凭据密码通过 `GitCredentialCipher` 加密存储，响应不回显。
- 生产环境通过 `agent.envs[].prompt` 注入只读约束。
- Knowledge Refinery 入库前根据 `agent.refinery.privacy.redact-patterns` 对结论文本脱敏，降低 API key、JWT 和用户路径段进入向量库的风险。

## 生产环境要求

- 公网入口只开放 Caddy 的 HTTPS 443；Spring Boot 保持监听 `127.0.0.1:18092`，不能覆盖为公网可达地址。
- 首次公网启动使用 Secret Store、进程环境或权限为 `600` 的 `data/secrets.properties` 临时注入 `AGENT_BOOTSTRAP_ADMIN_PASSWORD`；成功写入 BCrypt 哈希后删除明文配置。
- `data/` 目录权限设为 `700`，秘密文件权限设为 `600`；写入前使用 `git check-ignore -q data/secrets.properties` 确认不会被 Git 跟踪。
- 把 `agent.fs.roots` 限制到确实允许浏览、修改和执行 worktree 操作的最小目录范围，不授权 `/`、整个用户主目录或认证目录。
- 用户 Git 凭据使用独立的 `GIT_CRED_ENC_KEY` 加密；密钥通过受控环境或 Git 忽略配置注入，不写入受版本控制的文件。
- TLS 在反向代理终止时保持 `SERVER_FORWARD_HEADERS_STRATEGY=framework`，由可信代理设置 `X-Forwarded-Proto=https`；不能信任客户端直接提交的转发头。
- 当前 `application.yml` 的普通 Codex `sandbox-bypass` 默认值为 `true`。公网或多人环境必须显式设置 `CODEX_SANDBOX_BYPASS=false`，并验证实际 Codex 命令未绕过 sandbox。
- CLI 的 idle timeout、绝对运行上限和输出上限不能关闭。
- Vue/Vite 已使用 runtime-only 预编译构建，CSP 不包含 `unsafe-eval`；当前 `style-src` / `script-src` 仍保留 `unsafe-inline`，后续清理内联样式或脚本后应继续收紧。
- NATIVE 只读取 `AGENT_NATIVE_API_KEY` / `AGENT_NATIVE_BASE_URL`，不得回退到 Codex/OpenAI CLI 凭据；其日志根和远程工具 allowlist 必须由服务端固定。
- SQLite 文件及父目录只允许服务用户读写，并纳入按敏感数据保护的备份流程。

Caddy 样例、首次换密和上线检查见[公网 HTTPS 部署](public-deployment.md)；配置来源与秘密文件规则见[配置指南](configuration.md)。
