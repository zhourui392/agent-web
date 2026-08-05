package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.stage.WorkbenchStageCatalogAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.StageSkillReference;
import com.example.agentweb.domain.workbench.stage.StageSkillSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinition;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraft;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.interfaces.workbench.admin.dto.StageCatalogVersionRequest;
import com.example.agentweb.interfaces.workbench.admin.dto.StageDefinitionDraftRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workbench Stage Catalog 管理接口。
 *
 * @author alex
 * @since 2026-08-05
 */
@RestController
@RequestMapping(path = "/api/admin-settings/workbench/stage-definitions",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class AdminWorkbenchStageCatalogController {

    private final WorkbenchStageCatalogAppService appService;
    private final UserContext userContext;

    public AdminWorkbenchStageCatalogController(
            WorkbenchStageCatalogAppService appService, UserContext userContext) {
        this.appService = appService;
        this.userContext = userContext;
    }

    @GetMapping
    public Map<String, Object> findAll() {
        requireAdministrator();
        return catalogResponse(appService.find());
    }

    @GetMapping("/{definitionIdentifier}")
    public Map<String, Object> findOne(
            @PathVariable("definitionIdentifier") String definitionIdentifier) {
        requireAdministrator();
        WorkbenchStageCatalog catalog = appService.find();
        Map<String, Object> response = definitionResponse(
                catalog.requireDefinition(definitionIdentifier));
        response.put("stageCatalogVersion", catalog.getCatalogVersion());
        return response;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createDraft(
            @RequestHeader(value = "If-Match", required = false)
                    String expectedCatalogVersion,
            @RequestBody StageDefinitionDraftRequest request) {
        LoginUser administrator = requireAdministrator();
        requireDraftRequest(request);
        WorkbenchStageDefinition created = appService.createDraft(
                request.getDefinitionIdentifier(), request.toDraftContent(),
                parseExpectedVersion(expectedCatalogVersion), editor(administrator));
        return definitionResponse(created);
    }

    @PutMapping(path = "/{definitionIdentifier}/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveDraft(
            @PathVariable("definitionIdentifier") String definitionIdentifier,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedDefinitionVersion,
            @RequestBody StageDefinitionDraftRequest request) {
        LoginUser administrator = requireAdministrator();
        requireDraftRequest(request);
        WorkbenchStageDefinition saved = appService.saveDraft(
                definitionIdentifier, request.toDraftContent(),
                parseExpectedVersion(expectedDefinitionVersion), editor(administrator));
        return definitionResponse(saved);
    }

    @PostMapping(path = "/{definitionIdentifier}/publish",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> publishDraft(
            @PathVariable("definitionIdentifier") String definitionIdentifier,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedDefinitionVersion,
            @RequestBody StageCatalogVersionRequest request) {
        LoginUser administrator = requireAdministrator();
        WorkbenchStageDefinitionRevision published = appService.publishDraft(
                definitionIdentifier, requireCatalogVersion(request),
                parseExpectedVersion(expectedDefinitionVersion), editor(administrator));
        return publishedResponse(published);
    }

    @PostMapping(path = "/{definitionIdentifier}/disable",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> disable(
            @PathVariable("definitionIdentifier") String definitionIdentifier,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedDefinitionVersion,
            @RequestBody StageCatalogVersionRequest request) {
        LoginUser administrator = requireAdministrator();
        WorkbenchStageDefinition disabled = appService.disable(
                definitionIdentifier, requireCatalogVersion(request),
                parseExpectedVersion(expectedDefinitionVersion), editor(administrator));
        return definitionResponse(disabled);
    }

    private Map<String, Object> catalogResponse(WorkbenchStageCatalog catalog) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> definitions =
                new ArrayList<Map<String, Object>>(catalog.getDefinitions().size());
        for (WorkbenchStageDefinition definition : catalog.getDefinitions()) {
            definitions.add(definitionResponse(definition));
        }
        response.put("stageCatalogVersion", catalog.getCatalogVersion());
        response.put("definitions", definitions);
        response.put("updatedAt", catalog.getUpdatedAt());
        return response;
    }

    private Map<String, Object> definitionResponse(
            WorkbenchStageDefinition definition) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("definitionIdentifier", definition.getDefinitionIdentifier());
        response.put("definitionVersion", definition.getVersion());
        response.put("lifecycleStatus", definition.getLifecycleStatus().name());
        response.put("hasDraft", definition.hasDraft());
        response.put("draft", definition.hasDraft()
                ? draftResponse(definition.getDraft()) : null);
        response.put("published", definition.getCurrentPublishedRevision() == null
                ? null : publishedResponse(definition.getCurrentPublishedRevision()));
        response.put("createdBy", editorResponse(definition.getCreatedBy()));
        response.put("createdAt", definition.getCreatedAt());
        response.put("updatedBy", editorResponse(definition.getUpdatedBy()));
        response.put("updatedAt", definition.getUpdatedAt());
        return response;
    }

    private Map<String, Object> draftResponse(WorkbenchStageDraft draft) {
        Map<String, Object> response = contentResponse(draft.getContent());
        response.put("basedOnPublishedRevisionNumber",
                draft.getBasedOnPublishedRevisionNumber());
        response.put("draftHash", draft.getDraftHash());
        response.put("savedBy", editorResponse(draft.getSavedBy()));
        response.put("savedAt", draft.getSavedAt());
        return response;
    }

    private Map<String, Object> contentResponse(WorkbenchStageDraftContent content) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("sequenceNumber", content.getSequenceNumber());
        response.put("displayName", content.getDisplayName());
        response.put("description", content.getDescription());
        response.put("stageRules", content.getStageRules());
        response.put("allowedRunModes", content.getAllowedRunModes());
        response.put("commandReferences", commandSelections(
                content.getCommandSelections()));
        response.put("skillReferences", skillSelections(content.getSkillSelections()));
        response.put("mcpServerReferences", mcpSelections(
                content.getMcpServerSelections()));
        return response;
    }

    private Map<String, Object> publishedResponse(
            WorkbenchStageDefinitionRevision published) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("definitionIdentifier", published.getDefinitionIdentifier());
        response.put("revisionNumber", published.getRevisionNumber());
        response.put("sequenceNumber", published.getSequenceNumber());
        response.put("displayName", published.getDisplayName());
        response.put("description", published.getDescription());
        response.put("stageRules", published.getStageRules());
        response.put("allowedRunModes", published.getAllowedRunModes());
        response.put("commandReferences", commandReferences(
                published.getCommandReferences()));
        response.put("skillReferences", skillReferences(published.getSkillReferences()));
        response.put("mcpServerReferences", mcpReferences(
                published.getMcpServerReferences()));
        response.put("definitionHash", published.getDefinitionHash());
        response.put("createdBy", editorResponse(published.getCreatedBy()));
        response.put("createdAt", published.getCreatedAt());
        response.put("publishedAt", published.getPublishedAt());
        return response;
    }

    private List<Map<String, Object>> commandSelections(
            List<StageCommandSelection> selections) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageCommandSelection selection : selections) {
            response.add(versionResponse(selection.getIdentifier(), selection.getVersion()));
        }
        return response;
    }

    private List<Map<String, Object>> skillSelections(
            List<StageSkillSelection> selections) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageSkillSelection selection : selections) {
            Map<String, Object> item = versionResponse(
                    selection.getIdentifier(), selection.getVersion());
            item.put("required", selection.isRequired());
            response.add(item);
        }
        return response;
    }

    private List<Map<String, Object>> mcpSelections(
            List<StageMcpServerSelection> selections) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageMcpServerSelection selection : selections) {
            Map<String, Object> item = versionResponse(
                    selection.getIdentifier(), selection.getVersion());
            item.put("required", selection.isRequired());
            response.add(item);
        }
        return response;
    }

    private List<Map<String, Object>> commandReferences(
            List<StageCommandReference> references) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageCommandReference reference : references) {
            Map<String, Object> item = versionResponse(
                    reference.getIdentifier(), reference.getVersion());
            item.put("contentHash", reference.getContentHash());
            response.add(item);
        }
        return response;
    }

    private List<Map<String, Object>> skillReferences(
            List<StageSkillReference> references) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageSkillReference reference : references) {
            Map<String, Object> item = versionResponse(
                    reference.getIdentifier(), reference.getVersion());
            item.put("packageHash", reference.getPackageHash());
            item.put("required", reference.isRequired());
            response.add(item);
        }
        return response;
    }

    private List<Map<String, Object>> mcpReferences(
            List<StageMcpServerReference> references) {
        List<Map<String, Object>> response = new ArrayList<Map<String, Object>>();
        for (StageMcpServerReference reference : references) {
            Map<String, Object> item = versionResponse(
                    reference.getIdentifier(), reference.getVersion());
            item.put("definitionHash", reference.getDefinitionHash());
            item.put("required", reference.isRequired());
            item.put("maximumAccess", reference.getMaximumAccess());
            item.put("transport", reference.getTransport());
            response.add(item);
        }
        return response;
    }

    private Map<String, Object> versionResponse(String identifier, String version) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("identifier", identifier);
        response.put("version", version);
        return response;
    }

    private Map<String, Object> editorResponse(StageCatalogEditor editor) {
        if (editor == null) {
            return null;
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("actorId", editor.getActorId());
        response.put("actorName", editor.getActorName());
        return response;
    }

    private StageCatalogEditor editor(LoginUser administrator) {
        return StageCatalogEditor.create(
                administrator.getUserId(), administrator.getUserName());
    }

    private LoginUser requireAdministrator() {
        LoginUser user = userContext.currentUser()
                .orElseThrow(AdminWorkbenchUnauthorizedException::new);
        if (!user.isAdmin()) {
            throw new AdminWorkbenchForbiddenException();
        }
        return user;
    }

    private void requireDraftRequest(StageDefinitionDraftRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Stage Definition Draft request is required");
        }
    }

    private long requireCatalogVersion(StageCatalogVersionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Stage Catalog version request is required");
        }
        return request.requireExpectedStageCatalogVersion();
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new IllegalArgumentException("If-Match version is required");
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 1L) {
                throw new IllegalArgumentException(
                        "If-Match version must be positive");
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "If-Match version must be numeric", failure);
        }
    }
}
