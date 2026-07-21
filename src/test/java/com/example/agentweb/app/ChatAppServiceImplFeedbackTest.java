package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.AgentTypeResolver;
import com.example.agentweb.infra.UploadFileStore;
import com.example.agentweb.infra.UploadPicStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-20
 */
public class ChatAppServiceImplFeedbackTest {

    private SessionRepository sessionRepository;
    private ChatAppServiceImpl service;

    @BeforeEach
    public void setUp() {
        sessionRepository = mock(SessionRepository.class);
        SessionCache sessionCache = mock(SessionCache.class);
        AgentGateway gateway = mock(AgentGateway.class);
        Executor agentExecutor = mock(Executor.class);
        SlashCommandExpander commandExpander = mock(SlashCommandExpander.class);
        StreamOutputExtractor outputExtractor = mock(StreamOutputExtractor.class);
        AgentTypeResolver agentTypeResolver = mock(AgentTypeResolver.class);
        UploadPicStore uploadPicStore = mock(UploadPicStore.class);
        UploadFileStore uploadFileStore = mock(UploadFileStore.class);
        service = new ChatAppServiceImpl(sessionCache, sessionRepository, gateway, agentExecutor,
                commandExpander, outputExtractor, agentTypeResolver, uploadPicStore, uploadFileStore,
                java.util.Optional.empty(),
                new com.example.agentweb.domain.auth.CurrentUserProvider(() -> java.util.Optional.empty()));
    }

    private ChatSession session(String id) {
        return new ChatSession(id, AgentType.CLAUDE, "/tmp/wd", Instant.now(), new ArrayList<>());
    }

    @Test
    public void saveFeedback_session_not_found_throws_and_does_not_persist() {
        when(sessionRepository.findById("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.saveFeedback("missing", "CORRECT", null));
        verify(sessionRepository, never()).saveFeedback(anyString(), any());
    }

    @Test
    public void saveFeedback_valid_rating_with_comment_assembles_feedback_and_persists() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        Feedback saved = service.saveFeedback("s1", "CORRECT", "  分析到位  ");

        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(sessionRepository).saveFeedback(eq("s1"), captor.capture());
        Feedback persisted = captor.getValue();
        assertEquals(FeedbackRating.CORRECT, persisted.getRating());
        assertEquals("分析到位", persisted.getComment());
        assertNotNull(persisted.getUpdatedAt());
        assertSame(persisted, saved);
    }

    @Test
    public void saveFeedback_null_rating_writes_only_comment_with_rating_null() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        Feedback saved = service.saveFeedback("s1", null, "只留文字反馈");

        assertNull(saved.getRating());
        assertEquals("只留文字反馈", saved.getComment());
    }

    @Test
    public void saveFeedback_blank_rating_string_normalizes_rating_to_null() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        Feedback saved = service.saveFeedback("s1", "   ", null);

        assertNull(saved.getRating());
        assertNull(saved.getComment());
    }

    @Test
    public void saveFeedback_blank_comment_normalizes_to_null() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        Feedback saved = service.saveFeedback("s1", "INCORRECT", "   ");

        assertEquals(FeedbackRating.INCORRECT, saved.getRating());
        assertNull(saved.getComment());
    }

    @Test
    public void saveFeedback_invalid_rating_throws_and_does_not_persist() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        assertThrows(IllegalArgumentException.class,
                () -> service.saveFeedback("s1", "BANANA", null));
        verify(sessionRepository, never()).saveFeedback(anyString(), any());
    }

    @Test
    public void getFeedback_session_not_found_throws() {
        when(sessionRepository.findById("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getFeedback("missing"));
    }

    @Test
    public void getFeedback_session_already_rated_returns_that_feedback() {
        ChatSession s = session("s1");
        Feedback fb = new Feedback(FeedbackRating.PARTIALLY_CORRECT, "部分对", Instant.now());
        s.setFeedback(fb);
        when(sessionRepository.findById("s1")).thenReturn(s);

        assertSame(fb, service.getFeedback("s1"));
    }

    @Test
    public void getFeedback_session_not_rated_returns_null() {
        when(sessionRepository.findById("s1")).thenReturn(session("s1"));

        assertNull(service.getFeedback("s1"));
    }
}
