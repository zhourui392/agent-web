package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RefineryRecaller;
import com.example.agentweb.app.refinery.RecallOutcome;
import com.example.agentweb.app.refinery.RecallStatus;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chat.ShareToken;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.ChatProperties;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import com.example.agentweb.infra.log.LogSafe;
import com.example.agentweb.infra.log.MdcContext;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.TruncateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 聊天应用服务实现，编排会话生命周期、消息收发与对话摘要生成。
 * <p>通过 {@link SessionCache} 做内存快查，{@link SessionRepository} 做持久化，
 * 并委托 {@link AgentGateway} 调用 CLI Agent 完成实际推理。</p>
 * @author zhourui(V33215020)
 */
@Service
@Slf4j
public class ChatAppServiceImpl implements ChatAppService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SSE keepalive 间隔。codex 非流式, 事件间静默可达数十秒, 需小于前端 30s 心跳阈值。 */
    private static final long KEEPALIVE_INTERVAL_SECONDS = 15L;

    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;
    private final AgentGateway gateway;
    private final Executor agentExecutor;
    private final SlashCommandExpander commandExpander;
    private final StreamOutputExtractor outputExtractor;
    private final AgentTypeResolver agentTypeResolver;
    private final UploadPicStore uploadPicStore;
    private final UploadFileStore uploadFileStore;
    private final Optional<RefineryRecaller> chatRagRecaller;
    private final Optional<RecallObservationRecorder> recallObservationRecorder;
    private final CurrentUserProvider currentUserProvider;
    private final ChatProperties chatProperties;
    private WorkspacePathPolicy workspacePathPolicy;
    private final ScheduledExecutorService keepAliveScheduler =
            new ScheduledThreadPoolExecutor(2, namedFactory("chat-sse-keepalive"));

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> new Thread(r, prefix + "-" + counter.incrementAndGet());
    }

    /** 仅供历史单测构造器使用，生产装配始终注入真实路径策略。 */
    private static WorkspacePathPolicy permissivePathPolicy() {
        return new WorkspacePathPolicy() {
            @Override
            public String requireExistingDirectory(String path) {
                return path;
            }

            @Override
            public String requireExistingFile(String path) {
                return path;
            }

            @Override
            public String prepareWorkspaceDirectory(String path) {
                return path;
            }

            @Override
            public String prepareUploadDirectory(String path) {
                return path;
            }

            @Override
            public boolean isExistingPathAllowed(String path) {
                return true;
            }
        };
    }

    @Autowired
    public ChatAppServiceImpl(SessionCache sessionCache,
                              SessionRepository sessionRepository,
                              AgentGateway gateway,
                              Executor agentExecutor,
                              SlashCommandExpander commandExpander,
                              StreamOutputExtractor outputExtractor,
                              AgentTypeResolver agentTypeResolver,
                              UploadPicStore uploadPicStore,
                              UploadFileStore uploadFileStore,
                              Optional<RefineryRecaller> chatRagRecaller,
                              Optional<RecallObservationRecorder> recallObservationRecorder,
                              CurrentUserProvider currentUserProvider,
                              ChatProperties chatProperties) {
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
        this.gateway = gateway;
        this.agentExecutor = agentExecutor;
        this.commandExpander = commandExpander;
        this.outputExtractor = outputExtractor;
        this.agentTypeResolver = agentTypeResolver;
        this.uploadPicStore = uploadPicStore;
        this.uploadFileStore = uploadFileStore;
        this.chatRagRecaller = chatRagRecaller;
        this.recallObservationRecorder = recallObservationRecorder;
        this.currentUserProvider = currentUserProvider;
        this.chatProperties = chatProperties;
        this.workspacePathPolicy = permissivePathPolicy();
    }

    /**
     * 生产装配注入真实路径策略；独立单测未注入时保留历史构造兼容。
     */
    @Autowired
    void configureWorkspacePathPolicy(WorkspacePathPolicy workspacePathPolicy) {
        this.workspacePathPolicy = workspacePathPolicy;
    }

    ChatAppServiceImpl(SessionCache sessionCache,
                       SessionRepository sessionRepository,
                       AgentGateway gateway,
                       Executor agentExecutor,
                       SlashCommandExpander commandExpander,
                       StreamOutputExtractor outputExtractor,
                       AgentTypeResolver agentTypeResolver,
                       UploadPicStore uploadPicStore,
                       UploadFileStore uploadFileStore,
                       Optional<RefineryRecaller> chatRagRecaller,
                       RecallObservationRecorder recallObservationRecorder,
                       CurrentUserProvider currentUserProvider) {
        this(sessionCache, sessionRepository, gateway, agentExecutor, commandExpander, outputExtractor,
                agentTypeResolver, uploadPicStore, uploadFileStore, chatRagRecaller,
                Optional.ofNullable(recallObservationRecorder), currentUserProvider, new ChatProperties());
    }

    ChatAppServiceImpl(SessionCache sessionCache,
                       SessionRepository sessionRepository,
                       AgentGateway gateway,
                       Executor agentExecutor,
                       SlashCommandExpander commandExpander,
                       StreamOutputExtractor outputExtractor,
                       AgentTypeResolver agentTypeResolver,
                       UploadPicStore uploadPicStore,
                       UploadFileStore uploadFileStore,
                       Optional<RefineryRecaller> chatRagRecaller,
                       CurrentUserProvider currentUserProvider) {
        this(sessionCache, sessionRepository, gateway, agentExecutor, commandExpander, outputExtractor,
                agentTypeResolver, uploadPicStore, uploadFileStore, chatRagRecaller,
                Optional.empty(), currentUserProvider, new ChatProperties());
    }

    @Override
    public ChatSession startSession(StartSessionRequest req, String clientIp) {
        Assert.notNull(req, "request is null");
        AgentType type = agentTypeResolver.resolve(req.getAgentType());
        String workingDir = workspacePathPolicy.requireExistingDirectory(req.getWorkingDir());
        ChatSession s = new ChatSession(type, workingDir);
        // 持久化创建时选定的环境, 用于后续恢复时回填; null/空串均按 "无环境" 处理
        String reqEnv = req.getEnv();
        if (reqEnv != null && !reqEnv.isEmpty()) {
            s.setEnv(reqEnv);
        }
        // 客户端来源 IP 作审计弱归因; 空白按"未采集"处理
        if (clientIp != null && !clientIp.trim().isEmpty()) {
            s.setClientIp(clientIp.trim());
        }
        // 创建者标识用于会话数据隔离；null（无登录上下文/系统创建）按老数据语义全员可见
        s.setUserId(currentUserProvider.currentUserId());
        // 创建者姓名仅作审计记录(前端不展示); null 表示拿不到
        s.setUserName(currentUserProvider.currentUserName());
        sessionCache.save(s);
        sessionRepository.saveSession(s);
        log.debug("chat-session-persisted sessionId={} agentType={} workingDir={} env={} clientIp={} userId={} userName={}",
                s.getId(), type, s.getWorkingDir(), s.getEnv(), s.getClientIp(), s.getUserId(), s.getUserName());
        return s;
    }

    @Override
    public String sendMessage(String sessionId, SendMessageRequest req) throws IOException, InterruptedException {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            log.warn("chat-send-rejected reason=session-not-found sessionId={}", sessionId);
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        log.info("chat-send-once sessionId={} agentType={} messageLen={}",
                sessionId, s.getAgentType(), LogSafe.safeLen(req.getMessage()));
        // persist user message
        ChatMessage userMsg = new ChatMessage("user", req.getMessage());
        sessionRepository.addMessage(sessionId, userMsg);

        long startMs = System.currentTimeMillis();
        // 注入会话 owner 的 git 身份: 该会话内 agent 的 git commit 用属主身份 (默认用户/未配置回落机器默认)
        String output = gateway.runOnce(s.getAgentType(), s.getWorkingDir(), req.getMessage(), s.getUserId());
        log.info("chat-send-once-done sessionId={} outputLen={} elapsedMs={}",
                sessionId, LogSafe.safeLen(output), System.currentTimeMillis() - startMs);

        // persist assistant response
        ChatMessage assistantMsg = new ChatMessage("assistant", output);
        sessionRepository.addMessage(sessionId, assistantMsg);

        return output;
    }

    @Override
    public ChatSession getSession(String sessionId) {
        ChatSession s = sessionCache.find(sessionId);
        if (s != null) {
            if (!canCurrentUserSee(s)) {
                // 缓存命中但非属主: 与 SessionRepository 读侧 user_id 过滤同语义 —— 不可见即视为不存在,
                // 堵住进程级缓存绕过 SQL 过滤的越权读 (send/stream/listCommands 均经此路径)
                log.warn("chat-session-cache-denied sessionId={}", sessionId);
                return null;
            }
            log.debug("chat-session-cache-hit sessionId={}", sessionId);
            return s;
        }
        // Fallback to persistent storage (已在 SessionRepository 读侧按 user_id 过滤)
        s = sessionRepository.findById(sessionId);
        if (s != null) {
            log.debug("chat-session-cache-miss-loaded sessionId={} messageCount={}",
                    sessionId, s.getMessages() == null ? 0 : s.getMessages().size());
            sessionCache.save(s);
        } else {
            log.debug("chat-session-not-found sessionId={}", sessionId);
        }
        return s;
    }

    /**
     * 当前用户是否可见该会话, 与 {@link SessionRepository} 读侧 user_id 过滤同语义:
     * 后台线程 / admin ({@code shouldFilter()=false}) 一律放行; 普通用户仅可见自己的会话 + 老数据(owner 为 null)。
     */
    private boolean canCurrentUserSee(ChatSession session) {
        if (!currentUserProvider.shouldFilter()) {
            return true;
        }
        String owner = session.getUserId();
        return owner == null || owner.equals(currentUserProvider.currentUserId());
    }

    @Override
    public SseEmitter streamMessage(String sessionId, String message, String resumeId, String env,
                                    boolean recallEnabled) {
        final ChatSession s = getSession(sessionId);
        if (s == null) {
            log.warn("chat-stream-rejected reason=session-not-found sessionId={}", sessionId);
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        // env 在会话创建时一次性写入 session.env, 之后不可变 (前端单选 disabled)
        // URL 上的 env 参数仅为保持 controller 兼容, 这里一律以 session.env 为准
        final String effectiveEnv = s.getEnv();

        // 续聊判定必须以服务端持久化状态为准: 前端可能还残留被 truncate/人工清理前的旧 resumeId。
        // 原因 1: sessionCache 内的 ChatSession.messages 是初始加载的快照, addMessage 仅写 DB 不更新内存对象。
        // 原因 2: cache 与浏览器中的 resumeId 都可能滞后, 只有 DB 端 resume_id 表示当前会话真实可续接线程。
        final ChatSession freshSession = sessionRepository.findById(sessionId);
        final String effectiveResumeId = resolveEffectiveResumeId(sessionId, resumeId, freshSession);
        final List<ChatMessage> historySnapshot = resolveHistoryForPrefix(freshSession, effectiveResumeId);

        // persist user message (始终持久化原文, 不带前缀, 保证再次回退/摘要等场景看到的是干净对话)
        final long userMessageId = sessionRepository.addMessageReturningId(sessionId, new ChatMessage("user", message));
        final Optional<String> recallAttemptId = createRecallAttempt(sessionId, userMessageId, message,
                recallEnabled, effectiveEnv);

        // No SSE timeout – let the CLI process (and its own watchdog) control the lifecycle
        final SseEmitter emitter = new SseEmitter(-1L);
        bindEmitterLifecycle(emitter, sessionId);
        final StreamChunkHandler handler = new StreamChunkHandler(sessionRepository, sessionId, gateway, s.getAgentType());
        if (recallAttemptId.isPresent() && recallObservationRecorder.isPresent()) {
            final String attemptId = recallAttemptId.get();
            handler.onAssistantPersisted(msgId ->
                    safeAttachAssistantMessage(attemptId, msgId));
        }
        // SSE keepalive: codex 非流式, 事件间静默可达数十秒, 定期 ping 防止前端心跳/中间代理误判连接死亡
        final ScheduledFuture<?> keepAlive = scheduleKeepAlive(emitter);

        log.info("chat-stream-submit sessionId={} agentType={} resumeId={} requestResumeId={} effectiveEnv={} messageLen={}",
                sessionId, s.getAgentType(), effectiveResumeId, resumeId, effectiveEnv, LogSafe.safeLen(message));

        agentExecutor.execute(MdcContext.wrap(new Runnable() {
            @Override
            public void run() {
                try {
                    // RAG recall runs when the frontend switch is on and refinery is available.
                    // The trace carries complete observability facts; the public outcome only
                    // controls whether to show a recall card and inject the augmented prompt.
                    RecallTrace trace = (recallEnabled && chatRagRecaller.isPresent())
                            ? chatRagRecaller.get().traceForChat(message, s.getWorkingDir())
                            : null;
                    if (trace != null && recallAttemptId.isPresent()) {
                        safeRecordRecallTrace(recallAttemptId.get(), trace);
                    }
                    RecallOutcome recall = trace == null ? RecallOutcome.notRecalled(message) : trace.toOutcome();
                    if (recall.isRecalled()) {
                        // Send one recall event so the hidden prompt injection is visible to users.
                        String recallJson = recallEventJson(recall);
                        trySend(emitter, "recall", recallJson);
                        // Give the same public payload to the handler so the persisted assistant
                        // message can replay the recall card after refresh or reopen.
                        handler.setRecallJson(recallJson);
                    }
                    String rawMessage = recall.getMessage();
                    String cliMessage = rawMessage.equals(message)
                            ? commandExpander.expandIfCommand(s.getWorkingDir(), message)
                            : rawMessage;
                    if (historySnapshot != null) {
                        cliMessage = buildHistoryPrefix(historySnapshot, cliMessage);
                        log.info("session {} resumed after truncate: injecting history prefix with {} prior messages",
                                sessionId, historySnapshot.size());
                    }
                    cliMessage = appendFinalAnswerInstruction(cliMessage);
                    gateway.runStream(s.getAgentType(), s.getWorkingDir(), cliMessage, sessionId, effectiveResumeId, effectiveEnv,
                            0L,
                            handler.onChunk(sseSender(emitter, "chunk")),
                            handler.onExit(sseExitConsumer(emitter, keepAlive)),
                            s.getUserId());
                } catch (Exception ex) {
                    log.error("chat-stream-failed sessionId={} agentType={}", sessionId, s.getAgentType(), ex);
                    keepAlive.cancel(false);
                    trySend(emitter, "error", ex.getMessage());
                    emitter.completeWithError(ex);
                }
            }
        }));
        return emitter;
    }

    /** 绑定 SSE 生命周期日志：客户端断开 / 超时 / 异常分别打点。 */
    private void bindEmitterLifecycle(SseEmitter emitter, final String sessionId) {
        emitter.onCompletion(() -> log.debug("chat-stream-emitter-completed sessionId={}", sessionId));
        emitter.onTimeout(() -> log.warn("chat-stream-emitter-timeout sessionId={}", sessionId));
        emitter.onError(t -> log.warn("chat-stream-emitter-error sessionId={} reason={}",
                sessionId, t == null ? "" : t.getMessage()));
    }

    private Optional<String> createRecallAttempt(String sessionId, long userMessageId, String message,
                                                 boolean recallEnabled, String env) {
        if (recallObservationRecorder.isEmpty()) {
            return Optional.empty();
        }
        RecallStatus status = RecallStatus.PENDING;
        String skipReason = null;
        if (!recallEnabled) {
            status = RecallStatus.SKIPPED;
            skipReason = "DISABLED_BY_CLIENT";
        } else if (chatRagRecaller.isEmpty()) {
            status = RecallStatus.SKIPPED;
            skipReason = "REFINERY_UNAVAILABLE";
        }
        try {
            return recallObservationRecorder.get().tryCreateStart(new RecallObservationStart(
                    sessionId, userMessageId, message, recallEnabled, env, status, skipReason));
        } catch (RuntimeException e) {
            log.warn("chat-recall-attempt-create-port-failed sessionId={} userMessageId={} reason={}",
                    sessionId, userMessageId, e.getMessage());
            return Optional.empty();
        }
    }

    private void safeRecordRecallTrace(String attemptId, RecallTrace trace) {
        if (recallObservationRecorder.isEmpty()) {
            return;
        }
        try {
            recallObservationRecorder.get().tryRecordTrace(attemptId, trace);
        } catch (RuntimeException e) {
            log.warn("chat-recall-trace-record-port-failed attemptId={} reason={}", attemptId, e.getMessage());
        }
    }

    private void safeAttachAssistantMessage(String attemptId, long msgId) {
        if (recallObservationRecorder.isEmpty()) {
            return;
        }
        try {
            recallObservationRecorder.get().tryAttachAssistantMessage(attemptId, msgId);
        } catch (RuntimeException e) {
            log.warn("chat-recall-assistant-attach-port-failed attemptId={} msgId={} reason={}",
                    attemptId, msgId, e.getMessage());
        }
    }

    /**
     * 用服务端持久化的 resume_id 决定实际续聊线程。DB 端已清空时,忽略浏览器残留的旧值。
     */
    private String resolveEffectiveResumeId(String sessionId, String requestResumeId, ChatSession fresh) {
        String dbResumeId = trimToNull(fresh == null ? null : fresh.getResumeId());
        String clientResumeId = trimToNull(requestResumeId);
        if (dbResumeId != null) {
            if (clientResumeId != null && !dbResumeId.equals(clientResumeId)) {
                log.info("chat-resume-id-overridden sessionId={} requestResumeId={} dbResumeId={}",
                        sessionId, clientResumeId, dbResumeId);
            }
            return dbResumeId;
        }
        if (clientResumeId != null) {
            log.info("chat-resume-id-ignored-cleared sessionId={} requestResumeId={}",
                    sessionId, clientResumeId);
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 仅当实际 resumeId 为空, 且 session 在 DB 中历史消息非空时, 返回历史快照供拼接为单条 user message 前缀;
     * 否则返回 null。
     */
    private List<ChatMessage> resolveHistoryForPrefix(ChatSession fresh, String effectiveResumeId) {
        if (effectiveResumeId != null || fresh == null) {
            return null;
        }
        List<ChatMessage> messages = fresh.getMessages();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return new ArrayList<ChatMessage>(messages);
    }

    private String appendFinalAnswerInstruction(String message) {
        if (chatProperties == null || !chatProperties.isFinalAnswerInstructionEnabled()) {
            return message;
        }
        String instruction = chatProperties.getFinalAnswerInstruction();
        if (instruction == null || instruction.trim().isEmpty()) {
            return message;
        }
        return (message == null ? "" : message) + "\n\n---\n" + instruction.trim();
    }

    /** 注册周期性 SSE ping, 返回的 future 由流结束/异常时取消。 */
    private ScheduledFuture<?> scheduleKeepAlive(final SseEmitter emitter) {
        return keepAliveScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                trySend(emitter, "ping", "");
            }
        }, KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 把归一化 chunk 以指定事件名推送给前端。 */
    private Consumer<String> sseSender(final SseEmitter emitter, final String eventName) {
        return new Consumer<String>() {
            @Override
            public void accept(String data) {
                trySend(emitter, eventName, data);
            }
        };
    }

    /** 流结束回调: 先停 keepalive, 再发 exit 并 complete。 */
    private IntConsumer sseExitConsumer(final SseEmitter emitter, final ScheduledFuture<?> keepAlive) {
        return new IntConsumer() {
            @Override
            public void accept(int code) {
                keepAlive.cancel(false);
                trySend(emitter, "exit", String.valueOf(code));
                emitter.complete();
            }
        };
    }

    /** Serializes the public recall payload without chunk id, score, or attempt id. */
    private String recallEventJson(RecallOutcome recall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", recall.getQuery());
        payload.put("status", "HIT");
        payload.put("hits", recall.getHits());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("chat-recall-event-serialize-failed reason={}", e.getMessage());
            return "{\"query\":null,\"hits\":[]}";
        }
    }

    /** SSE 发送统一吞异常: emitter 已 complete 或客户端断开都不应中断流程。 */
    private void trySend(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception ex) {
            // emitter 已关闭或客户端断开；只在 DEBUG 记录避免刷屏
            if (log.isDebugEnabled()) {
                log.debug("chat-sse-send-failed eventName={} reason={}", eventName, ex.getMessage());
            }
        }
    }

    @Override
    public void stopSession(String sessionId) {
        log.info("chat-stop-stream sessionId={}", sessionId);
        gateway.stopStream(sessionId);
    }

    @Override
    public boolean isSessionRunning(String sessionId) {
        return gateway.isRunning(sessionId);
    }

    @Override
    public List<SlashCommand> listCommands(String sessionId) {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return commandExpander.listCommands(s.getWorkingDir());
    }

    @Override
    public TruncateResult truncateFrom(String sessionId, long fromId) {
        ChatSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        String prefillContent = "";
        for (ChatMessage msg : s.getMessages()) {
            if (msg.getId() != null && msg.getId().longValue() == fromId) {
                if ("user".equals(msg.getRole())) {
                    prefillContent = msg.getContent();
                }
                break;
            }
        }
        boolean hadResumeId = s.getResumeId() != null && !s.getResumeId().isEmpty();
        int deleted = sessionRepository.truncateFrom(sessionId, fromId);
        recallObservationRecorder.ifPresent(r -> r.tryDeleteByMessageRange(sessionId, fromId));
        // cache 内部 ChatSession 对象的 messages / resumeId 已和持久化不一致, 必须失效让下次重读
        sessionCache.remove(sessionId);
        log.info("session {} truncated from id={}: {} messages deleted, resumeId cleared={}, jsonl on CLI side left untouched",
                sessionId, fromId, deleted, hadResumeId);
        return new TruncateResult(deleted, prefillContent, hadResumeId);
    }

    /**
     * 拼接历史消息为单条 user message 的前缀, 用于截断后重开场景下喂给 CLI。
     * 给 LLM 看用 XML fence;assistant 消息须先 extractPlainText 剥离 stream-json (单条可达数十万字符)。
     */
    private String buildHistoryPrefix(List<ChatMessage> history, String currentMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<conversation_history>\n");
        sb.append("The following is prior conversation context from a previous session. ");
        sb.append("Please consider it as background and respond only to the new user message at the bottom.\n\n");
        for (ChatMessage msg : history) {
            boolean isUser = "user".equals(msg.getRole());
            String text = isUser ? msg.getContent() : outputExtractor.extractPlainText(msg.getContent());
            if (text == null || text.isEmpty()) {
                continue;
            }
            sb.append("[").append(msg.getRole()).append("]: ").append(text).append("\n\n");
        }
        sb.append("</conversation_history>\n\n");
        sb.append("<new_user_message>\n").append(currentMessage).append("\n</new_user_message>");
        return sb.toString();
    }

    @Override
    public Feedback saveFeedback(String sessionId, String rating, String comment) {
        ChatSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            log.warn("chat-feedback-rejected reason=session-not-found sessionId={}", sessionId);
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Feedback feedback = new Feedback(parseRating(rating), normalizeComment(comment), java.time.Instant.now());
        sessionRepository.saveFeedback(sessionId, feedback);
        log.info("chat-feedback-saved sessionId={} rating={} hasComment={}",
                sessionId, feedback.getRating(), feedback.getComment() != null);
        return feedback;
    }

    @Override
    public Feedback getFeedback(String sessionId) {
        ChatSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return s.getFeedback();
    }

    @Override
    public String shareSession(String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        String token = sessionRepository.setShareToken(sessionId, ShareToken.generate());
        log.info("chat-session-shared sessionId={}", sessionId);
        return token;
    }

    /** 评分字符串转枚举:null/空白 → null(未评分);非法值 → IllegalArgumentException。 */
    private FeedbackRating parseRating(String rating) {
        if (rating == null || rating.trim().isEmpty()) {
            return null;
        }
        try {
            return FeedbackRating.valueOf(rating.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid feedback rating: " + rating);
        }
    }

    /** 备注归一化:null 或纯空白统一为 null,否则去除首尾空白。 */
    private String normalizeComment(String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            return null;
        }
        return comment.trim();
    }

    @Override
    public void deleteSession(String sessionId) {
        // 顺序:1)取 workingDir 用于清图/清附件 2)清缓存 3)删持久化。
        // session 已不存在(脏数据/重复调用)时仍执行 2/3,保证幂等。
        ChatSession s = sessionRepository.findById(sessionId);
        if (s != null) {
            // 删除权限收紧: 仅创建者(或无归属老数据)可删, 删他人会话直接抛 403, 不动任何数据。
            // 与可见性隔离开关无关——可见性可全开, 删除按创建者归属把关。
            s.requireDeletableBy(currentUserProvider.currentUserId());
            uploadPicStore.deleteSessionImages(s.getWorkingDir(), sessionId);
            uploadFileStore.deleteSessionFiles(s.getWorkingDir(), sessionId);
        } else {
            log.warn("chat-session-delete-no-record sessionId={} 已无持久化记录,跳过图片/附件清理", sessionId);
        }
        sessionCache.remove(sessionId);
        sessionRepository.deleteById(sessionId);
        recallObservationRecorder.ifPresent(r -> r.tryDeleteBySessionId(sessionId));
    }
}
