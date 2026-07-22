package com.example.agentweb.adapter;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chatrun.ChatExecutionActivityProbe;

import java.io.IOException;
import java.util.List;

/**
 * Port for sending a prompt to a specific CLI agent implementation.
 * @author zhourui(V33215020)
 */
public interface AgentGateway extends ChatExecutionActivityProbe {

    /**
     * Execute one prompt against the selected agent in the given working directory.
     * @param type Agent type
     * @param workingDir Working directory (must exist)
     * @param userMessage User prompt content
     * @return stdout/stderr merged output
     * @throws IOException 进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    default String runOnce(AgentType type, String workingDir, String userMessage)
            throws IOException, InterruptedException {
        return runOnce(type, workingDir, userMessage, null);
    }

    /**
     * 同 {@link #runOnce(AgentType, String, String)}，但带会话 owner userId 用于注入 per-user git 身份。
     *
     * @param type        Agent type
     * @param workingDir  工作目录
     * @param userMessage 用户提示词
     * @param userId      会话 owner 工号；{@code null}（系统路径 / 默认用户）→ 不注入，走机器默认 git
     * @return stdout/stderr merged output
     * @throws IOException          进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    String runOnce(AgentType type, String workingDir, String userMessage, String userId)
            throws IOException, InterruptedException;

    /**
     * Stream output chunks as the agent runs. Implementations should invoke onChunk for each
     * available stdout/stderr chunk and call onExit with the final exit code (or -1 on timeout).
     * This method should block until the process exits.
     * @param type Agent type
     * @param workingDir 工作目录,必须已存在
     * @param userMessage 用户提示词内容
     * @param sessionId Session ID used to track and stop the process
     * @param resumeId Optional resume ID for continuing a conversation (used for Claude --resume)
     * @param env 环境约束 key (对应 EnvProperties),空字符串/null 表示无约束
     * @param timeoutSeconds Per-call hard timeout; the process is force-killed after this many
     *                       seconds. {@code <= 0} uses the configured stream idle timeout and
     *                       absolute runtime limit.
     * @param onChunk 单行 stdout/stderr 输出回调
     * @param onExit 进程退出回调,参数为退出码 (-1 表示超时被强制终止)
     * @throws IOException 进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    default void runStream(AgentType type,
                           String workingDir,
                           String userMessage,
                           String sessionId,
                           String resumeId,
                           String env,
                           long timeoutSeconds,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.IntConsumer onExit) throws IOException, InterruptedException {
        runStream(type, workingDir, userMessage, sessionId, resumeId, env, timeoutSeconds,
                onChunk, onExit, null);
    }

    /**
     * 同 {@link #runStream}，但带会话 owner userId 用于注入 per-user git 身份。
     * <p>流式路径会 fork 线程，子线程取不到 {@code CurrentUserProvider}，故 userId 由调用方在 fork 前
     * 从会话 owner 解析并透传。系统路径（diagnose / scheduled / ticket）传 {@code null} → 走机器默认 git。</p>
     *
     * @param type           Agent type
     * @param workingDir     工作目录
     * @param userMessage    用户提示词
     * @param sessionId      会话 ID
     * @param resumeId       resume ID（可空）
     * @param env            环境约束 key（可空）
     * @param timeoutSeconds per-call 硬超时
     * @param onChunk        单行输出回调
     * @param onExit         退出回调
     * @param userId         会话 owner 工号；{@code null}（系统路径 / 默认用户）→ 不注入
     * @throws IOException          进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    default void runStream(AgentType type,
                           String workingDir,
                           String userMessage,
                           String sessionId,
                           String resumeId,
                           String env,
                           long timeoutSeconds,
                           java.util.function.Consumer<String> onChunk,
                           java.util.function.IntConsumer onExit,
                           String userId) throws IOException, InterruptedException {
        runStream(type, workingDir, userMessage, sessionId, resumeId, env, timeoutSeconds,
                onChunk, onExit, userId, null);
    }

    /**
     * 同 {@link #runStream}，但允许向本次 run 的子进程注入额外环境变量。
     *
     * @param type           Agent type
     * @param workingDir     工作目录
     * @param userMessage    用户提示词
     * @param sessionId      会话 ID
     * @param resumeId       resume ID（可空）
     * @param env            环境约束 key（可空）
     * @param timeoutSeconds per-call 硬超时
     * @param onChunk        单行输出回调
     * @param onExit         退出回调
     * @param userId         会话 owner 工号（可空）
     * @param extraEnv       附加进程环境变量（可空 = 不注入）
     * @throws IOException          进程启动失败或读 IO 异常
     * @throws InterruptedException 等待进程退出时被中断
     */
    void runStream(AgentType type,
                   String workingDir,
                   String userMessage,
                   String sessionId,
                   String resumeId,
                   String env,
                   long timeoutSeconds,
                   java.util.function.Consumer<String> onChunk,
                   java.util.function.IntConsumer onExit,
                   String userId,
                   java.util.Map<String, String> extraEnv) throws IOException, InterruptedException;

    /**
     * 与 {@link #runStream} 相同，但把进程终止原因作为结构化结果返回。
     * <p>默认实现兼容旧 Gateway，仅能报告普通退出；支持技术超时和输出上限的适配器应覆盖完整重载。</p>
     */
    default void runStreamWithResult(AgentType type,
                                     String workingDir,
                                     String userMessage,
                                     String sessionId,
                                     String resumeId,
                                     String env,
                                     long timeoutSeconds,
                                     java.util.function.Consumer<String> onChunk,
                                     java.util.function.Consumer<AgentStreamResult> onExit)
            throws IOException, InterruptedException {
        runStreamWithResult(type, workingDir, userMessage, sessionId, resumeId, env,
                timeoutSeconds, onChunk, onExit, null, null);
    }

    /**
     * 带用户身份和附加环境变量的结构化流式执行入口。
     */
    default void runStreamWithResult(AgentType type,
                                     String workingDir,
                                     String userMessage,
                                     String sessionId,
                                     String resumeId,
                                     String env,
                                     long timeoutSeconds,
                                     java.util.function.Consumer<String> onChunk,
                                     final java.util.function.Consumer<AgentStreamResult> onExit,
                                     String userId,
                                     java.util.Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStream(type, workingDir, userMessage, sessionId, resumeId, env, timeoutSeconds,
                onChunk, code -> onExit.accept(AgentStreamResult.completed(code)), userId, extraEnv);
    }

    /**
     * Stop a running stream process by session ID.
     * @param sessionId 会话 ID,无对应运行进程时静默返回
     */
    void stopStream(String sessionId);

    /**
     * Check if a stream process is still running for the given session.
     * @param sessionId 会话 ID
     * @return true 表示该会话有正在运行的进程
     */
    boolean isRunning(String sessionId);

    @Override
    default boolean isExecutionActive(String sessionId) {
        return isRunning(sessionId);
    }

    /**
     * Extract the CLI-internal session id (used for subsequent resume) from one line of stdout.
     * <p>Different CLIs surface this id under different event types and field names (Claude:
     * {@code system.init.session_id}; Codex: {@code thread.started.thread_id}). Returns {@code null}
     * when the line does not contain a session id for the given agent type.</p>
     * @param type Agent type
     * @param stdoutLine 单行 stdout 输出
     * @return CLI 侧 session id,无法提取时返回 null
     */
    String extractResumeId(AgentType type, String stdoutLine);

    /**
     * Normalize one line of stdout into 0..N front-end events. One raw codex event
     * ({@code item.started/command_execution}) can expand to two front-end events
     * ({@code content_block_start} + {@code input_json_delta}); reconnect noise
     * ({@code error}) returns an empty list; regular events return one element.
     * Claude implementations return a singleton list (pass-through).
     * @param type Agent type
     * @param stdoutLine 单行 stdout 输出
     * @return 归一化后的前端事件 JSON 列表 (0..N 条)
     */
    List<String> normalizeChunk(AgentType type, String stdoutLine);
}
