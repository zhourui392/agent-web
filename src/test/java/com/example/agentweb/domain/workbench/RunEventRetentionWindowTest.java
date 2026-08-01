package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 持久事件保留窗口与恢复游标领域语义测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RunEventRetentionWindowTest {

    @Test
    void emptyRunShouldExposeEmptyReplayWindow() {
        RunEventRetentionWindow window =
                RunEventRetentionWindow.from(0L, 0L);

        assertEquals(0L, window.getLastEventSequence());
        assertEquals(0L, window.getEarliestRetainedSequence());
        assertDoesNotThrow(() -> window.requireReplayAfter(0L));
        assertFalse(window.hasMoreAfter(0L));
    }

    @Test
    void retainedEventsShouldAllowCursorImmediatelyBeforeFirstEvent() {
        RunEventRetentionWindow window =
                RunEventRetentionWindow.from(10L, 5L);

        assertEquals(5L, window.getEarliestRetainedSequence());
        assertDoesNotThrow(() -> window.requireReplayAfter(4L));
        assertDoesNotThrow(() -> window.requireReplayAfter(10L));
        RunEventCursorExpiredException expired = assertThrows(
                RunEventCursorExpiredException.class,
                () -> window.requireReplayAfter(3L));
        assertEquals(5L, expired.getEarliestRetainedSequence());
        assertEquals(10L, expired.getLastEventSequence());
        assertTrue(window.hasMoreAfter(9L));
        assertFalse(window.hasMoreAfter(10L));
    }

    @Test
    void fullyPrunedRunShouldExposeLastPlusOneAsRetentionStart() {
        RunEventRetentionWindow window =
                RunEventRetentionWindow.from(10L, 0L);

        assertEquals(11L, window.getEarliestRetainedSequence());
        assertDoesNotThrow(() -> window.requireReplayAfter(10L));
        assertThrows(RunEventCursorExpiredException.class,
                () -> window.requireReplayAfter(9L));
        assertFalse(window.hasMoreAfter(10L));
    }

    @Test
    void cursorAndPersistedFactsOutsideRunBoundaryShouldFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RunEventRetentionWindow.from(-1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> RunEventRetentionWindow.from(0L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> RunEventRetentionWindow.from(5L, 6L));

        RunEventRetentionWindow window =
                RunEventRetentionWindow.from(5L, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> window.requireReplayAfter(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> window.requireReplayAfter(6L));
        assertThrows(IllegalArgumentException.class,
                () -> window.hasMoreAfter(6L));
    }
}
