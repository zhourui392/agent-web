package com.example.agentweb.infra;

import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionCache;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemorySessionRepo implements SessionCache {
    private final Map<String, ChatSession> store = new ConcurrentHashMap<String, ChatSession>();

    public void save(ChatSession s) { store.put(s.getId(), s); }

    public ChatSession find(String id) { return store.get(id); }

    public void remove(String id) { store.remove(id); }
}
