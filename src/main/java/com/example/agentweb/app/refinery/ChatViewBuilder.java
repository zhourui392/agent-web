package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.ConversationTurn;
import com.example.agentweb.domain.refinery.ConversationView;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.refinery.VerdictSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chat 来源的 {@link ConversationView} builder.
 *
 * <p>从 {@link SessionRepository} 加载 {@link ChatSession} 后, 映射为子域可消费的值对象.
 * 缺失或空消息会议返回 {@code Optional.empty()}, 让调用方安全跳过.</p>
 *
 * <p>env 字段直接来自 session.env (可空); 为空时由 {@link ConversationView} 走默认值 "unknown".
 * verdict 永远 {@link VerdictSignal#NONE} —— 聊天暂无反馈闭环.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
@Component
public class ChatViewBuilder {

    private final SessionRepository sessionRepo;

    public ChatViewBuilder(SessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public Optional<ConversationView> build(String sessionId) {
        ChatSession session = sessionRepo.findById(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        List<ChatMessage> messages = session.getMessages();
        if (messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConversationView.builder()
                .sourceId(session.getId())
                .sourceType(SourceType.CHAT)
                .agentType(session.getAgentType())
                .workingDir(session.getWorkingDir())
                .env(session.getEnv())
                .verdict(VerdictSignal.NONE)
                .turns(toTurns(messages))
                .build());
    }

    private static List<ConversationTurn> toTurns(List<ChatMessage> messages) {
        List<ConversationTurn> turns = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            turns.add(new ConversationTurn(msg.getRole(), msg.getContent(), msg.getTimestamp()));
        }
        return turns;
    }
}
