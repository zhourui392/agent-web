package com.example.agentweb.app.agentrun;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Application-layer prompt assembly pipeline for AgentRun.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Service
@Slf4j
public class PromptAssemblyService {

    private final List<PromptContributor> contributors;

    public PromptAssemblyService(List<PromptContributor> contributors) {
        this.contributors = ordered(contributors);
    }

    public PromptAssemblyResult assemble(AgentRunContext context) {
        PromptAssembly assembly = new PromptAssembly(context);
        for (PromptContributor contributor : contributors) {
            contributor.append(assembly);
        }
        String prompt = join(assembly.parts());
        String hash = sha256Hex(prompt);
        log.info("agent-run-prompt-assembled runForm={} sourceDomain={} workingDir={} agentType={} env={} "
                        + "promptHash={} partCount={} guardrailSource={} historicalRagUsed={} "
                        + "workspaceContextDocs={} workspaceHits={} recallContributions={}",
                assembly.getContext().getRunForm(),
                assembly.getContext().getSourceDomain(),
                assembly.getContext().getWorkingDir(),
                assembly.getContext().getAgentType(),
                assembly.getContext().getEnv(),
                hash,
                assembly.parts().size(),
                assembly.guardrailSource(),
                assembly.getHistoricalRagUsed(),
                assembly.getWorkspaceContextDocs(),
                assembly.getWorkspaceKnowledgeHits().size(),
                assembly.recallContributions().size());
        return new PromptAssemblyResult(
                prompt,
                hash,
                assembly.parts(),
                assembly.getHistoricalRagUsed(),
                assembly.getHistoricalRagChunkIdsJson(),
                assembly.getWorkspaceKnowledgeHits(),
                assembly.getWorkspaceContextDocs(),
                assembly.recallContributions(),
                assembly.guardrailSource());
    }

    private List<PromptContributor> ordered(List<PromptContributor> source) {
        List<PromptContributor> list = new ArrayList<PromptContributor>(source);
        list.sort(Comparator.comparingInt(this::orderOf));
        return list;
    }

    private int orderOf(PromptContributor contributor) {
        if (contributor instanceof EnvPromptContributor) {
            return 10;
        }
        if (contributor instanceof WorkspaceContextContributor) {
            return 20;
        }
        if (contributor instanceof KnowledgePreRecallContributor) {
            return 30;
        }
        if (contributor instanceof HistoricalRagContributor) {
            return 40;
        }
        if (contributor instanceof UserInputContributor) {
            return 50;
        }
        if (contributor instanceof OutputInstructionContributor) {
            return 60;
        }
        return 100;
    }

    private String join(List<PromptPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (PromptPart part : parts) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(part.getContent());
        }
        return sb.toString();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
