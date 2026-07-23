package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityDecision;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RejectedSkill;
import com.example.agentweb.domain.harness.SnapshotSkill;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 API 使用的不可变 Capability Snapshot 读视图。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilitySnapshotView {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final AgentRuntime runtime;
    private final String environment;
    private final String policyVersion;
    private final String promptPackId;
    private final String promptPackVersion;
    private final String promptPackHash;
    private final Map<String, String> promptResourceHashes;
    private final List<SnapshotSkill> selectedSkills;
    private final List<RejectedSkill> rejectedSkills;
    private final List<CapabilityDecision> capabilityDecisions;
    private final List<HarnessPromptPart> promptParts;
    private final String finalPrompt;
    private final String promptHash;
    private final String snapshotHash;
    private final Instant createdAt;

    public CapabilitySnapshotView(CapabilitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("capability snapshot must not be null");
        }
        this.runId = snapshot.getRunId();
        this.stage = snapshot.getStage();
        this.attemptNumber = snapshot.getAttemptNumber();
        this.runtime = snapshot.getRuntime();
        this.environment = snapshot.getEnvironment();
        this.policyVersion = snapshot.getPolicyVersion();
        this.promptPackId = snapshot.getPromptPackId();
        this.promptPackVersion = snapshot.getPromptPackVersion();
        this.promptPackHash = snapshot.getPromptPackHash();
        this.promptResourceHashes = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(snapshot.getPromptResourceHashes()));
        this.selectedSkills = immutable(snapshot.getSelectedSkills());
        this.rejectedSkills = immutable(snapshot.getRejectedSkills());
        this.capabilityDecisions = immutable(snapshot.getCapabilityDecisions());
        this.promptParts = immutable(snapshot.getPromptParts());
        this.finalPrompt = snapshot.getFinalPrompt();
        this.promptHash = snapshot.getPromptHash();
        this.snapshotHash = snapshot.getSnapshotHash();
        this.createdAt = snapshot.getCreatedAt();
    }

    private <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
