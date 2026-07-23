package com.example.agentweb.infra;

import com.example.agentweb.config.ChatProperties;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionCache;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * @author zhourui(V33215020)
 */
@Repository
public class InMemorySessionRepo implements SessionCache {
    private final Map<String, ChatSession> store;

    public InMemorySessionRepo(ChatProperties properties) {
        final int maxEntries = Math.max(1, properties.getSessionCacheMaxEntries());
        this.store = Collections.synchronizedMap(new LinkedHashMap<String, ChatSession>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ChatSession> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public void save(ChatSession s) { store.put(s.getId(), s); }

    public ChatSession find(String id) { return store.get(id); }

    public void remove(String id) { store.remove(id); }
}
