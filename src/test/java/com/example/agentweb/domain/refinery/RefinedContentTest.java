package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class RefinedContentTest {

    @Test
    public void title_should_be_auto_trimmed() {
        RefinedContent c = new RefinedContent(
                "  some title  ",
                null, "ctx", "proc", "concl");
        assertEquals("some title", c.getTitle());
    }

    @Test
    public void title_null_or_blank_should_throw() {
        assertThrows(IllegalArgumentException.class,
                () -> new RefinedContent(null, null, "c", "p", "co"));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinedContent("   ", null, "c", "p", "co"));
    }

    @Test
    public void trigger_signals_null_should_normalize_to_empty_list() {
        RefinedContent c = new RefinedContent("t", null, "c", "p", "co");
        assertTrue(c.getTriggerSignals().isEmpty());
    }

    @Test
    public void trigger_signals_should_filter_empty_tokens_and_strip_whitespace() {
        RefinedContent c = new RefinedContent("t",
                Arrays.asList(" 退款按钮不显示 ", "", "  ", "Page load error", null),
                "c", "p", "co");
        assertEquals(2, c.getTriggerSignals().size());
        assertEquals("退款按钮不显示", c.getTriggerSignals().get(0));
        assertEquals("Page load error", c.getTriggerSignals().get(1));
    }

    @Test
    public void trigger_signals_should_be_immutable_externally() {
        RefinedContent c = new RefinedContent("t",
                Collections.singletonList("kw"), "c", "p", "co");
        assertThrows(UnsupportedOperationException.class,
                () -> c.getTriggerSignals().add("hack"));
    }

    @Test
    public void context_process_conclusion_null_should_normalize_to_empty_string() {
        RefinedContent c = new RefinedContent("t", null, null, null, null);
        assertEquals("", c.getContext());
        assertEquals("", c.getProcess());
        assertEquals("", c.getConclusion());
    }
}
