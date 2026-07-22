package com.example.agentweb.domain.chatrun;

/**
 * Repository-backed cross-aggregate guard for destructive session mutations.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class RepositoryChatRunActivityGuard implements ChatRunActivityGuard {

    private final ChatRunRepository runRepository;
    private final ChatExecutionActivityProbe executionActivityProbe;

    public RepositoryChatRunActivityGuard(ChatRunRepository runRepository) {
        this(runRepository, ChatExecutionActivityProbe.inactive());
    }

    public RepositoryChatRunActivityGuard(ChatRunRepository runRepository,
                                          ChatExecutionActivityProbe executionActivityProbe) {
        if (runRepository == null) {
            throw new IllegalArgumentException("chat run repository must not be null");
        }
        if (executionActivityProbe == null) {
            throw new IllegalArgumentException("execution activity probe must not be null");
        }
        this.runRepository = runRepository;
        this.executionActivityProbe = executionActivityProbe;
    }

    @Override
    public void requireInactive(String sessionId) {
        if (runRepository.findActiveBySessionId(sessionId).isPresent()
                || executionActivityProbe.isExecutionActive(sessionId)) {
            throw new ActiveChatRunExistsException(sessionId);
        }
    }
}
