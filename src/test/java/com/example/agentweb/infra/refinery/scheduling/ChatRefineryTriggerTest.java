package com.example.agentweb.infra.refinery.scheduling;

import com.example.agentweb.app.refinery.RefineryAppService;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.refinery.RagChunkRepository;
import com.example.agentweb.infra.refinery.config.RefineryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@ExtendWith(MockitoExtension.class)
public class ChatRefineryTriggerTest {

    private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Mock private RefineryAppService appService;
    @Mock private SessionRepository sessionRepo;
    @Mock private RagChunkRepository chunkRepo;

    private RefineryProperties props;
    private ChatRefineryTrigger scheduler;

    @BeforeEach
    public void setUp() {
        props = new RefineryProperties();
        props.getPoll().setSilentMinutes(30);
        props.getPoll().setMaxPerTick(5);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new ChatRefineryTrigger(appService, sessionRepo, chunkRepo, props, null, fixedClock);
    }

    @Test
    public void tick_should_compute_threshold_from_silent_minutes_and_call_app_service() {
        when(sessionRepo.findIdsWithLastMessageBefore(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList("sess-1", "sess-2"));

        scheduler.tick();

        ArgumentCaptor<Long> beforeMs = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> sentinel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> maxRetries = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(sessionRepo).findIdsWithLastMessageBefore(
                beforeMs.capture(), sentinel.capture(), maxRetries.capture(), limit.capture());
        long expectedBefore = NOW.minusSeconds(30 * 60).toEpochMilli();
        assertEquals(expectedBefore, beforeMs.getValue().longValue());
        assertEquals(com.example.agentweb.domain.refinery.SessionRefineryState.LAST_ERROR_BELOW_THRESHOLD,
                sentinel.getValue());
        assertEquals(3, maxRetries.getValue().intValue());
        assertEquals(5, limit.getValue().intValue());

        verify(appService).refineAndIngest("sess-1");
        verify(appService).refineAndIngest("sess-2");
    }

    @Test
    public void tick_with_empty_candidates_should_not_call_app_service() {
        when(sessionRepo.findIdsWithLastMessageBefore(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        scheduler.tick();

        verify(appService, never()).refineAndIngest(anyString());
    }

    @Test
    public void tick_when_one_session_throws_should_not_affect_other_sessions() {
        when(sessionRepo.findIdsWithLastMessageBefore(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList("sess-a", "sess-b", "sess-c"));
        when(appService.refineAndIngest("sess-b"))
                .thenThrow(new RuntimeException("network down"));

        scheduler.tick();

        verify(appService).refineAndIngest("sess-a");
        verify(appService).refineAndIngest("sess-b");
        verify(appService).refineAndIngest("sess-c");
    }

    @Test
    public void tick_when_repo_throws_should_swallow_exception() {
        when(sessionRepo.findIdsWithLastMessageBefore(anyLong(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        scheduler.tick();

        verify(appService, never()).refineAndIngest(anyString());
    }
}
