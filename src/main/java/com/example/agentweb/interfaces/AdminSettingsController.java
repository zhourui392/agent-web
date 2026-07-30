package com.example.agentweb.interfaces;

import com.example.agentweb.app.setting.WorkspaceSettingsAppServiceImpl;
import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.setting.RuntimeAgentSettings;
import com.example.agentweb.interfaces.dto.WorkspaceSettingsUpdateRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台系统设置：对话默认模型与工作空间目录授权，修改后免重启热生效。
 *
 * <p>受管理口令鉴权({@code agent.admin.protected-prefixes} 含 {@code /api/admin-settings})。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
@RestController
@RequestMapping(path = "/api/admin-settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminSettingsController {

    private final RuntimeAgentSettings runtimeAgentSettings;
    private final WorkspaceSettingsAppServiceImpl workspaceSettingsAppService;
    private final AgentCatalogService agentCatalogService;

    public AdminSettingsController(RuntimeAgentSettings runtimeAgentSettings,
                                   WorkspaceSettingsAppServiceImpl workspaceSettingsAppService,
                                   AgentCatalogService agentCatalogService) {
        this.runtimeAgentSettings = runtimeAgentSettings;
        this.workspaceSettingsAppService = workspaceSettingsAppService;
        this.agentCatalogService = agentCatalogService;
    }

    /**
     * 读取当前的对话默认模型与可选项。
     *
     * @return {@code {chatDefaultAgent, options}}
     */
    @GetMapping("/agent-models")
    public Map<String, Object> getAgentModels() {
        return body(runtimeAgentSettings.getChatDefaultAgent());
    }

    /**
     * 更新对话默认模型。稳定身份解析后由 AgentCatalog 校验默认资格。
     *
     * @param req {@code {chatDefaultAgent}}
     * @return 更新后的最新值
     */
    @PutMapping("/agent-models")
    public Map<String, Object> updateAgentModels(@RequestBody Map<String, String> req) {
        AgentType chat = AgentType.parseKnown(req.get("chatDefaultAgent"));
        agentCatalogService.requireDefaultEligible(chat);
        runtimeAgentSettings.setChatDefaultAgent(chat);
        log.info("admin-agent-models-updated chat={}", chat);
        return body(chat);
    }

    @GetMapping("/workspaces")
    public Map<String, Object> getWorkspaces() {
        return workspaceBody(workspaceSettingsAppService.get());
    }

    @PutMapping("/workspaces")
    public Map<String, Object> updateWorkspaces(@Valid @RequestBody WorkspaceSettingsUpdateRequest request) {
        workspaceSettingsAppService.update(request.getDefaultWorkspace(),
                request.getWorkspaceRoots(), request.getUploadRoots());
        log.info("admin-workspace-settings-updated");
        return workspaceBody(workspaceSettingsAppService.get());
    }

    @DeleteMapping("/workspaces")
    public Map<String, Object> resetWorkspaces() {
        workspaceSettingsAppService.reset();
        log.info("admin-workspace-settings-reset");
        return workspaceBody(workspaceSettingsAppService.get());
    }

    private Map<String, Object> body(AgentType chat) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chatDefaultAgent", chat.name());
        java.util.List<String> options = agentCatalogService.currentCatalog()
                .defaultEligibleTypes().stream().map(AgentType::name).toList();
        m.put("options", options);
        return m;
    }

    private Map<String, Object> workspaceBody(WorkspaceSettings settings) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("defaultWorkspace", settings.getDefaultWorkspace());
        body.put("workspaceRoots", settings.getWorkspaceRoots());
        body.put("uploadRoots", settings.getUploadRoots());
        return body;
    }
}
