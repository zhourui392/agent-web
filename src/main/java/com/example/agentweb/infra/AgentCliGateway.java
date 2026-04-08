package com.example.agentweb.infra;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * CLI-based implementation: spawn a process per message.
 * Notes:
 * - Works only if the CLI can run non-interactively (read from stdin or args).
 * - Merges stdout/stderr to preserve context.
 */
@Component
public class AgentCliGateway implements AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(AgentCliGateway.class);
    private final AgentCliProperties props;
    private final EnvProperties envProperties;
    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<String, Process>();
    private final ScheduledExecutorService watchdogScheduler;
    private final ExecutorService readExecutor;

    public AgentCliGateway(AgentCliProperties props, EnvProperties envProperties) {
        this.props = props;
        this.envProperties = envProperties;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        this.watchdogScheduler = scheduler;
        this.readExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public String runOnce(AgentType type, String workingDir, String userMessage) throws IOException, InterruptedException {
        AgentCliProperties.Client cfg = resolve(type);
        List<String> cmd = buildCommand(cfg, userMessage);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);

        Process p = pb.start();
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

        String output = readWithTimeout(p, cfg.getTimeoutSeconds());
        int code = p.waitFor();
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
                          java.util.function.Consumer<String> onChunk,
                          java.util.function.IntConsumer onExit) throws IOException, InterruptedException {
        AgentCliProperties.Client cfg = resolve(type);
        List<String> cmd = buildCommand(cfg, userMessage);

        // Add --resume flag for Claude if resumeId is provided
        if (type == AgentType.CLAUDE && resumeId != null && !resumeId.trim().isEmpty()) {
            cmd.add("--resume");
            cmd.add(resumeId.trim());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);

        final Process p = pb.start();
        if (sessionId != null) {
            runningProcesses.put(sessionId, p);
        }
        // stdin behavior
        if (cfg.isStdin()) {
            String envPrefix = "";
            if (env != null && !env.trim().isEmpty()) {
                EnvProperties.EnvEntry entry = envProperties.findByKey(env.trim());
                if (entry != null && entry.getPrompt() != null) {
                    envPrefix = entry.getPrompt();
                }
            }
            OutputStream os = p.getOutputStream();
            String fullMessage = envPrefix + userMessage;
            log.info("stdin message (env={}): {}", env, fullMessage.length() > 200 ? fullMessage.substring(0, 200) + "..." : fullMessage);
            os.write(fullMessage.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        } else {
            try { p.getOutputStream().close(); } catch (IOException ignore) { /* no-op */ }
        }

        // timeout watchdog
        Future<?> killer = null;
        if (cfg.getTimeoutSeconds() > 0) {
            killer = watchdogScheduler.schedule(() -> {
                try {
                    onChunk.accept("[timeout]\n");
                } catch (Exception ignore) { /* ignore */ }
                p.destroyForcibly();
            }, cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
        }

        // stream reading loop: read line-by-line to ensure each SSE chunk is a complete JSON line
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    onChunk.accept(line);
                }
            }
        } catch (IOException ioe) {
            onChunk.accept("[error] " + ioe.getMessage());
        } finally {
            if (killer != null) { killer.cancel(false); }
        }
        int code = p.waitFor();
        if (sessionId != null) {
            runningProcesses.remove(sessionId);
        }
        onExit.accept(code);
    }

    @Override
    public void stopStream(String sessionId) {
        Process p = runningProcesses.remove(sessionId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    @Override
    public boolean isRunning(String sessionId) {
        Process p = runningProcesses.get(sessionId);
        return p != null && p.isAlive();
    }

    private String readWithTimeout(Process p, int timeoutSeconds) throws InterruptedException {
        Future<String> fut = readExecutor.submit(() -> {
            try (InputStream is = p.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                }
                return bos.toString("UTF-8");
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (timeoutSeconds <= 0) {
                return fut.get();
            }
            return fut.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            p.destroyForcibly();
            return "[timeout]";
        } catch (ExecutionException ee) {
            return "[error] " + ee.getCause();
        }
    }

    private List<String> buildCommand(AgentCliProperties.Client cfg, String userMessage) {
        if (cfg.getExec() == null || cfg.getExec().trim().isEmpty()) {
            throw new IllegalStateException("Executable not configured");
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(cfg.getExec());
        for (String a : cfg.getArgs()) {
            if (a.contains("${MESSAGE}")) {
                cmd.add(a.replace("${MESSAGE}", userMessage));
            } else {
                cmd.add(a);
            }
        }
        return cmd;
    }

    private AgentCliProperties.Client resolve(AgentType type) {
        if (type == AgentType.CODEX) {
            return props.getCodex();
        } else if (type == AgentType.CLAUDE) {
            return props.getClaude();
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }
}
