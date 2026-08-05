package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.capability.CapabilitySourceCandidate;
import com.example.agentweb.app.capability.CapabilitySourceConfigurationAppService;
import com.example.agentweb.app.capability.CapabilitySourceProbeResult;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.interfaces.workbench.admin.dto.CapabilitySourceConfigurationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workbench Capability Source 管理接口。
 *
 * @author alex
 * @since 2026-08-05
 */
@RestController
@RequestMapping(path = "/api/admin-settings/workbench/capability-sources",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminWorkbenchCapabilitySourceController {

    private final CapabilitySourceConfigurationAppService appService;
    private final UserContext userContext;
    private final ObjectMapper objectMapper;

    public AdminWorkbenchCapabilitySourceController(
            CapabilitySourceConfigurationAppService appService,
            UserContext userContext, ObjectMapper objectMapper) {
        this.appService = appService;
        this.userContext = userContext;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Map<String, Object> find() {
        requireAdministrator();
        return appService.find().map(this::configurationResponse)
                .orElseGet(this::emptyConfigurationResponse);
    }

    @PostMapping(path = "/validation",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> validate(
            @RequestBody CapabilitySourceConfigurationRequest request) {
        requireAdministrator();
        CapabilitySourceProbeResult result = appService.validate(candidate(request));
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("commandCatalogDirectories",
                result.getCommandCatalogDirectories());
        response.put("skillCatalogDirectories", result.getSkillCatalogDirectories());
        response.put("canonicalMcpConfigurationJson",
                result.getCanonicalMcpConfigurationJson());
        response.put("canonicalMcpConfiguration",
                readJson(result.getCanonicalMcpConfigurationJson()));
        response.put("commands", result.getCommands());
        response.put("skills", result.getSkills());
        response.put("mcpServers", result.getMcpServers());
        response.put("warnings", result.getWarnings());
        return response;
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> update(
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @RequestBody CapabilitySourceConfigurationRequest request) {
        LoginUser administrator = requireAdministrator();
        CapabilitySourceConfiguration updated = appService.update(
                candidate(request), parseExpectedVersion(expectedVersion),
                CapabilityConfigurationEditor.create(
                        administrator.getUserId(), administrator.getUserName()));
        return configurationResponse(updated);
    }

    private CapabilitySourceCandidate candidate(
            CapabilitySourceConfigurationRequest request) {
        if (request == null || request.getMcpConfiguration() == null) {
            throw new IllegalArgumentException(
                    "capability source configuration request is required");
        }
        return new CapabilitySourceCandidate(
                request.toCommandCatalogDirectories(),
                request.toSkillCatalogDirectories(),
                writeJson(request.getMcpConfiguration()));
    }

    private LoginUser requireAdministrator() {
        LoginUser user = userContext.currentUser()
                .orElseThrow(AdminWorkbenchUnauthorizedException::new);
        if (!user.isAdmin()) {
            throw new AdminWorkbenchForbiddenException();
        }
        return user;
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Expected capability source version is required");
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 0L) {
                throw new IllegalArgumentException(
                        "Expected capability source version is invalid");
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Expected capability source version is invalid");
        }
    }

    private Map<String, Object> configurationResponse(
            CapabilitySourceConfiguration configuration) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("version", configuration.getVersion());
        response.put("configurationHash", configuration.getConfigurationHash());
        response.put("commandCatalogDirectories",
                configuration.getCommandCatalogDirectories());
        response.put("skillCatalogDirectories",
                configuration.getSkillCatalogDirectories());
        response.put("mcpConfiguration",
                readJson(configuration.getMcpConfigurationJson()));
        response.put("updatedBy", configuration.getUpdatedBy());
        response.put("updatedAt", configuration.getUpdatedAt().toString());
        return response;
    }

    private Map<String, Object> emptyConfigurationResponse() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("version", 0L);
        response.put("configurationHash", null);
        response.put("commandCatalogDirectories", java.util.Collections.emptyList());
        response.put("skillCatalogDirectories", java.util.Collections.emptyList());
        response.put("mcpConfiguration", readJson(
                "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}"));
        response.put("updatedBy", null);
        response.put("updatedAt", null);
        return response;
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP configuration JSON is invalid", failure);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "stored MCP configuration JSON is invalid", failure);
        }
    }
}
