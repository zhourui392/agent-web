package com.example.agentweb.app.mode;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ChatModeRepository;
import com.example.agentweb.domain.mode.ModeCapabilities;
import com.example.agentweb.domain.mode.ModeNotFoundException;
import com.example.agentweb.domain.mode.ModePermissionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模式 CRUD 与 fork 用例回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ModeAppServiceImplTest {

    private ChatModeRepository modeRepository;
    private ModeQueryService modeQueryService;
    private ModeAppService service;

    @BeforeEach
    void setUp() {
        modeRepository = mock(ChatModeRepository.class);
        modeQueryService = mock(ModeQueryService.class);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(null) {
            @Override
            public String currentUserId() {
                return "u1";
            }
        };
        service = new ModeAppServiceImpl(modeRepository, modeQueryService,
                currentUserProvider);
    }

    private SaveModeCommand command(String identifier) {
        return new SaveModeCommand(identifier, "评审模式", "描述", "提示词",
                "plan", null, null,
                List.of(new SaveModeCommand.CapabilityRefInput(
                        "cmd", "1", ChatModeTest_hash("cmd"), 0)),
                null, null);
    }

    /** 构造指定操作者身份的服务：administrator=false 模拟普通用户，true 模拟管理员。 */
    private ModeAppService serviceWithOperator(boolean administrator) {
        CurrentUserProvider operator = new CurrentUserProvider(null) {
            @Override
            public String currentUserId() {
                return "u1";
            }

            @Override
            public boolean isAdministrator() {
                return administrator;
            }
        };
        return new ModeAppServiceImpl(modeRepository, modeQueryService, operator);
    }

    private ChatMode foreignMode() {
        return ChatMode.restore("m2", "u2", "reviewer", "评审",
                null, null, null, null, null, null, ModeCapabilities.empty(),
                Instant.now(), Instant.now(), 3);
    }

    private static String ChatModeTest_hash(String content) {
        return com.example.agentweb.domain.shared.CanonicalHashing.sha256(content);
    }

    @Test
    void createSavesAggregateWithOwner() {
        ModeView view = service.create(command("reviewer"));
        assertEquals("reviewer", view.identifier());
        assertEquals("plan", view.permissionMode());
        assertEquals(1, view.commands().size());
    }

    @Test
    void updateRejectsForeignMode() {
        when(modeRepository.find("m2")).thenReturn(Optional.of(foreignMode()));
        assertThrows(ModeNotFoundException.class, () ->
                service.update("m2", command("reviewer")));
    }

    @Test
    void updateAllowsAdministratorToEditForeignMode() {
        ChatMode foreign = foreignMode();
        when(modeRepository.find("m2")).thenReturn(Optional.of(foreign));
        ModeView view = serviceWithOperator(true).update("m2", command("reviewer"));
        assertEquals("评审模式", view.displayName());
    }

    @Test
    void deleteAllowsAdministratorToRemoveForeignMode() {
        when(modeRepository.find("m2")).thenReturn(Optional.of(foreignMode()));
        serviceWithOperator(true).delete("m2");
        verify(modeRepository).delete("m2", 3);
    }

    @Test
    void forkCopiesFrozenTemplateCapabilities() {
        when(modeQueryService.findTemplateRevision("stage-def", 7L)).thenReturn(
                new ModeQueryService.ModeTemplateRevisionView(7L, "stage-def",
                        "模板名", "模板描述", "按 DDD 方式梳理需求",
                        List.of(new ModeView.CommandRefView("cmd", "1",
                                ChatModeTest_hash("cmd"))),
                        List.of(),
                        List.of(new ModeView.McpServerRefView("mcp", "2",
                                ChatModeTest_hash("mcp"), "READ", "STDIO"))));
        ModeView view = service.fork("stage-def", 7L, "我的模式");
        assertEquals("我的模式", view.displayName());
        assertEquals(7L, view.sourceRevisionId());
        assertEquals(1, view.commands().size());
        assertEquals(1, view.mcpServers().size());
        assertEquals("READ", view.mcpServers().get(0).maximumAccess());
    }

    @Test
    void forkCopiesTemplateStageRulesAsDefaultPrompt() {
        when(modeQueryService.findTemplateRevision("stage-def", 7L)).thenReturn(
                new ModeQueryService.ModeTemplateRevisionView(7L, "stage-def",
                        "模板名", "模板描述", "按 DDD 方式梳理需求并输出结构化文档",
                        List.of(), List.of(), List.of()));
        ModeView view = service.fork("stage-def", 7L, null);
        assertEquals("按 DDD 方式梳理需求并输出结构化文档", view.defaultPrompt());
    }

    @Test
    void forkFailsWhenTemplateRevisionMissing() {
        when(modeQueryService.findTemplateRevision("stage-def", 404L)).thenReturn(null);
        assertThrows(ModeNotFoundException.class,
                () -> service.fork("stage-def", 404L, "x"));
    }
}
