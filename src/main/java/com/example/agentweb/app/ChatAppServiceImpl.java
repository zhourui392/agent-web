package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.ChatSessionTruncation;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chat.ShareToken;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.app.logging.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 聊天应用服务实现，编排会话生命周期、消息收发与对话摘要生成。
 * <p>通过 {@link SessionCache} 做内存快查，{@link SessionRepository} 做持久化，
 * 并委托 {@link AgentGateway} 调用 CLI Agent 完成实际推理。</p>
 * @author zhourui(V33215020)
 */
@Service
@Slf4j
public class ChatAppServiceImpl implements ChatAppService {

    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;
    private final AgentGateway gateway;
    private final SlashCommandExpander commandExpander;
    private final ChatAgentDefaults chatAgentDefaults;
    private final UploadPicStorage uploadPicStore;
    private final UploadFileStorage uploadFileStore;
    private final Optional<RecallObservationRecorder> recallObservationRecorder;
    private final CurrentUserProvider currentUserProvider;
    private WorkspacePathPolicy workspacePathPolicy;
    private ChatRunActivityGuard chatRunActivityGuard = ChatRunActivityGuard.permissive();

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
                              SlashCommandExpander commandExpander,
                              ChatAgentDefaults chatAgentDefaults,
                              UploadPicStorage uploadPicStore,
                              UploadFileStorage uploadFileStore,
                              Optional<RecallObservationRecorder> recallObservationRecorder,
                              CurrentUserProvider currentUserProvider) {
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
        this.gateway = gateway;
        this.commandExpander = commandExpander;
        this.chatAgentDefaults = chatAgentDefaults;
        this.uploadPicStore = uploadPicStore;
        this.uploadFileStore = uploadFileStore;
        this.recallObservationRecorder = recallObservationRecorder;
        this.currentUserProvider = currentUserProvider;
        this.workspacePathPolicy = permissivePathPolicy();
    }

    /**
     * 生产装配注入真实路径策略；独立单测未注入时保留历史构造兼容。
     */
    @Autowired
    void configureWorkspacePathPolicy(WorkspacePathPolicy workspacePathPolicy) {
        this.workspacePathPolicy = workspacePathPolicy;
    }

    /**
     * 生产装配注入活动 run 领域守卫；历史独立单测未涉及新表时保留兼容默认值。
     */
    @Autowired
    void configureChatRunActivityGuard(ChatRunActivityGuard chatRunActivityGuard) {
        this.chatRunActivityGuard = chatRunActivityGuard;
    }

    @Override
    public ChatSession startSession(StartSessionCommand command, String clientIp) {
        Assert.notNull(command, "command is null");
        AgentType type = AgentType.resolveSelection(command.agentType(), chatAgentDefaults.getChatDefaultAgent());
        String workingDir = workspacePathPolicy.requireExistingDirectory(command.workingDir());
        ChatSession s = new ChatSession(type, workingDir);
        // 持久化创建时选定的环境, 用于后续恢复时回填; null/空串均按 "无环境" 处理
        String reqEnv = command.env();
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
    public String sendMessage(String sessionId, SendMessageCommand command) throws IOException, InterruptedException {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            log.warn("chat-send-rejected reason=session-not-found sessionId={}", sessionId);
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        log.info("chat-send-once sessionId={} agentType={} messageLen={}",
                sessionId, s.getAgentType(), LogSafe.safeLen(command.message()));
        // persist user message
        ChatMessage userMsg = new ChatMessage("user", command.message());
        sessionRepository.addMessage(sessionId, userMsg);

        long startMs = System.currentTimeMillis();
        // 注入会话 owner 的 git 身份: 该会话内 agent 的 git commit 用属主身份 (默认用户/未配置回落机器默认)
        String output = gateway.runOnce(s.getAgentType(), s.getWorkingDir(), command.message(), s.getUserId());
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
                // 堵住进程级缓存绕过 SQL 过滤的越权读 (send/listCommands 均经此路径)
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
        chatRunActivityGuard.requireInactive(sessionId);
        ChatSessionTruncation plan = s.planTruncationFrom(fromId);
        int deleted = sessionRepository.truncateFrom(sessionId, fromId);
        recallObservationRecorder.ifPresent(r -> r.tryDeleteByMessageRange(sessionId, fromId));
        // cache 内部 ChatSession 对象的 messages / resumeId 已和持久化不一致, 必须失效让下次重读
        sessionCache.remove(sessionId);
        log.info("session {} truncated from id={}: {} messages deleted, resumeId cleared={}, jsonl on CLI side left untouched",
                sessionId, fromId, deleted, plan.isResumeIdPresent());
        return new TruncateResult(deleted, plan.getPrefillContent(), plan.isResumeIdPresent());
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
            chatRunActivityGuard.requireInactive(sessionId);
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
