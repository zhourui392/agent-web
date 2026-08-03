package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminCapabilityCatalogView;
import com.example.agentweb.app.workbench.admin.AdminPhaseCapabilityAppService;
import com.example.agentweb.app.workbench.admin.AdminPhaseCapabilityProfileView;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.workbench.admin.dto.PhaseCapabilityProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 管理后台 Phase Capability Profile CRUD 边界。
 *
 * <p>受 {@code agent.admin.protected-prefixes} 保护，仅 ADMIN 角色可访问。
 * 管理员可查看四阶段 Profile 和可信 Catalog，并更新阶段能力配置。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@RestController
@RequestMapping(
        path = "/api/admin/workbench-capabilities",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminPhaseCapabilityController {

    private final AdminPhaseCapabilityAppService appService;
    private final UserContext userContext;

    public AdminPhaseCapabilityController(
            AdminPhaseCapabilityAppService appService,
            UserContext userContext) {
        this.appService = appService;
        this.userContext = userContext;
    }

    @GetMapping("/profiles")
    public List<AdminPhaseCapabilityProfileView> listProfiles() {
        requireAdministrator();
        return appService.listProfiles();
    }

    @GetMapping("/profiles/{phase}")
    public AdminPhaseCapabilityProfileView getProfile(
            @PathVariable("phase") String phase) {
        requireAdministrator();
        return appService.getProfile(parsePhase(phase));
    }

    @PutMapping(
            path = "/profiles/{phase}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public AdminPhaseCapabilityProfileView updateProfile(
            @PathVariable("phase") String phase,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @Valid @RequestBody PhaseCapabilityProfileRequest request) {
        requireAdministrator();
        long version = parseExpectedVersion(expectedVersion);
        WorkbenchPhase parsedPhase = parsePhase(phase);
        List<AdminPhaseCapabilityAppService.CapabilityReferenceInput> capabilities =
                new ArrayList<AdminPhaseCapabilityAppService.CapabilityReferenceInput>();
        for (PhaseCapabilityProfileRequest.CapabilityReferenceInput input
                : request.getCapabilities()) {
            capabilities.add(
                    new AdminPhaseCapabilityAppService.CapabilityReferenceInput(
                            input.getId(), input.getType(), input.isRequired()));
        }
        return appService.updateProfile(
                parsedPhase, capabilities, version, currentAdministrator());
    }

    @GetMapping("/catalog")
    public AdminCapabilityCatalogView listCatalog() {
        requireAdministrator();
        return appService.listCatalog();
    }

    private WorkbenchAdministrator currentAdministrator() {
        LoginUser user = userContext.currentUser()
                .orElseThrow(AdminWorkbenchUnauthorizedException::new);
        if (!user.isAdmin()) {
            throw new AdminWorkbenchForbiddenException();
        }
        return WorkbenchAdministrator.fromAuthenticated(user);
    }

    private void requireAdministrator() {
        LoginUser user = userContext.currentUser()
                .orElseThrow(AdminWorkbenchUnauthorizedException::new);
        if (!user.isAdmin()) {
            throw new AdminWorkbenchForbiddenException();
        }
    }

    private WorkbenchPhase parsePhase(String value) {
        try {
            return WorkbenchPhase.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new AdminPhaseCapabilityRequestException();
        }
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new AdminPhaseCapabilityRequestException();
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 1L) {
                throw new AdminPhaseCapabilityRequestException();
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new AdminPhaseCapabilityRequestException();
        }
    }
}
