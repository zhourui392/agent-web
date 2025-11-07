# Agent Web Service - 后台运行脚本使用说明

## 脚本文件

- `service.sh` - 后台服务管理脚本（推荐用于生产环境）
- `run.sh` - 前台运行脚本（开发调试用）

## service.sh 使用方法

### 1. 启动服务

```bash
./service.sh start
```

服务将在后台运行，日志输出到 `logs/agent-web.log` 文件。

**输出示例：**
```
========================================
  Starting Agent Web Service
========================================
Starting service in background...
✓ Service started successfully (PID: 2001222)
✓ Log file: logs/agent-web.log
✓ Server running on port 18092
```

### 2. 停止服务

```bash
./service.sh stop
```

优雅停止服务（最多等待30秒，之后强制停止）。

**输出示例：**
```
========================================
  Stopping Agent Web Service
========================================
Stopping service (PID: 2001222)...
✓ Service stopped successfully
```

### 3. 重启服务

```bash
./service.sh restart
```

先停止再启动服务。

### 4. 查看服务状态

```bash
./service.sh status
```

显示服务运行状态、PID、日志文件位置和最近的日志信息。

**输出示例：**
```
========================================
  Agent Web Service Status
========================================
✓ Service is running
  PID: 2001222
  Log: logs/agent-web.log
  Port: 18092

Process Info:
UID        PID    PPID  C STIME TTY          TIME CMD
ubuntu   2001222     1 99 20:27 ?        00:00:11 java -jar target/agent-web-0.1.0-SNAPSHOT.jar

Recent Logs (last 10 lines):
...
```

### 5. 实时查看日志

```bash
./service.sh logs
```

实时追踪日志输出（类似 `tail -f`），按 `Ctrl+C` 退出。

## 文件说明

### 生成的文件

- `agent-web.pid` - 保存服务进程的 PID
- `logs/agent-web.log` - 服务日志文件
- `logs/` - 日志目录（自动创建）

### 日志管理

日志会持续追加到 `logs/agent-web.log` 文件中。建议定期清理或使用日志轮转工具：

```bash
# 清空日志
> logs/agent-web.log

# 或者归档旧日志
mv logs/agent-web.log logs/agent-web-$(date +%Y%m%d).log
```

## 开机自启动配置

### 方法1：使用 systemd（推荐）

创建 systemd 服务文件：

```bash
sudo nano /etc/systemd/system/agent-web.service
```

添加以下内容：

```ini
[Unit]
Description=Agent Web Service
After=network.target

[Service]
Type=forking
User=ubuntu
WorkingDirectory=/home/ubuntu/workspace/agent-web
ExecStart=/home/ubuntu/workspace/agent-web/service.sh start
ExecStop=/home/ubuntu/workspace/agent-web/service.sh stop
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启用并启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable agent-web
sudo systemctl start agent-web
sudo systemctl status agent-web
```

### 方法2：使用 crontab

```bash
crontab -e
```

添加以下行：

```
@reboot cd /home/ubuntu/workspace/agent-web && ./service.sh start
```

## 常见问题

### 1. 服务无法启动

检查日志文件 `logs/agent-web.log` 查看错误信息：

```bash
cat logs/agent-web.log
```

### 2. 端口被占用

确认端口 18092 没有被其他程序占用：

```bash
lsof -i :18092
# 或
netstat -tulpn | grep 18092
```

### 3. PID 文件过期

如果 status 显示 "Stale PID file"，脚本会自动清理。也可以手动删除：

```bash
rm -f agent-web.pid
```

### 4. 重新构建后启动

如果修改了代码，需要重新构建：

```bash
mvn clean package -DskipTests
./service.sh restart
```

## 监控和管理

### 检查服务是否正常运行

```bash
curl http://localhost:18092
```

### 查看进程资源占用

```bash
ps aux | grep agent-web
top -p $(cat agent-web.pid)
```

### 查看最近100行日志

```bash
tail -n 100 logs/agent-web.log
```

### 搜索日志中的错误

```bash
grep ERROR logs/agent-web.log
grep Exception logs/agent-web.log
```

## 快速命令参考

```bash
# 启动
./service.sh start

# 停止
./service.sh stop

# 重启
./service.sh restart

# 状态
./service.sh status

# 查看日志
./service.sh logs

# 实时监控
watch -n 2 './service.sh status'
```

## 注意事项

1. **权限**: 确保脚本有执行权限 `chmod +x service.sh`
2. **Java**: 需要 Java 8 或更高版本
3. **Maven**: 首次启动时需要 Maven 构建项目
4. **端口**: 服务运行在 18092 端口
5. **日志**: 日志会持续增长，建议定期清理

## 与 run.sh 的区别

| 特性 | service.sh | run.sh |
|------|-----------|--------|
| 运行方式 | 后台守护进程 | 前台运行 |
| 日志输出 | 文件 (logs/agent-web.log) | 终端 |
| 进程管理 | PID 文件 + start/stop 命令 | Ctrl+C 停止 |
| 适用场景 | 生产环境、长期运行 | 开发调试 |
| 系统登出后 | 继续运行 | 停止 |

## 技术实现

- 使用 `nohup` 实现后台运行
- PID 文件管理进程
- 优雅关闭（SIGTERM）+ 强制停止（SIGKILL）
- 日志重定向到文件
- 启动健康检查（3秒后验证进程是否存活）
