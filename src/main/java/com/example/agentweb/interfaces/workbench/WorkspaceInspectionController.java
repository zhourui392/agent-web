package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkspaceInspectionAppService;
import com.example.agentweb.interfaces.workbench.dto.WorkspaceInspectRequest;
import com.example.agentweb.interfaces.workbench.dto.WorkspaceInspectionResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 候选仓库检查的 HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(path = "/api/workbench/workspaces",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkspaceInspectionController {

    private final WorkspaceInspectionAppService appService;

    public WorkspaceInspectionController(WorkspaceInspectionAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/inspect")
    public WorkspaceInspectionResponse inspect(
            @Valid @RequestBody WorkspaceInspectRequest request) {
        return WorkspaceInspectionResponse.from(
                appService.inspect(request.getWorkspaceRoot()));
    }
}
