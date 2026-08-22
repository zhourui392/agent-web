package com.example.agentweb.interfaces;

import com.example.agentweb.app.mode.ModeAppService;
import com.example.agentweb.app.mode.ModeQueryService;
import com.example.agentweb.app.mode.ModeView;
import com.example.agentweb.app.mode.SaveModeCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.interfaces.dto.ModeDtos;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模式 CRUD / 模板库 / 能力目录接口。
 *
 * @author alex
 * @since 2026-08-20
 */
@RestController
@RequestMapping(path = "/api/modes", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class ModeController {

    private final ModeAppService modeAppService;
    private final ModeQueryService modeQueryService;
    private final CurrentUserProvider currentUserProvider;

    public ModeController(ModeAppService modeAppService,
                          ModeQueryService modeQueryService,
                          CurrentUserProvider currentUserProvider) {
        this.modeAppService = modeAppService;
        this.modeQueryService = modeQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<ModeDtos.ModeResponse> list(@RequestParam("scope") String scope) {
        if ("templates".equals(scope)) {
            throw new IllegalArgumentException(
                    "use /api/modes/templates for the template catalog");
        }
        if (!"mine".equals(scope)) {
            throw new IllegalArgumentException("scope must be mine or templates");
        }
        String userId = currentUserProvider.currentUserId();
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return modeQueryService.listMine(userId).stream()
                .map(ModeDtos.ModeResponse::from)
                .toList();
    }

    @GetMapping("/templates")
    public List<ModeDtos.TemplateResponse> templates() {
        return modeQueryService.listTemplates().stream()
                .map(ModeDtos.TemplateResponse::from)
                .toList();
    }

    @GetMapping("/capabilities")
    public List<ModeDtos.CapabilityCatalogResponse> capabilities() {
        return modeQueryService.listCapabilityCatalog().stream()
                .map(ModeDtos.CapabilityCatalogResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModeDtos.ModeResponse create(@Valid @RequestBody ModeDtos.SaveModeRequest req) {
        return ModeDtos.ModeResponse.from(modeAppService.create(toCommand(req)));
    }

    @PutMapping("/{id}")
    public ModeDtos.ModeResponse update(@PathVariable("id") String id,
                                        @Valid @RequestBody ModeDtos.SaveModeRequest req) {
        return ModeDtos.ModeResponse.from(modeAppService.update(id, toCommand(req)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        modeAppService.delete(id);
    }

    @PostMapping("/fork")
    @ResponseStatus(HttpStatus.CREATED)
    public ModeDtos.ModeResponse fork(@Valid @RequestBody ModeDtos.ForkModeRequest req) {
        if (req.getRevisionId() == null || req.getRevisionId() < 1) {
            throw new IllegalArgumentException("revisionId must be a positive number");
        }
        return ModeDtos.ModeResponse.from(modeAppService.fork(
                req.getDefinitionIdentifier(), req.getRevisionId(), req.getDisplayName()));
    }

    private SaveModeCommand toCommand(ModeDtos.SaveModeRequest req) {
        return new SaveModeCommand(
                req.getIdentifier(), req.getDisplayName(), req.getDescription(),
                req.getDefaultPrompt(), req.getPermissionMode(), req.getModel(),
                req.getEffort(),
                toCommandRefs(req.getCommands()),
                toSkillRefs(req.getSkills()),
                toMcpRefs(req.getMcpServers()));
    }

    private static List<SaveModeCommand.CapabilityRefInput> toCommandRefs(
            List<ModeDtos.CapabilityRefRequest> refs) {
        return refs == null ? List.of() : refs.stream()
                .map(r -> new SaveModeCommand.CapabilityRefInput(
                        r.getIdentifier(), r.getVersion(), r.getContentHash(), r.getSortOrder()))
                .toList();
    }

    private static List<SaveModeCommand.CapabilityRefInput> toSkillRefs(
            List<ModeDtos.CapabilityRefRequest> refs) {
        return refs == null ? List.of() : refs.stream()
                .map(r -> new SaveModeCommand.CapabilityRefInput(
                        r.getIdentifier(), r.getVersion(), r.getContentHash(), r.getSortOrder()))
                .toList();
    }

    private static List<SaveModeCommand.McpServerRefInput> toMcpRefs(
            List<ModeDtos.McpServerRefRequest> refs) {
        return refs == null ? List.of() : refs.stream()
                .map(r -> new SaveModeCommand.McpServerRefInput(
                        r.getIdentifier(), r.getVersion(), r.getContentHash(),
                        r.getMaximumAccess(), r.getTransport(), r.getSortOrder()))
                .toList();
    }
}
