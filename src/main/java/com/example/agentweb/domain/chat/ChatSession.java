package com.example.agentweb.domain.chat;

import com.example.agentweb.domain.shared.AgentType;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    @JsonCreator
    public ChatSession(@JsonProperty("id") String id,
                @JsonProperty("agentType") AgentType agentType,
                @JsonProperty("workingDir") String workingDir,
                @JsonProperty("createdAt") Instant createdAt,
                @JsonProperty("messages") List<ChatMessage> messages) {
        this.id = id;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.createdAt = createdAt;
        this.messages = messages != null ? new ArrayList<ChatMessage>(messages) : new ArrayList<ChatMessage>();
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

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }
}
