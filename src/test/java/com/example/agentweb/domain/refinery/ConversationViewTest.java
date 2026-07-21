package com.example.agentweb.domain.refinery;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
class ConversationViewTest {

    @Test
    void builder_should_return_view_with_required_fields() {
        ConversationView view = sampleBuilder().build();

        assertEquals("sess-1", view.getSourceId());
        assertEquals(SourceType.CHAT, view.getSourceType());
        assertEquals(AgentType.CLAUDE, view.getAgentType());
        assertEquals("/tmp", view.getWorkingDir());
        assertEquals("unknown", view.getEnv());
        assertEquals(VerdictSignal.NONE, view.getVerdict());
        assertEquals(1, view.getTurns().size());
    }

    @Test
    void builder_should_default_env_to_unknown_when_omitted() {
        ConversationView view = sampleBuilder().env(null).build();
        assertEquals("unknown", view.getEnv());
    }

    @Test
    void builder_should_default_verdict_to_none_when_omitted() {
        ConversationView view = sampleBuilder().verdict(null).build();
        assertEquals(VerdictSignal.NONE, view.getVerdict());
    }

    @Test
    void builder_should_reject_null_source_id() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().sourceId(null).build());
        assertTrue(ex.getMessage().contains("sourceId"));
    }

    @Test
    void builder_should_reject_blank_source_id() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().sourceId("   ").build());
    }

    @Test
    void builder_should_reject_null_source_type() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().sourceType(null).build());
    }

    @Test
    void builder_should_reject_null_agent_type() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().agentType(null).build());
    }

    @Test
    void builder_should_reject_null_working_dir() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().workingDir(null).build());
    }

    @Test
    void builder_should_reject_empty_turns() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().turns(Collections.<ConversationTurn>emptyList()).build());
    }

    @Test
    void builder_should_reject_null_turns_list() {
        assertThrows(IllegalArgumentException.class,
                () -> sampleBuilder().turns(null).build());
    }

    @Test
    void turns_should_be_immutable() {
        List<ConversationTurn> mutable = new java.util.ArrayList<>();
        mutable.add(new ConversationTurn("user", "hi", Instant.now()));
        ConversationView view = sampleBuilder().turns(mutable).build();

        assertThrows(UnsupportedOperationException.class,
                () -> view.getTurns().add(new ConversationTurn("user", "x", Instant.now())));
    }

    @Test
    void same_builder_instance_can_chain_all_setters() {
        ConversationView view = ConversationView.builder()
                .sourceId("d-1")
                .sourceType(SourceType.DIAGNOSE)
                .agentType(AgentType.CODEX)
                .workingDir("/work")
                .env("prod")
                .verdict(VerdictSignal.POSITIVE)
                .turns(Arrays.asList(new ConversationTurn("user", "hello", Instant.EPOCH)))
                .build();

        assertEquals(SourceType.DIAGNOSE, view.getSourceType());
        assertEquals("prod", view.getEnv());
        assertEquals(VerdictSignal.POSITIVE, view.getVerdict());
    }

    private ConversationView.Builder sampleBuilder() {
        return ConversationView.builder()
                .sourceId("sess-1")
                .sourceType(SourceType.CHAT)
                .agentType(AgentType.CLAUDE)
                .workingDir("/tmp")
                .turns(Arrays.asList(new ConversationTurn("user", "hello", Instant.now())));
    }
}
