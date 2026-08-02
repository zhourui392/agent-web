# 安装与启动

## 环境要求

- Java 21 或更高版本。
- Maven 3.6 或更高版本，并已加入 `PATH`。
- Claude CLI / Codex CLI 中至少一个；两者可以同时配置。

## 使用服务脚本

Linux：

```bash
# 自动查找 JDK 21+，编译成功后在后台启动
./scripts/service.sh start

# 可用子命令：build、stop、restart、status、logs
./scripts/service.sh status
```

Windows PowerShell：

```powershell
# 自动查找 JDK 21+，编译成功后在后台启动
.\scripts\service.ps1 start

# 可用子命令：build、stop、restart、status、logs
.\scripts\service.ps1 status
```

执行 `build`、`start` 或 `restart` 时，脚本依次检查 `JAVA_BIN`、`JAVA_HOME`、`PATH`、Windows 系统注册表和常见 JDK 安装目录。找到完整的 JDK 21 或更高版本后，脚本为当前进程设置 `JAVA_HOME` / `PATH`，执行 `mvn clean package`，并仅在编译成功后启动 `app/agent-web.jar`。

自动探测失败时可显式指定 Java：

```bash
JAVA_BIN=/usr/local/jdk-21/bin/java ./scripts/service.sh start
```

```powershell
$env:JAVA_BIN='C:\Program Files\Java\jdk-21\bin\java.exe'
.\scripts\service.ps1 start
```

JVM 参数通过 `JAVA_OPTS` 传入；Spring Boot 参数可以追加到 `start` / `restart` 后，例如：

```bash
./scripts/service.sh start --spring.profiles.active=local
```

运行日志位于 `logs/service.log`，Windows 标准错误另写入 `logs/service-error.log`。

## 手工构建与前台启动

```bash
mvn clean package

# 二选一
mvn spring-boot:run
java -jar target/agent-web-0.1.0-SNAPSHOT.jar
```

Linux 直接运行 Maven 时，本仓库环境应显式使用 JDK 21：

```bash
JAVA_HOME=/usr/local/jdk-21 PATH=/usr/local/jdk-21/bin:$PATH \
mvn spring-boot:run
```

## 登录与首次初始化

登录页 `/login.html` 使用数据库用户名和密码认证。会话默认保留 7 天；除登录入口、静态资源和只读分享外，其他入口均要求登录。

首次初始化 SQLite 时会创建 `ADMIN` 账户 `admin`，公开种子密码为 `Aa135246`。这个密码只允许本机初始化使用，不能用于公网环境。公网模式启动时，如果数据库仍保留种子密码哈希，应用会在监听端口对外提供请求前要求 `AGENT_BOOTSTRAP_ADMIN_PASSWORD`：

```bash
read -rsp 'New admin password: ' AGENT_BOOTSTRAP_ADMIN_PASSWORD && echo
export AGENT_BOOTSTRAP_ADMIN_PASSWORD
mvn spring-boot:run
```

应用将新密码以 BCrypt cost 12 哈希写入数据库，并注销该账户的已有会话。环境变量本身不会写入数据库，后续启动也不会覆盖已修改的密码。完整公网换密、Caddy 配置和检查清单见[公网 HTTPS 部署](public-deployment.md)。

管理后台复用同一登录会话，并额外要求账户角色为 `ADMIN`；不存在独立管理口令或绕过开关。`agent.chat.user-isolation-enabled` 默认开启，普通用户只能访问自己的会话。

应用只支持域名根路径部署。原 `/qa` 子路径已经废弃，前后端一律使用根绝对路径。

## 本机 HTTP 开发

本机开发不经过 HTTPS 反向代理时，需要显式关闭公网门禁和 Secure Cookie，同时保持 loopback 监听：

```bash
SERVER_ADDRESS=127.0.0.1 \
AGENT_PUBLIC_ACCESS_ENABLED=false \
AGENT_AUTH_COOKIE_SECURE=false \
AGENT_AUTH_COOKIE_NAME=local_session \
mvn spring-boot:run
```

服务配置、工作空间目录和可选能力见[配置指南](configuration.md)。
