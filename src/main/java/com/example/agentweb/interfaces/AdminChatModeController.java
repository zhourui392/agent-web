package com.example.agentweb.interfaces;

import com.example.agentweb.app.mode.AdminModeView;
import com.example.agentweb.app.mode.ModeQueryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台 Chat Mode 全量列表接口：管理员统一维护各用户的模式资产。
 *
 * <p>鉴权由 AdminAuthFilter 按 protectedPrefixes 前缀（/api/admin-settings/**）
 * 在 filter 层完成，未登录 401、非管理员 403；与同前缀的
 * {@link AdminSettingsController} 口径一致。写操作复用既有 /api/modes 端点，
 * 应用层对管理员放行归属校验。</p>
 *
 * @author zhourui
 * @since 2026/08/22
 */
@RestController
@RequestMapping(path = "/api/admin-settings/modes",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminChatModeController {

    private final ModeQueryService modeQueryService;

    public AdminChatModeController(ModeQueryService modeQueryService) {
        this.modeQueryService = modeQueryService;
    }

    /**
     * 全量模式列表（跨用户，含归属者）。
     *
     * @return 按更新时间倒序的全部用户模式
     */
    @GetMapping
    public List<AdminModeView> list() {
        return modeQueryService.listAll();
    }
}
