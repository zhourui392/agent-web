package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次公共 Agent Runtime 执行所需的完整、不可变事实。
 *
 * <p>Chat 与 Workbench 各自负责完成业务校验并组装本对象；Runtime 只执行该计划，
 * 不从缺省值推断来源流程、写权限或能力选择。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AgentExecutionPlan {

    private final ExecutionIdentity executionIdentity;
    private final RuntimeSelection runtimeSelection;
    private final PromptPayload promptPayload;
    private final WorkspaceLayout workspaceLayout;
    private final ResolvedCapabilityBinding capabilityBinding;
    private final RuntimeLimits runtimeLimits;
    private final List<RuntimeAttachmentExpectation> attachmentExpectations;

    public AgentExecutionPlan(ExecutionIdentity executionIdentity,
                              RuntimeSelection runtimeSelection,
                              PromptPayload promptPayload,
                              WorkspaceLayout workspaceLayout,
                              ResolvedCapabilityBinding capabilityBinding,
                              RuntimeLimits runtimeLimits) {
        this(executionIdentity, runtimeSelection, promptPayload,
                workspaceLayout, capabilityBinding, runtimeLimits,
                Collections.<RuntimeAttachmentExpectation>emptyList());
    }

    public AgentExecutionPlan(
            ExecutionIdentity executionIdentity,
            RuntimeSelection runtimeSelection,
            PromptPayload promptPayload,
            WorkspaceLayout workspaceLayout,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits,
            List<RuntimeAttachmentExpectation> attachmentExpectations) {
        this.executionIdentity = Objects.requireNonNull(
                executionIdentity, "executionIdentity");
        this.runtimeSelection = Objects.requireNonNull(runtimeSelection, "runtimeSelection");
        this.promptPayload = Objects.requireNonNull(promptPayload, "promptPayload");
        this.workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout");
        this.capabilityBinding = Objects.requireNonNull(
                capabilityBinding, "capabilityBinding");
        this.runtimeLimits = Objects.requireNonNull(runtimeLimits, "runtimeLimits");
        this.attachmentExpectations = immutableAttachments(
                attachmentExpectations, workspaceLayout);
    }

    private static List<RuntimeAttachmentExpectation> immutableAttachments(
            List<RuntimeAttachmentExpectation> attachments,
            WorkspaceLayout workspaceLayout) {
        if (attachments == null || attachments.contains(null)
                || attachments.size() > 8) {
            throw new IllegalArgumentException(
                    "runtime attachment expectations must be complete and bounded");
        }
        Set<String> unique = new HashSet<String>();
        for (RuntimeAttachmentExpectation attachment : attachments) {
            if (attachment.isRepositoryDocument()
                    && !workspaceLayout.getReadableRoots().contains(
                    attachment.getRepositoryRoot())) {
                throw new IllegalArgumentException(
                        "runtime attachment repository must be readable");
            }
            if (!unique.add(attachment.logicalIdentity())) {
                throw new IllegalArgumentException(
                        "runtime attachment expectations must be unique");
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<RuntimeAttachmentExpectation>(attachments));
    }
}
