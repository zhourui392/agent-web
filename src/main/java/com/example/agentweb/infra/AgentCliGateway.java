package com.example.agentweb.infra;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
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

    private final AgentCliProperties props;

    public AgentCliGateway(AgentCliProperties props) {
        this.props = props;
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

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);

        final Process p = pb.start();
        // stdin behavior
        if (cfg.isStdin()) {
            OutputStream os = p.getOutputStream();
            os.write(userMessage.getBytes(StandardCharsets.UTF_8));
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

        // stream reading loop (blocking until EOF)
        try (InputStream is = p.getInputStream()) {
            byte[] buf = new byte[2048];
            int r;
            while ((r = is.read(buf)) != -1) {
                String s = new String(buf, 0, r, StandardCharsets.UTF_8);
                onChunk.accept(s);
            }
        } catch (IOException ioe) {
            onChunk.accept("[error] " + ioe.getMessage() + "\n");
        } finally {
            if (killer != null) { killer.cancel(false); }
            scheduler.shutdownNow();
        }
        int code = p.waitFor();
        onExit.accept(code);
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
