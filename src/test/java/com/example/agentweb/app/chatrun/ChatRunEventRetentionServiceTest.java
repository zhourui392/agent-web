package com.example.agentweb.app.chatrun;

import com.example.agentweb.config.ResumableChatStreamProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunEventRetentionServiceTest {

    @Test
    void purge_should_delete_terminal_events_before_old_metadata_in_small_batches() {
        Instant now = Instant.parse("2026-07-22T10:00:00Z");
        ChatRunEventStore eventStore = mock(ChatRunEventStore.class);
        ChatRunRetentionStore retentionStore = mock(ChatRunRetentionStore.class);
        ResumableChatStreamProperties settings = new ResumableChatStreamProperties();
        settings.setEventRetentionHours(24);
        settings.setRunRetentionDays(30);
        Instant eventCutoff = now.minusSeconds(24L * 60L * 60L);
        Instant runCutoff = now.minusSeconds(30L * 24L * 60L * 60L);
        when(eventStore.deleteBefore(eventCutoff, 5_000)).thenReturn(12);
        when(retentionStore.deleteTerminalRunsBefore(runCutoff, 500)).thenReturn(3);
        ChatRunEventRetentionService service = new ChatRunEventRetentionService(
                eventStore, retentionStore, settings, Clock.fixed(now, ZoneOffset.UTC));

        ChatRunRetentionResult result = service.purgeExpired();

        assertEquals(12, result.getDeletedEvents());
        assertEquals(3, result.getDeletedRuns());
        org.mockito.InOrder order = inOrder(eventStore, retentionStore);
        order.verify(eventStore).deleteBefore(eventCutoff, 5_000);
        order.verify(retentionStore).deleteTerminalRunsBefore(runCutoff, 500);
    }
}
