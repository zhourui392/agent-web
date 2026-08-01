package com.example.agentweb.domain.chatrun;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 校验持久化 Session 种类与 Run 来源之间的一致性。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RunSessionOriginPolicy {

    private static final Map<SessionKind, RunOrigin> EXPECTED_ORIGINS = expectedOrigins();

    private RunSessionOriginPolicy() {
    }

    public static void requireCompatible(ChatSession session, ChatRun run) {
        if (session == null || run == null) {
            throw new IllegalArgumentException("chat session and run are required");
        }
        RunOrigin expectedOrigin = EXPECTED_ORIGINS.get(session.getSessionKind());
        boolean matchingSession = Objects.equals(session.getId(), run.getSessionId());
        boolean matchingOrigin = expectedOrigin != null
                && run.getRunOrigin() == expectedOrigin;
        if (!matchingSession || !matchingOrigin) {
            throw new ChatRunNotFoundException(run.getId().getValue());
        }
    }

    private static Map<SessionKind, RunOrigin> expectedOrigins() {
        Map<SessionKind, RunOrigin> origins =
                new EnumMap<SessionKind, RunOrigin>(SessionKind.class);
        origins.put(SessionKind.CHAT, RunOrigin.CHAT);
        origins.put(SessionKind.WORKBENCH_PHASE, RunOrigin.WORKBENCH);
        return Collections.unmodifiableMap(origins);
    }
}
