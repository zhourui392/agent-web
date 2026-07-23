# 公网 HTTPS 部署

本服务能启动本机 CLI、浏览白名单目录并操作工作区，公网入口的权限等同于服务进程在宿主机上的权限。推荐拓扑如下：

```text
Internet ── HTTPS :443 ──> Caddy ── HTTP 127.0.0.1:18092 ──> agent-web
                             │
                             └─ 自动 HTTPS、可信转发头、SSE 反代
```

关键边界：公网只开放 Caddy 的 443；Spring Boot 默认只监听 `127.0.0.1:18092`，外部不能直连上游端口。应用使用 Spring 的 `framework` 转发头策略，由同机 Caddy 提供可信的 HTTPS 协议和客户端来源信息。

## 1. 准备管理员密码和目录边界

仓库中的初始账户名是 `admin`，公开种子密码是 `Aa135246`，只用于本机初始化，不能用于公网。首次公网启动前生成一个独立强密码，并通过 Secret 管理器或权限为 `600` 的进程环境文件注入：

```bash
openssl rand -base64 32

export AGENT_PUBLIC_ACCESS_ENABLED=true
export AGENT_BOOTSTRAP_ADMIN_PASSWORD='<上一步生成的密码>'
export AGENT_WORKSPACE_ROOT='/srv/agent-workspaces'
export SERVER_ADDRESS=127.0.0.1
export SERVER_FORWARD_HEADERS_STRATEGY=framework
```

新密码必须为 12～256 个字符，且不能继续使用公开种子密码。若数据库仍是种子哈希但未配置新密码，应用会在 Web Server 开始接收请求前失败退出。换密与注销该账户已有登录会话在同一事务内完成。启动成功后，数据库只保存 BCrypt cost 12 哈希；后续启动检测到密码已经修改，不会再次覆盖，可从进程环境中移除 `AGENT_BOOTSTRAP_ADMIN_PASSWORD`。

同时收紧以下路径，不要把 `/`、用户主目录或凭据目录整体开放：

- `agent.fs.roots` / `AGENT_WORKSPACE_ROOT`：浏览、下载、删除和 Git worktree 操作共用的工作目录边界。
- `agent.fs.upload-roots`：仅上传通道允许写入的目录。
- 服务进程的 OS 用户权限：应使用非 root 专用用户，只赋予实际需要的文件和 CLI 凭据权限。

如果启用了用户 Git 凭据或 Refinery，还必须分别设置强随机加密密钥或 embedding key；凭据不能写进仓库配置。

## 2. Caddy 反向代理

当前服务器的 `/etc/caddy/Caddyfile` 已加入以下站点。应用挂载在域名根路径，不再使用 `/qa`：

```caddyfile
agent.mokatu.shop {
    reverse_proxy 127.0.0.1:18092 {
        flush_interval -1
        lb_try_duration 15s
        lb_try_interval 250ms
    }
}
```

Caddy 自动完成 HTTP → HTTPS 跳转、证书申请和续期，并为上游设置 `X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host`。`flush_interval -1` 让聊天和诊断 SSE 事件立即向浏览器刷新；`lb_try_*` 会在应用滚动重启的短窗口内重试可安全重试的 GET 请求，避免直接返回瞬时 502。

配置变更后使用以下命令校验和无中断加载：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl reload caddy
```

若以后在 Caddy 前再增加 CDN 或其他代理，需要显式配置 Caddy 的 `trusted_proxies`，只信任确定的上游网段；否则不要根据客户端自行提交的转发头做鉴权或限流。

## 3. 启动与首次登录

按项目既有方式启动 JAR 或 Maven 进程。Linux 直接运行 Maven 时确保使用 JDK 21：

```bash
JAVA_HOME=/usr/local/jdk-21 PATH=/usr/local/jdk-21/bin:$PATH \
mvn spring-boot:run
```

访问 `https://agent.mokatu.shop/`，使用用户名 `admin` 和 `AGENT_BOOTSTRAP_ADMIN_PASSWORD` 的值登录。公网默认 Cookie 名是 `__Host-agent_session`，并强制 `Secure`、`HttpOnly`、`SameSite=Strict`。不要通过 `http://127.0.0.1:18092/` 登录：浏览器不会在明文 HTTP 中回传 Secure Cookie，该端口也只作为 Caddy 上游使用。

## 4. 上线检查

上线前至少确认：

- 外网只能连接 443，无法连接 18092；80 只做 HTTPS 跳转。
- `https://agent.mokatu.shop/` 返回安全头；HTTPS 响应包含 `Strict-Transport-Security`。
- 登录响应的 Cookie 为 `__Host-agent_session`，包含 `Secure; HttpOnly; Path=/; SameSite=Strict`。
- 使用公开种子密码无法登录；数据库 `user_account.password_hash` 是 BCrypt 哈希而非明文。
- Caddy 设置可信转发头，应用日志 / 限流看到的来源 IP 与实际客户端一致。
- `agent.fs.roots`、上传目录、worktree 根和服务 OS 权限均为最小范围。
- `CODEX_SANDBOX_BYPASS=false`，CLI 超时和输出上限未被关闭。
- Git 凭据加密密钥和 embedding key 均为独立强密钥且不在仓库中。
- SQLite 文件及父目录仅服务用户可读写，并纳入备份；备份同样按敏感数据保护。

本机纯 HTTP 开发不属于公网部署，可显式设置：

```bash
SERVER_ADDRESS=127.0.0.1 \
AGENT_PUBLIC_ACCESS_ENABLED=false \
AGENT_AUTH_COOKIE_SECURE=false \
AGENT_AUTH_COOKIE_NAME=local_session \
mvn spring-boot:run
```
