package com.example.agentweb.infra;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

    public AgentCliGateway(AgentCliProperties props, EnvProperties envProperties) {
        this.props = props;
        this.envProperties = envProperties;
    }

    @Override
    public String runOnce(AgentType type, String workingDir, String userMessage) throws IOException, InterruptedException {
        AgentCliProperties.Client cfg = resolve(type);
        if (cfg.getExec() == null || cfg.getExec().trim().isEmpty()) {
            throw new IllegalStateException("Executable not configured for " + type);
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
        if (cfg.getExec() == null || cfg.getExec().trim().isEmpty()) {
            throw new IllegalStateException("Executable not configured for " + type);
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

        // timeout watchdog (use ScheduledThreadPoolExecutor to conform to P3C)
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        Future<?> killer = null;
        if (cfg.getTimeoutSeconds() > 0) {
            killer = scheduler.schedule(() -> {
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
            scheduler.shutdownNow();
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

    private String readWithTimeout(Process p, int timeoutSeconds) throws InterruptedException {
        java.util.concurrent.ThreadPoolExecutor es = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>());
        try {
            Future<String> fut = es.submit(() -> {
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
            if (timeoutSeconds <= 0) {
                return fut.get();
            }
            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            long millis = Duration.between(Instant.now(), deadline).toMillis();
            return fut.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            p.destroyForcibly();
            return "[timeout]";
        } catch (ExecutionException ee) {
            return "[error] " + ee.getCause();
        } finally {
            es.shutdownNow();
        }
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
