package com.example.agentweb.app.agentrun;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Discovers workspace context and appends its summary.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
@Slf4j
public class WorkspaceContextContributor implements PromptContributor {

    private final WorkspaceContextResolver resolver;

    public WorkspaceContextContributor(WorkspaceContextResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void append(PromptAssembly assembly) {
        if (!assembly.getContext().getRecallPolicy().isWorkspaceContextEnabled()) {
            return;
        }
        try {
            WorkspaceContext context = assembly.getContext().getWorkspaceContext();
            if (context == null) {
                context = resolver.resolve(assembly.getContext().getWorkingDir());
                assembly.setContext(assembly.getContext().withWorkspaceContext(context));
            }
            applyWorkspaceGuardrail(assembly, context);
            for (WorkspaceKnowledgeIndex index : context.getKnowledgeIndexes()) {
                assembly.addWorkspaceContextDoc(index.getRelativePath());
            }
            assembly.addPart(PromptPartType.WORKSPACE_CONTEXT, "Workspace Context", context.summary());
        } catch (RuntimeException e) {
            log.warn("workspace-context-contributor-failed workingDir={} reason={}",
                    assembly.getContext().getWorkingDir(), e.getMessage(), e);
        }
    }

    private void applyWorkspaceGuardrail(PromptAssembly assembly, WorkspaceContext context) {
        context.guardrailFor(assembly.getContext().getEnv()).ifPresent(guardrail -> {
            StringBuilder sb = new StringBuilder();
            if (guardrail.isReadonly()) {
                sb.append("[Workspace Guardrail]\nreadonly: true\n");
            }
            if (guardrail.getPrompt() != null && !guardrail.getPrompt().trim().isEmpty()) {
                sb.append(guardrail.getPrompt());
            }
            if (sb.length() > 0) {
                assembly.addPart(PromptPartType.ENV, "Workspace Guardrail", sb.toString());
                assembly.markWorkspaceGuardrailApplied();
            }
        });
    }
}
