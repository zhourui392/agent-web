package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.capability.EffectivePhaseCapabilityView;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationErrorCode;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationException;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityMutationView;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityOwnerService;
import com.example.agentweb.app.workbench.capability.PublicPhaseCapabilityOverrideView;
import com.example.agentweb.app.workbench.capability.PutPhaseCapabilityOverrideCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.workbench.dto.PhaseCapabilityOverrideRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Workbench Phase Capability Profile 与 Override 的 Owner HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/phases/{phase}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkbenchCapabilityController {

    private final PhaseCapabilityOwnerService service;
    private final CurrentUserProvider currentUserProvider;

    public WorkbenchCapabilityController(
            PhaseCapabilityOwnerService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/capability-profile")
    public EffectivePhaseCapabilityView getEffectiveProfile(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase) {
        return service.getEffectiveProfile(
                currentOwner(), WorkbenchId.of(workbenchId),
                parsePhase(phase));
    }

    @GetMapping("/capability-override")
    public PublicPhaseCapabilityOverrideView getOverride(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase) {
        return service.getOverride(
                        currentOwner(), WorkbenchId.of(workbenchId),
                        parsePhase(phase))
                .orElseThrow(() -> new PhaseCapabilityApplicationException(
                        PhaseCapabilityApplicationErrorCode.OVERRIDE_NOT_FOUND,
                        "phase capability override was not found"));
    }

    @PutMapping(
            path = "/capability-override",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PhaseCapabilityMutationView putOverride(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @Valid @RequestBody PhaseCapabilityOverrideRequest request) {
        WorkbenchId id = WorkbenchId.of(workbenchId);
        WorkbenchPhase parsedPhase = parsePhase(phase);
        PutPhaseCapabilityOverrideCommand command =
                new PutPhaseCapabilityOverrideCommand(
                        id, parsedPhase, parseExpectedVersion(expectedVersion),
                        request.getOptionalSkillIds(),
                        request.getOptionalMcpServerIds(),
                        request.getAdditionalRule());
        return service.putOverride(currentOwner(), command);
    }

    @DeleteMapping("/capability-override")
    public PhaseCapabilityMutationView deleteOverride(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion) {
        return service.deleteOverride(
                currentOwner(), WorkbenchId.of(workbenchId),
                parsePhase(phase), parseExpectedVersion(expectedVersion));
    }

    @ExceptionHandler({
            WorkbenchCapabilityRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception ignoredFailure) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", "WORKBENCH_CAPABILITY_REQUEST_INVALID");
        body.put("message", "workbench capability request is invalid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private WorkbenchPhase parsePhase(String value) {
        try {
            return WorkbenchPhase.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new WorkbenchCapabilityRequestException();
        }
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new WorkbenchCapabilityRequestException();
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 0L) {
                throw new WorkbenchCapabilityRequestException();
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new WorkbenchCapabilityRequestException();
        }
    }
}
