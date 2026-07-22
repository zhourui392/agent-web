package com.example.agentweb.domain.chatrun;

/**
 * Repository-backed cross-aggregate guard for destructive session mutations.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class RepositoryChatRunActivityGuard implements ChatRunActivityGuard {

    private final ChatRunRepository runRepository;

    public RepositoryChatRunActivityGuard(ChatRunRepository runRepository) {
        if (runRepository == null) {
            throw new IllegalArgumentException("chat run repository must not be null");
        }
        this.runRepository = runRepository;
    }

    @Override
    public void requireInactive(String sessionId) {
        if (runRepository.findActiveBySessionId(sessionId).isPresent()) {
            throw new ActiveChatRunExistsException(sessionId);
        }
    }
}
