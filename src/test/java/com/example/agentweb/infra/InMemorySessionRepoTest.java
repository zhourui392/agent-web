package com.example.agentweb.infra;

import com.example.agentweb.config.ChatProperties;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link InMemorySessionRepo} 有界 LRU 缓存测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class InMemorySessionRepoTest {

    @Test
    void save_should_evictLeastRecentlyUsedEntryAboveLimit() {
        ChatProperties properties = new ChatProperties();
        properties.setSessionCacheMaxEntries(2);
        InMemorySessionRepo repository = new InMemorySessionRepo(properties);
        ChatSession first = session();
        ChatSession second = session();
        ChatSession third = session();

        repository.save(first);
        repository.save(second);
        assertNotNull(repository.find(first.getId()));
        repository.save(third);

        assertNotNull(repository.find(first.getId()));
        assertNull(repository.find(second.getId()));
        assertNotNull(repository.find(third.getId()));
    }

    private ChatSession session() {
        return new ChatSession(AgentType.CODEX, "/tmp");
    }
}
