package com.example.agentweb.infra;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.cli.BuildContext;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.log.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * CLI-based implementation: spawn a process per message.
 * <p>命令拼装、resume 处理、session id 抽取等 CLI 相关差异已下沉到 {@link CliDialect}。
 * 本类只负责通用的进程编排：stdin 写入、watchdog kill、按行读 stdout。</p>
 * @author zhourui(V33215020)
 */
@Component
@Slf4j
public class AgentCliGateway implements AgentGateway {

    private static final String SLASH_PREFIX = "/";

    private final AgentCliProperties props;
    private final EnvProperties envProperties;
    private final com.example.agentweb.infra.git.GitProcessEnvCustomizer gitEnvCustomizer;
    private final ProcessEnvironmentSanitizer environmentSanitizer;
    private final Map<AgentType, CliDialect> dialects;
    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<String, Process>();
    private final ScheduledExecutorService watchdogScheduler;
    private final ExecutorService readExecutor;

    @Autowired
    public AgentCliGateway(AgentCliProperties props, EnvProperties envProperties,
                           com.example.agentweb.infra.git.GitProcessEnvCustomizer gitEnvCustomizer,
                           List<CliDialect> dialectBeans,
                           ProcessEnvironmentSanitizer environmentSanitizer) {
        this.props = props;
        this.envProperties = envProperties;
        this.gitEnvCustomizer = gitEnvCustomizer;
        this.environmentSanitizer = environmentSanitizer;
        this.dialects = new EnumMap<AgentType, CliDialect>(AgentType.class);
        for (CliDialect d : dialectBeans) {
            this.dialects.put(d.type(), d);
        }
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, namedFactory("agent-cli-watchdog"));
        scheduler.setRemoveOnCancelPolicy(true);
        this.watchdogScheduler = scheduler;
        this.readExecutor = Executors.newCachedThreadPool(namedFactory("agent-cli-reader"));
    }

    AgentCliGateway(AgentCliProperties props, EnvProperties envProperties,
                    com.example.agentweb.infra.git.GitProcessEnvCustomizer gitEnvCustomizer,
                    List<CliDialect> dialectBeans) {
        this(props, envProperties, gitEnvCustomizer, dialectBeans, new ProcessEnvironmentSanitizer());
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> new Thread(r, prefix + "-" + counter.incrementAndGet());
    }

    @Override
    public String runOnce(AgentType type, String workingDir, String userMessage, String userId)
            throws IOException, InterruptedException {
        AgentCliProperties.Client cfg = resolve(type);
        List<String> cmd = resolveDialect(type).buildCommand(BuildContext.builder()
                .config(cfg)
                .userMessage(userMessage)
                .workingDir(workingDir)
                .build());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);
        environmentSanitizer.sanitize(pb);
        gitEnvCustomizer.applyIdentityOnly(pb, userId);

        long startNanos = System.nanoTime();
        log.debug("cli-runonce-build agentType={} cwd={} cmd={}",
                type, workingDir, LogSafe.summarizeCmd(cmd));
        Process p = pb.start();
        log.info("cli-runonce-spawn agentType={} pid={} cwd={}", type, processId(p), workingDir);
        // Optionally write message to stdin
        if (cfg.isStdin()) {
            OutputStream os = p.getOutputStream();
            os.write(userMessage.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        } else {
            try {
                p.getOutputStream().close();
            } catch (IOException ignore) { /* no-op */ }
        }

        String output = readWithTimeout(p, cfg.getTimeoutSeconds(), cfg.getStdoutDrainGraceMs(),
                cfg.getMaxOutputBytes());
        int code = p.waitFor();
        log.info("cli-runonce-exit agentType={} exitCode={} outputLen={} elapsedMs={}",
                type, code, LogSafe.safeLen(output), elapsedMs(startNanos));
        // Keep output even on non-zero exit to help debugging
        return "[exit=" + code + "]\n" + output;
    }

    @Override
    public void runStream(AgentType type,
                          String workingDir,
                          String userMessage,
                          String sessionId,
                          String resumeId,
                          String env,
                          long timeoutSeconds,
                          java.util.function.Consumer<String> onChunk,
                          java.util.function.IntConsumer onExit,
                          String userId,
                          java.util.Map<String, String> extraEnv) throws IOException, InterruptedException {
        AgentCliProperties.Client cfg = resolve(type);
        List<String> cmd = resolveDialect(type).buildCommand(BuildContext.builder()
                .config(cfg)
                .userMessage(userMessage)
                .resumeId(resumeId)
                .workingDir(workingDir)
                .build());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);
        environmentSanitizer.sanitize(pb);
        gitEnvCustomizer.applyIdentityOnly(pb, userId);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        final long startNanos = System.nanoTime();
        log.debug("cli-stream-build agentType={} sessionId={} resumeId={} cwd={} cmd={}",
                type, sessionId, resumeId, workingDir, LogSafe.summarizeCmd(cmd));

        final Process p = pb.start();
        if (sessionId != null) {
            registerProcess(sessionId, p);
        }
        log.info("cli-stream-spawn agentType={} sessionId={} resumeId={} pid={}",
                type, sessionId, resumeId, processId(p));
        Future<?> killer = null;
        Future<?> readDone = null;
        try {
            writeStdin(p, cfg, userMessage, env);

            // per-call 超时 >0 时优先（诊断侧据此强制 30 分钟超时）；否则回退到 agent.cli.* 配置
            long effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : cfg.getTimeoutSeconds();
            killer = scheduleTimeoutKill(p, effectiveTimeout, onChunk);
            AtomicBoolean turnEnded = new AtomicBoolean(false);
            final CliDialect dialect = resolveDialect(type);
            readDone = startStdoutReader(p, type, sessionId, startNanos, onChunk, dialect, turnEnded);

            int code = p.waitFor();
            log.info("cli-stream-exit agentType={} sessionId={} elapsedMs={} turnEnded={} exitCode={}",
                    type, sessionId, elapsedMs(startNanos), turnEnded.get(), code);
            if (turnEnded.get()) {
                readDone.cancel(true);
            } else {
                awaitStdoutDrain(readDone, cfg.getStdoutDrainGraceMs());
            }
            // turn-end 收尾时进程是被读线程强杀的，回合既已正常结束则报 0。
            onExit.accept(turnEnded.get() ? 0 : code);
        } finally {
            if (killer != null) {
                killer.cancel(false);
            }
            if (readDone != null && !readDone.isDone()) {
                readDone.cancel(true);
            }
            if (sessionId != null) {
                runningProcesses.remove(sessionId, p);
            }
            if (p.isAlive()) {
                destroyProcessTree(p);
            }
        }
    }

    /** 按配置把 user message（含环境约束前缀）写入子进程 stdin；非 stdin 模式直接关闭输出流。 */
    private void writeStdin(Process p, AgentCliProperties.Client cfg, String userMessage, String env)
            throws IOException {
        if (!cfg.isStdin()) {
            try { p.getOutputStream().close(); } catch (IOException ignore) { /* no-op */ }
            return;
        }
        String fullMessage = composeMessage(userMessage, env);
        log.debug("cli-stream-stdin env={} messageLen={}", env, fullMessage.length());
        OutputStream os = p.getOutputStream();
        os.write(fullMessage.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();
    }

    /**
     * 拼装最终发给 agent 的消息：环境约束前缀 + 用户消息。
     * <p>slash command 必须以 "/" 开头才能被 CLI 识别(MCP prompt / 内置命令),
     * 因此对 slash command 把环境约束追加到末尾,而不是顶头拼接。</p>
     */
    private String composeMessage(String userMessage, String env) {
        String envPrefix = resolveEnvPrefix(env);
        if (userMessage != null && userMessage.startsWith(SLASH_PREFIX)) {
            return envPrefix.isEmpty() ? userMessage : userMessage + "\n\n" + envPrefix;
        }
        return envPrefix + (userMessage == null ? "" : userMessage);
    }

    /** 取环境约束前缀；env 为空或未配置对应 entry 时返回空串。 */
    private String resolveEnvPrefix(String env) {
        if (env == null || env.trim().isEmpty()) {
            return "";
        }
        EnvProperties.EnvEntry entry = envProperties.findByKey(env.trim());
        return (entry != null && entry.getPrompt() != null) ? entry.getPrompt() : "";
    }

    /** 注册超时看门狗；timeoutSeconds ≤0 时返回 null（不限时）。 */
    private Future<?> scheduleTimeoutKill(final Process p, long timeoutSeconds,
                                          final java.util.function.Consumer<String> onChunk) {
        if (timeoutSeconds <= 0) {
            return null;
        }
        return watchdogScheduler.schedule(() -> {
            log.warn("cli-stream-timeout-kill pid={} timeoutSec={}", processId(p), timeoutSeconds);
            try {
                onChunk.accept("[timeout]\n");
            } catch (Exception ignore) { /* ignore */ }
            destroyProcessTree(p);
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * 在 readExecutor 上启动 stdout 按行读取线程。
     * <p>读到方言判定的 turn-end 标记时：置 {@code turnEnded}、强收子进程（唤醒主线程的 waitFor）、
     * 停止读取 —— turn-end 是终结事件，之后无有效输出，停读可让本线程干净退出而不被孤儿管道阻塞。</p>
     */
    private Future<?> startStdoutReader(final Process p,
                                        final AgentType agentType,
                                        final String sessionId,
                                        final long startNanos,
                                        final java.util.function.Consumer<String> onChunk,
                                        final CliDialect dialect,
                                        final AtomicBoolean turnEnded) {
        final AtomicBoolean firstStdoutLogged = new AtomicBoolean(false);
        final AtomicLong deliveredBytes = new AtomicLong(0L);
        final long maxOutputBytes = normalizeMaxOutputBytes(resolve(agentType).getMaxOutputBytes());
        return readExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }
                        if (firstStdoutLogged.compareAndSet(false, true)) {
                            log.debug("cli-stream-first-stdout agentType={} sessionId={} elapsedMs={} preview={}",
                                    agentType, sessionId, elapsedMs(startNanos), previewLine(line));
                        }
                        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                        if (deliveredBytes.addAndGet(lineBytes) > maxOutputBytes) {
                            log.warn("cli-stream-output-limit agentType={} sessionId={} maxBytes={}",
                                    agentType, sessionId, maxOutputBytes);
                            onChunk.accept("[output-limit]");
                            destroyProcessTree(p);
                            break;
                        }
                        onChunk.accept(line);
                        if (dialect.isTurnEnd(line)) {
                            turnEnded.set(true);
                            log.info("cli-stream-turn-end agentType={} sessionId={} elapsedMs={}",
                                    agentType, sessionId, elapsedMs(startNanos));
                            destroyProcessTree(p);
                            break;
                        }
                    }
                } catch (IOException ioe) {
                    log.warn("cli-stream-read-error agentType={} sessionId={} reason={}",
                            agentType, sessionId, ioe.getMessage());
                    onChunk.accept("[error] " + ioe.getMessage());
                }
            }
        });
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 兼容旧调用点；新代码请直接用 {@link LogSafe#summarizeCmd}。 */
    private String summarizeCommand(List<String> cmd) {
        return LogSafe.summarizeCmd(cmd);
    }

    /** 取进程 PID，反射失败回退到 hashCode（仅供日志使用）。 */
    private String processId(Process p) {
        if (p == null) {
            return "?";
        }
        try {
            // JDK 9+ 才有 Process.pid()；JDK 8 走反射降级
            return String.valueOf(p.getClass().getMethod("pid").invoke(p));
        } catch (Exception ignore) {
            return String.valueOf(p.hashCode());
        }
    }

    private String previewLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    /** 进程退出后给读线程宽限期排空残余缓冲；超时即判定管道被孤儿子进程持有，放弃等待。 */
    private void awaitStdoutDrain(Future<?> readDone, long graceMs) throws InterruptedException {
        try {
            readDone.get(normalizeDrainGraceMs(graceMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            readDone.cancel(true);
        } catch (ExecutionException ignore) {
            // 读线程内部异常已通过 onChunk 上报
        }
    }

    @Override
    public void stopStream(String sessionId) {
        Process p = runningProcesses.remove(sessionId);
        if (p != null && p.isAlive()) {
            log.info("cli-stream-destroy sessionId={} pid={} reason=manual-stop", sessionId, processId(p));
            destroyProcessTree(p);
        } else {
            log.debug("cli-stream-destroy-noop sessionId={} reason={}",
                    sessionId, p == null ? "no-process" : "already-exited");
        }
    }

    @Override
    public boolean isRunning(String sessionId) {
        Process p = runningProcesses.get(sessionId);
        return p != null && p.isAlive();
    }

    @Override
    public String extractResumeId(AgentType type, String stdoutLine) {
        return resolveDialect(type).extractResumeId(stdoutLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String stdoutLine) {
        return resolveDialect(type).normalizeChunk(stdoutLine);
    }

    private String readWithTimeout(Process p, int timeoutSeconds, long drainGraceMs, long configuredMaxOutputBytes)
            throws InterruptedException {
        // collected 由读线程写入、收尾后由本线程读取；用其自身做锁保证可见性。
        // 取共享缓冲而非 Future<String>，是为了在读线程被孤儿管道阻塞、需放弃等待时，
        // 仍能拿回进程退出前已排空的部分输出。
        final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        final AtomicBoolean outputLimited = new AtomicBoolean(false);
        final long maxOutputBytes = normalizeMaxOutputBytes(configuredMaxOutputBytes);
        Future<?> reader = readExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        synchronized (collected) {
                            int remaining = (int) Math.max(0L, maxOutputBytes - collected.size());
                            int accepted = Math.min(r, remaining);
                            if (accepted > 0) {
                                collected.write(buf, 0, accepted);
                            }
                            if (accepted < r) {
                                outputLimited.set(true);
                                destroyProcessTree(p);
                                break;
                            }
                        }
                    }
                } catch (IOException ignore) {
                    // 已读部分保留在 collected 中
                }
            }
        });
        try {
            if (timeoutSeconds > 0) {
                reader.get(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                // 无总超时：仍须独立等进程退出，退出后给读线程宽限期排空缓冲，
                // 避免被 codex 孤儿子进程持有的 stdout 管道久阻
                p.waitFor();
                reader.get(normalizeDrainGraceMs(drainGraceMs), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException te) {
            destroyProcessTree(p);
            reader.cancel(true);
            if (timeoutSeconds > 0) {
                return "[timeout]";
            }
            // timeoutSeconds<=0 超时：进程已退出，返回已排空的部分输出
        } catch (ExecutionException ee) {
            return "[error] " + ee.getCause();
        }
        synchronized (collected) {
            String output = new String(collected.toByteArray(), StandardCharsets.UTF_8);
            return outputLimited.get() ? output + "\n[output-limit]" : output;
        }
    }

    private long normalizeDrainGraceMs(long drainGraceMs) {
        return Math.max(1L, drainGraceMs);
    }

    private long normalizeMaxOutputBytes(long maxOutputBytes) {
        return maxOutputBytes > 0 ? maxOutputBytes : 10L * 1024L * 1024L;
    }

    private CliDialect resolveDialect(AgentType type) {
        CliDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("No CliDialect registered for type: " + type);
        }
        return dialect;
    }

    private AgentCliProperties.Client resolve(AgentType type) {
        if (type == AgentType.CODEX) {
            return props.getCodex();
        }
        if (type == AgentType.CLAUDE) {
            return props.getClaude();
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private void registerProcess(String sessionId, Process process) {
        while (true) {
            Process existing = runningProcesses.putIfAbsent(sessionId, process);
            if (existing == null) {
                return;
            }
            if (existing.isAlive()) {
                destroyProcessTree(process);
                throw new IllegalStateException("Session already has a running process: " + sessionId);
            }
            if (runningProcesses.replace(sessionId, existing, process)) {
                return;
            }
        }
    }

    /** 先终止所有派生进程，再终止根进程。反射使用 ProcessHandle 以保持 Java 8 源码兼容。 */
    private void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object rootHandle = Process.class.getMethod("toHandle").invoke(process);
            @SuppressWarnings("unchecked")
            Stream<Object> descendants = (Stream<Object>) handleType.getMethod("descendants").invoke(rootHandle);
            List<Object> handles = new ArrayList<>();
            try {
                descendants.forEach(handles::add);
            } finally {
                descendants.close();
            }
            Collections.reverse(handles);
            for (Object handle : handles) {
                handleType.getMethod("destroyForcibly").invoke(handle);
            }
            handleType.getMethod("destroyForcibly").invoke(rootHandle);
        } catch (Exception ex) {
            process.destroyForcibly();
        }
    }
}
