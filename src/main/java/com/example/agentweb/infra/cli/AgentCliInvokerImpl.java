package com.example.agentweb.infra.cli;

import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link AgentCliInvoker} 默认实现。复用 {@link CliDialect#buildCommand(BuildContext)} 构造命令,
 * 自管子进程生命周期 + 显式超时,避免与流式 {@code AgentCliGateway} 互相干扰。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
@Component
@Slf4j
public class AgentCliInvokerImpl implements AgentCliInvoker {

    /**
     * CLI 非零退出时, 异常消息里只保留 buffer 尾部这么多字符。
     * 真正的错误行 (鉴权失败 / 模型不支持 / API 报错) 总在输出末尾, 而开头是冗长的
     * stream-json {@code init} 事件 (工具/skill 列表可达数 KB)。截尾避免整条日志被 init 刷屏。
     */
    private static final int ERROR_OUTPUT_TAIL_CHARS = 600;

    private final Map<AgentType, CliDialect> dialects;
    private final AgentCliProperties props;
    private final StreamOutputExtractor outputExtractor;

    public AgentCliInvokerImpl(List<CliDialect> dialectBeans,
                               AgentCliProperties props,
                               StreamOutputExtractor outputExtractor) {
        this.dialects = new EnumMap<>(AgentType.class);
        for (CliDialect d : dialectBeans) {
            this.dialects.put(d.type(), d);
        }
        this.props = props;
        this.outputExtractor = outputExtractor;
    }

    @Override
    public String invokeSync(AgentType type, String workingDir, String prompt, long timeoutSeconds) {
        return invokeSync(type, workingDir, prompt, timeoutSeconds, null);
    }

    @Override
    public String invokeSync(AgentType type, String workingDir, String prompt, long timeoutSeconds,
                             String model) {
        CliDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new CliInvokeException(CliInvokeException.Reason.IO_FAILURE,
                    "no CliDialect registered for " + type);
        }
        AgentCliProperties.Client cfg = resolveClient(type);
        List<String> cmd = dialect.buildCommand(BuildContext.builder()
                .config(cfg)
                .userMessage(prompt)
                .workingDir(workingDir)
                .model(model)
                .build());

        return runProcess(dialect, cmd, workingDir, prompt, cfg.isStdin(), timeoutSeconds);
    }

    private String runProcess(CliDialect dialect, List<String> cmd, String workingDir, String prompt,
                              boolean writeStdin, long timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CliInvokeException(CliInvokeException.Reason.IO_FAILURE,
                    "failed to spawn CLI process", e);
        }

        feedStdin(process, prompt, writeStdin);

        StringBuilder buffer = new StringBuilder();
        Thread reader = startStdoutReader(dialect, process, buffer);

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // destroyForcibly 是异步请求: 子进程未真正退出前仍持有工作目录句柄,
                // 调用方 (含测试 @TempDir) 随即清理工作目录会失败。有界等待确保进程被回收。
                awaitTermination(process);
                throw new CliInvokeException(CliInvokeException.Reason.TIMEOUT,
                        "CLI process timed out after " + timeoutSeconds + "s");
            }
            reader.join(TimeUnit.SECONDS.toMillis(3));
            int code = process.exitValue();
            if (code != 0) {
                throw new CliInvokeException(CliInvokeException.Reason.NON_ZERO_EXIT,
                        "CLI exited with code " + code + ", output=" + tail(buffer));
            }
            String raw = buffer.toString();
            log.debug("agent-cli-invoke-ok cmd0={} outputLen={}", cmd.get(0), raw.length());
            return outputExtractor.extractPlainText(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new CliInvokeException(CliInvokeException.Reason.IO_FAILURE,
                    "interrupted while waiting for CLI process", e);
        }
    }

    /** 强杀后有界等待进程退出, 确保工作目录句柄释放; 被中断时恢复中断标志后返回, 不抛出。 */
    private void awaitTermination(Process process) {
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void feedStdin(Process process, String prompt, boolean writeStdin) {
        if (!writeStdin) {
            try {
                process.getOutputStream().close();
            } catch (IOException ignore) {
                // 关闭 stdin 失败不影响后续读取
            }
            return;
        }
        try (OutputStream os = process.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            log.warn("agent-cli-invoke-stdin-write-failed", e);
        }
    }

    /** 取 buffer 尾部 {@link #ERROR_OUTPUT_TAIL_CHARS} 字符 (真正的错误行), 前面超出部分以 ... 省略。 */
    private String tail(StringBuilder buffer) {
        int len = buffer.length();
        if (len <= ERROR_OUTPUT_TAIL_CHARS) {
            return buffer.toString();
        }
        return "...(" + (len - ERROR_OUTPUT_TAIL_CHARS) + " chars omitted)..."
                + buffer.substring(len - ERROR_OUTPUT_TAIL_CHARS);
    }

    /**
     * 启动 stdout 读取线程,每行先经 dialect 归一化(codex 原始 NDJSON → Claude 兼容形态)
     * 再落入 buffer,使下游 {@link StreamOutputExtractor} 面对统一格式——避免 codex 同步路径
     * (issue-log refine / merge / dedup-match)漏归一化导致整段输出被静默丢弃。
     */
    private Thread startStdoutReader(CliDialect dialect, Process process, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    List<String> normalized = dialect.normalizeChunk(line);
                    if (normalized == null || normalized.isEmpty()) {
                        continue;
                    }
                    synchronized (sink) {
                        for (String n : normalized) {
                            if (n == null) {
                                continue;
                            }
                            sink.append(n).append('\n');
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("agent-cli-invoke-read-failed", e);
            }
        }, "agent-cli-invoker-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private AgentCliProperties.Client resolveClient(AgentType type) {
        switch (type) {
            case CLAUDE:
                return props.getClaude();
            case CODEX:
                return props.getCodex();
            default:
                throw new CliInvokeException(CliInvokeException.Reason.IO_FAILURE,
                        "unsupported agent type: " + type);
        }
    }
}
