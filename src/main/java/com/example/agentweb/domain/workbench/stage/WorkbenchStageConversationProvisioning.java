package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

import java.time.Instant;

/**
 * Workbench 聚合为动态 Stage Session 提供的可信装配事实。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationProvisioning {

    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final String definitionIdentifier;
    private final OwnerReference owner;
    private final AgentType agentType;
    private final String environment;
    private final String primaryRepositoryRoot;
    private final String contextId;
    private final String currentConversationId;
    private final Instant currentConversationCreatedAt;
    private final int currentConversationGeneration;
    private final long workbenchVersion;

    private WorkbenchStageConversationProvisioning(
            Workbench workbench, WorkbenchStageState stage,
            WorkbenchStageConversationReference currentConversation) {
        this.workbenchId = workbench.getId();
        this.stageInstanceIdentifier = stage.getStageInstanceIdentifier();
        this.definitionIdentifier =
                stage.getSnapshot().getDefinitionIdentifier();
        this.owner = workbench.getOwner();
        this.agentType = workbench.getAgentType();
        this.environment = workbench.getEnvironment();
        this.primaryRepositoryRoot = DomainText.require(
                workbench.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                "Stage conversation primary repository root", 4096);
        this.contextId = DomainText.require(
                workbenchId.getValue() + ":" + stageInstanceIdentifier,
                "Stage conversation context identifier", 512);
        this.currentConversationId = currentConversation == null
                ? null : currentConversation.getConversationId();
        this.currentConversationCreatedAt = currentConversation == null
                ? null : currentConversation.getCreatedAt();
        this.currentConversationGeneration = currentConversation == null
                ? 0 : currentConversation.getGeneration();
        this.workbenchVersion = workbench.getVersion();
    }

    public static WorkbenchStageConversationProvisioning plan(
            Workbench workbench, WorkbenchStageState stage,
            WorkbenchStageConversationReference currentConversation) {
        if (workbench == null || stage == null) {
            throw new IllegalArgumentException(
                    "Stage conversation provisioning facts are required");
        }
        return new WorkbenchStageConversationProvisioning(
                workbench, stage, currentConversation);
    }

    public boolean hasCurrentConversation() {
        return currentConversationId != null;
    }

    public String requireCurrentConversationId() {
        if (currentConversationId == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.STAGE_TRANSITION_INVALID,
                    "Stage conversation must exist before submitting a Run");
        }
        return currentConversationId;
    }
}
