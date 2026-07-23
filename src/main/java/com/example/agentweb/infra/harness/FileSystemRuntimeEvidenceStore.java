package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.RuntimeEvidenceStore;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;

/**
 * 把 Runtime JSONL 作为敏感审计证据写入 Harness 受控 Artifact Store。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemRuntimeEvidenceStore implements RuntimeEvidenceStore {

    private static final String CREATOR = "harness-runtime";

    private final ArtifactStore artifactStore;

    public FileSystemRuntimeEvidenceStore(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Override
    public String store(AgentExecutionSpec spec, byte[] redactedJsonl, Instant createdAt) {
        ArtifactContent content = ArtifactContent.from(redactedJsonl);
        String artifactId = "runtime-jsonl-" + spec.getExecutionId();
        ArtifactDescriptor descriptor = new ArtifactDescriptor(artifactId,
                ArtifactType.TEST_EVIDENCE, 1, spec.getRunId(), spec.getStage(),
                spec.getAttemptNumber(), "application/octet-stream", content.getSizeBytes(),
                content.getSha256(), ArtifactClassification.SENSITIVE, CREATOR, createdAt,
                Collections.<ArtifactReference>emptyList());
        artifactStore.store(descriptor, content);
        return "artifact:" + artifactId + ":1:" + content.getSha256();
    }
}
