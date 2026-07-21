package com.example.agentweb.interfaces;

import com.example.agentweb.app.metrics.ConversationDetail;
import com.example.agentweb.app.metrics.ConversationPage;
import com.example.agentweb.app.metrics.ConversationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话记录控制器单测:验证 page/size clamp、关键字透传与详情 200/404 分支。直接调用,不起容器。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminConversationControllerTest {

    private final ConversationQueryService queryService = mock(ConversationQueryService.class);
    private final AdminConversationController controller = new AdminConversationController(queryService);

    @Test
    public void list_clampsPageAndSizeAndPassesKeyword() {
        ConversationPage page = new ConversationPage(Collections.emptyList(), 0L, 1, 20);
        when(queryService.list(eq(1), eq(20), eq("foo"))).thenReturn(page);

        // page<1 → 1, size 巨大 → 100? 但此用例先验下界与透传
        controller.list(0, 20, "foo");

        // page 被 clamp 到下界 1
        verify(queryService).list(eq(1), eq(20), eq("foo"));
    }

    @Test
    public void list_clampsSizeToUpperBound() {
        when(queryService.list(eq(2), eq(100), eq(null)))
                .thenReturn(new ConversationPage(Collections.emptyList(), 0L, 2, 100));

        controller.list(2, 9999, null);

        verify(queryService).list(eq(2), eq(100), eq(null));
    }

    @Test
    public void list_clampsSizeToLowerBound() {
        when(queryService.list(eq(1), eq(1), eq(null)))
                .thenReturn(new ConversationPage(Collections.emptyList(), 0L, 1, 1));

        controller.list(1, 0, null);

        verify(queryService).list(eq(1), eq(1), eq(null));
    }

    @Test
    public void detail_found_returns200() {
        ConversationDetail detail = new ConversationDetail();
        when(queryService.detail("s1")).thenReturn(detail);

        ResponseEntity<ConversationDetail> result = controller.detail("s1");

        assertEquals(200, result.getStatusCodeValue());
        assertEquals(detail, result.getBody());
    }

    @Test
    public void detail_missing_returns404() {
        when(queryService.detail("nope")).thenReturn(null);

        ResponseEntity<ConversationDetail> result = controller.detail("nope");

        assertEquals(404, result.getStatusCodeValue());
    }
}
