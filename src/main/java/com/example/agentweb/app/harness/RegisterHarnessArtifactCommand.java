package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 注册不可变 Artifact 版本的应用命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RegisterHarnessArtifactCommand {

    private final String runId;
    private final HarnessStage stage;
    private final ArtifactType artifactType;
    private final byte[] content;
    private final String contentType;
    private final ArtifactClassification classification;
    private final List<ArtifactReference> sourceArtifacts;

    public RegisterHarnessArtifactCommand(String runId, HarnessStage stage,
                                          ArtifactType artifactType, byte[] content,
                                          String contentType,
                                          ArtifactClassification classification,
                                          List<ArtifactReference> sourceArtifacts) {
        this.runId = runId;
        this.stage = stage;
        this.artifactType = artifactType;
        this.content = content == null ? null : content.clone();
        this.contentType = contentType;
        this.classification = classification;
        this.sourceArtifacts = sourceArtifacts == null ? null
                : Collections.unmodifiableList(new ArrayList<ArtifactReference>(sourceArtifacts));
    }

    public byte[] getContent() {
        return content == null ? null : content.clone();
    }
}
