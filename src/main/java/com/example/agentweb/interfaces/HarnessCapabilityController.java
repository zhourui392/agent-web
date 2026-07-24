package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.CapabilitySnapshotQueryService;
import com.example.agentweb.app.harness.CapabilitySnapshotView;
import com.example.agentweb.app.harness.HarnessCapabilityService;
import com.example.agentweb.app.harness.HarnessCapabilitySnapshotNotFoundException;
import com.example.agentweb.app.harness.ResolveHarnessCapabilityCommand;
import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.interfaces.dto.HarnessCapabilityResolveRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Harness M2 Capability Snapshot 固化与管理预览 API。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@RestController
@RequestMapping(path = "/api/harness/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessCapabilityController {

    private final HarnessCapabilityService capabilityService;
    private final CapabilitySnapshotQueryService queryService;

    public HarnessCapabilityController(HarnessCapabilityService capabilityService,
                                       CapabilitySnapshotQueryService queryService) {
        this.capabilityService = capabilityService;
        this.queryService = queryService;
    }

    @PostMapping("/{runId}/stages/{stage}/capability-snapshot")
    public ResponseEntity<CapabilitySnapshotView> resolve(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessCapabilityResolveRequest request) {
        CapabilityGrant grant = new CapabilityGrant(set(request.getReadableFileRoots()),
                set(request.getWritableFileRoots()), set(request.getExecutableCommands()));
        ResolveHarnessCapabilityCommand command = new ResolveHarnessCapabilityCommand(
                runId, stage(stage), set(request.getExplicitSkillIds()), set(request.getTechnicalTags()),
                set(request.getApprovedWorkspaceSkillIds()), grant,
                set(request.getExplicitMcpServerIds()), set(request.getRequiredMcpServerIds()),
                set(request.getGrantedMcpServerIds()),
                request.getCurrentInput());
        return ResponseEntity.status(HttpStatus.CREATED).body(capabilityService.resolve(command));
    }

    @GetMapping("/{runId}/stages/{stage}/attempts/{attemptNumber}/capability-snapshot")
    public CapabilitySnapshotView find(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @PathVariable("attemptNumber") int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attempt number must be positive");
        }
        HarnessStage harnessStage = stage(stage);
        return queryService.find(runId, harnessStage, attemptNumber)
                .orElseThrow(() -> new HarnessCapabilitySnapshotNotFoundException(
                        runId, harnessStage.name(), attemptNumber));
    }

    private HarnessStage stage(String value) {
        return HarnessStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private Set<String> set(List<String> values) {
        if (values == null) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<String>(values);
    }
}
