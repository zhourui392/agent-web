package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Workbench 聚合给 Application 的 Phase Session 可信装配计划。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationProvisioning {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final OwnerReference owner;
    private final AgentType agentType;
    private final String environment;
    private final String primaryRepositoryRoot;
    private final String contextId;
    private final String currentConversationId;
    private final Instant currentConversationCreatedAt;
    private final int currentConversationGeneration;
    private final long workbenchVersion;

    private PhaseConversationProvisioning(
            Workbench workbench, WorkbenchPhase phase,
            PhaseConversationReference currentConversation) {
        this.workbenchId = workbench.getId();
        this.phase = phase;
        this.owner = workbench.getOwner();
        this.agentType = workbench.getAgentType();
        this.environment = workbench.getEnvironment();
        this.primaryRepositoryRoot = DomainText.require(
                workbench.getRepositoryScope().primaryRepository().getRepositoryRoot(),
                "phase conversation primary repository root", 4096);
        this.contextId = DomainText.require(
                workbenchId.getValue() + ":" + phase.name(),
                "phase conversation context id", 512);
        this.currentConversationId = currentConversation == null
                ? null : currentConversation.getConversationId();
        this.currentConversationCreatedAt = currentConversation == null
                ? null : currentConversation.getCreatedAt();
        this.currentConversationGeneration = currentConversation == null
                ? 0 : currentConversation.getGeneration();
        this.workbenchVersion = workbench.getVersion();
    }

    static PhaseConversationProvisioning plan(
            Workbench workbench, WorkbenchPhase phase,
            PhaseConversationReference currentConversation) {
        if (workbench == null || phase == null) {
            throw new IllegalArgumentException("phase conversation provisioning facts are required");
        }
        return new PhaseConversationProvisioning(workbench, phase, currentConversation);
    }

    public boolean hasCurrentConversation() {
        return currentConversationId != null;
    }

    public String requireCurrentConversationId() {
        if (currentConversationId == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                    "phase conversation must exist before submitting a run");
        }
        return currentConversationId;
    }
}
