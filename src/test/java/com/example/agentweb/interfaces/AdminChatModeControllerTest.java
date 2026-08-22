package com.example.agentweb.interfaces;

import com.example.agentweb.app.mode.AdminModeView;
import com.example.agentweb.app.mode.ModeQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminChatModeController 全量模式列表委托回归。
 *
 * <p>鉴权由 AdminAuthFilter 前缀保护承担（filter 自身有独立回归），
 * 本测试只验证 handler 的读侧委托。</p>
 *
 * @author zhourui
 * @since 2026/08/22
 */
class AdminChatModeControllerTest {

    private ModeQueryService modeQueryService;
    private AdminChatModeController controller;

    @BeforeEach
    void setUp() {
        modeQueryService = mock(ModeQueryService.class);
        controller = new AdminChatModeController(modeQueryService);
    }

    @Test
    void list_delegatesToQueryServiceAllModes() {
        List<AdminModeView> expected = List.of(new AdminModeView(
                "m1", "reviewer", "评审模式", null, null, "plan", null, null,
                null, List.of(), List.of(), List.of(), "u1", "alice"));
        when(modeQueryService.listAll()).thenReturn(expected);

        List<AdminModeView> result = controller.list();

        assertEquals(1, result.size());
        assertEquals("alice", result.get(0).ownerUsername());
        assertSame(expected, result);
        verify(modeQueryService).listAll();
    }
}
