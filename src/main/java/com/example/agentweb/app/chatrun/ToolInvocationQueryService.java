package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chat.ChatSessionNotFoundException;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolInvocationQueryService {
    private static final int MAX_PAGE_SIZE = 200;
    private final ToolInvocationRepository repository;
    private final SessionRepository sessionRepository;

    public ToolInvocationQueryService(ToolInvocationRepository repository,
                                      SessionRepository sessionRepository) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
    }

    public List<ToolInvocation> findBySession(String sessionId, int limit, int offset) {
        if (sessionRepository.findById(sessionId) == null) {
            throw new ChatSessionNotFoundException(sessionId);
        }
        return repository.findBySessionId(sessionId, pageSize(limit), Math.max(0, offset));
    }

    public List<ToolInvocation> findByRun(String runId, int limit, int offset) {
        List<ToolInvocation> rows = repository.findByRunId(runId, pageSize(limit), Math.max(0, offset));
        if (!rows.isEmpty() && sessionRepository.findById(rows.get(0).getSessionId()) == null) {
            return java.util.Collections.emptyList();
        }
        return rows;
    }

    private int pageSize(int requested) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, requested));
    }
}
