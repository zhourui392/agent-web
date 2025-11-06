package com.example.agentweb.infra;

import com.example.agentweb.domain.ChatSession;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemorySessionRepo {
    private final Map<String, ChatSession> store = new ConcurrentHashMap<String, ChatSession>();

    public void save(ChatSession s) { store.put(s.getId(), s); }

    public ChatSession find(String id) { return store.get(id); }
}
