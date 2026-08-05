package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.stage.WorkbenchStageCatalogAppService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通用户创建 Workbench 时可选择的 Published Stage 安全投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@RestController
@RequestMapping(path = "/api/workbench/stage-definitions",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class WorkbenchStageDefinitionController {

    private final WorkbenchStageCatalogAppService appService;
    private final CurrentUserProvider currentUserProvider;

    public WorkbenchStageDefinitionController(
            WorkbenchStageCatalogAppService appService,
            CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public Map<String, Object> findSelectableStages() {
        currentUserProvider.currentUserId();
        WorkbenchStageCatalog catalog = appService.find();
        List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
        for (WorkbenchStageDefinitionRevision revision
                : catalog.selectableRevisions()) {
            stages.add(stageResponse(revision));
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("stageCatalogVersion", catalog.getCatalogVersion());
        response.put("stages", stages);
        return response;
    }

    private Map<String, Object> stageResponse(
            WorkbenchStageDefinitionRevision revision) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("definitionIdentifier", revision.getDefinitionIdentifier());
        response.put("publishedRevision", revision.getRevisionNumber());
        response.put("displayName", revision.getDisplayName());
        response.put("description", revision.getDescription());
        response.put("sequenceNumber", revision.getSequenceNumber());
        response.put("definitionHash", revision.getDefinitionHash());
        return response;
    }
}
