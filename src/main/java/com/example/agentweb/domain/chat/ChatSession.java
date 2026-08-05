package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root representing a chat session bound to a working directory and an agent type.
 * @author zhourui(V33215020)
 */
public class ChatSession {
    @Getter
    private final String id;
    @Getter
    private final AgentType agentType;
    @Getter
    private final String workingDir;
    @Getter
    private final Instant createdAt;
    @Getter
    private final SessionKind sessionKind;
    @Getter
    private final String contextId;
    @Getter
    private Instant retiredAt;
    private final List<ChatMessage> messages;
    @Getter @Setter
    private String resumeId;
    @Getter @Setter
    private String title;
    /** 对话绑定的环境 key (如 test/prod), 与 env.yml 配置对应; 为空表示无环境约束 */
    @Getter @Setter
    private String env;
    /** 发起会话时的客户端来源 IP, 供审计归因; 为空表示未采集 */
    @Getter @Setter
    private String clientIp;
    /** 创建会话的登录用户标识；为空表示老数据或系统创建，全员可见。 */
    @Getter @Setter
    private String userId;
    /** 创建会话的登录用户姓名，仅作审计记录；为空表示老数据或无法获取。 */
    @Getter @Setter
    private String userName;
    /** 用户对该会话 AI 分析正确性的反馈; 从未评价过为 null */
    @Getter @Setter
    private Feedback feedback;

    public ChatSession(AgentType agentType, String workingDir) {
        this(UUID.randomUUID().toString(), agentType, workingDir, Instant.now(), new ArrayList<ChatMessage>());
    }

    public static ChatSession forTask(String taskName, AgentType agentType, String workingDir) {
        ChatSession s = new ChatSession(agentType, workingDir);
        s.setTitle("Task-" + taskName);
        return s;
    }

    public ChatSession(String id, AgentType agentType, String workingDir,
                       Instant createdAt, List<ChatMessage> messages) {
        this(id, agentType, workingDir, createdAt, messages, SessionKind.CHAT, null, null);
    }

    @JsonCreator
    public ChatSession(@JsonProperty("id") String id,
                @JsonProperty("agentType") AgentType agentType,
                @JsonProperty("workingDir") String workingDir,
                @JsonProperty("createdAt") Instant createdAt,
                @JsonProperty("messages") List<ChatMessage> messages,
                @JsonProperty("sessionKind") SessionKind sessionKind,
                @JsonProperty("contextId") String contextId,
                @JsonProperty("retiredAt") Instant retiredAt) {
        this.id = id;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.createdAt = DomainText.requireTime(createdAt, "session created at");
        this.sessionKind = sessionKind == null ? SessionKind.CHAT : sessionKind;
        this.contextId = requireConsistentContext(this.sessionKind, contextId);
        this.retiredAt = requireConsistentRetirement(this.sessionKind, this.createdAt, retiredAt);
        this.messages = messages != null ? new ArrayList<ChatMessage>(messages) : new ArrayList<ChatMessage>();
    }

    /**
     * 由可信服务端事实创建动态 Workbench Stage 会话。
     */
    public static ChatSession createWorkbenchStage(
            String id, AgentType agentType, String workingDir,
            String contextId, String ownerId, String ownerName,
            Instant now) {
        return createWorkbenchConversation(
                id, agentType, workingDir, contextId, ownerId, ownerName, now);
    }

    private static ChatSession createWorkbenchConversation(
            String id, AgentType agentType, String workingDir,
            String contextId, String ownerId, String ownerName,
            Instant now) {
        String stableId = DomainText.require(id, "session id", 128);
        if (agentType == null) {
            throw new IllegalArgumentException("agent type must not be null");
        }
        String resolvedWorkingDir = DomainText.require(workingDir, "working directory", 4096);
        String stageContextId = DomainText.require(
                contextId, "session context id", 512);
        String resolvedOwnerId = DomainText.require(ownerId, "session owner id", 128);
        String resolvedOwnerName = DomainText.require(ownerName, "session owner name", 256);
        Instant createdAt = DomainText.requireTime(now, "session created at");
        ChatSession session = new ChatSession(
                stableId, agentType, resolvedWorkingDir, createdAt, null,
                SessionKind.WORKBENCH_STAGE, stageContextId, null);
        session.setUserId(resolvedOwnerId);
        session.setUserName(resolvedOwnerName);
        return session;
    }

    /**
     * 退役 Workbench Stage 会话并保留只读历史；重复退役不改写首次时间。
     *
     * @return 本次是否首次退役
     */
    public boolean retire(Instant now) {
        if (sessionKind == SessionKind.CHAT) {
            throw new IllegalStateException(
                    "Only Workbench conversations can be retired");
        }
        Instant retirementTime = DomainText.requireTime(now, "session retired at");
        if (retirementTime.isBefore(createdAt)) {
            throw new IllegalArgumentException("session retired at must not be before created at");
        }
        if (retiredAt != null) {
            return false;
        }
        retiredAt = retirementTime;
        return true;
    }

    /**
     * 要求当前聚合属于普通 Chat 边界；其他来源统一伪装为会话不存在。
     */
    public void requireOrdinaryChat() {
        if (sessionKind != SessionKind.CHAT) {
            throw new ChatSessionNotFoundException(id);
        }
    }

    /**
     * 一次核验动态 Stage Conversation 的全部稳定事实。
     */
    public void requireActiveWorkbenchStage(
            String expectedSessionId, AgentType expectedAgentType,
            String expectedWorkingDir, String expectedEnvironment,
            String expectedContextId, String expectedOwnerId,
            String expectedOwnerName, Instant expectedCreatedAt) {
        requireActiveWorkbenchConversation(
                expectedSessionId,
                expectedAgentType, expectedWorkingDir, expectedEnvironment,
                expectedContextId, expectedOwnerId, expectedOwnerName,
                expectedCreatedAt);
    }

    private void requireActiveWorkbenchConversation(
            String expectedSessionId,
            AgentType expectedAgentType, String expectedWorkingDir,
            String expectedEnvironment, String expectedContextId,
            String expectedOwnerId, String expectedOwnerName,
            Instant expectedCreatedAt) {
        String stableSessionId = DomainText.require(
                expectedSessionId, "expected session id", 128);
        if (expectedAgentType == null) {
            throw new IllegalArgumentException("expected agent type must not be null");
        }
        String stableWorkingDir = DomainText.require(
                expectedWorkingDir, "expected working directory", 4096);
        String stableContextId = DomainText.require(
                expectedContextId, "expected session context id", 512);
        String stableOwnerId = DomainText.require(expectedOwnerId, "expected owner id", 128);
        String stableOwnerName = DomainText.require(expectedOwnerName, "expected owner name", 256);
        Instant stableCreatedAt = DomainText.requireTime(
                expectedCreatedAt, "expected session created at");
        if (sessionKind != SessionKind.WORKBENCH_STAGE
                || retiredAt != null
                || !Objects.equals(id, stableSessionId)
                || agentType != expectedAgentType
                || !Objects.equals(workingDir, stableWorkingDir)
                || !Objects.equals(env, expectedEnvironment)
                || !Objects.equals(contextId, stableContextId)
                || !Objects.equals(userId, stableOwnerId)
                || !Objects.equals(userName, stableOwnerName)
                || !createdAt.equals(stableCreatedAt)) {
            throw new IllegalStateException(
                    "Workbench conversation facts do not match");
        }
    }

    private static String requireConsistentContext(SessionKind sessionKind, String contextId) {
        if (sessionKind == SessionKind.CHAT) {
            if (contextId != null) {
                throw new IllegalArgumentException("Chat session context id must be null");
            }
            return null;
        }
        return DomainText.require(
                contextId, "Workbench conversation context id", 512);
    }

    private static Instant requireConsistentRetirement(SessionKind sessionKind,
                                                       Instant createdAt,
                                                       Instant retiredAt) {
        if (sessionKind == SessionKind.CHAT && retiredAt != null) {
            throw new IllegalArgumentException("Chat session retired at must be null");
        }
        if (retiredAt != null && retiredAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("session retired at must not be before created at");
        }
        return retiredAt;
    }

    public void addMessage(String role, String content) {
        messages.add(new ChatMessage(role, content));
    }

    /**
     * 删除权限校验：仅创建者可删除自己的会话；无归属的老数据/公共会话(userId 为 null)允许任意用户删除；
     * 删他人会话抛 {@link SessionDeletionForbiddenException}。本规则独立于可见性隔离开关——
     * 会话可全员可见，但删除始终按创建者归属收紧。
     *
     * @param currentUserId 当前登录用户标识；为空表示无登录上下文
     */
    public void requireDeletableBy(String currentUserId) {
        if (userId != null && !userId.equals(currentUserId)) {
            throw new SessionDeletionForbiddenException(id, currentUserId);
        }
    }

    /**
     * 根据截断起点生成重开会话所需的语义信息。
     *
     * <p>只有命中 user 消息时才将其内容带回编辑框；resumeId 是否存在也由聚合统一解释，
     * 避免应用层遍历内部消息或根据 getter 重组业务含义。</p>
     *
     * @param fromId 截断起点消息 ID
     * @return 截断领域计划
     */
    public ChatSessionTruncation planTruncationFrom(long fromId) {
        String prefillContent = "";
        for (ChatMessage message : messages) {
            if (isMessage(message, fromId)) {
                prefillContent = userContentOrEmpty(message);
                break;
            }
        }
        return new ChatSessionTruncation(prefillContent, resumeId != null && !resumeId.isEmpty());
    }

    private boolean isMessage(ChatMessage message, long messageId) {
        return message.getId() != null && message.getId().longValue() == messageId;
    }

    private String userContentOrEmpty(ChatMessage message) {
        return "user".equals(message.getRole()) ? message.getContent() : "";
    }

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }
}
