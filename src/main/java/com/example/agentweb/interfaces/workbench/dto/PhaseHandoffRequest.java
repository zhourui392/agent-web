package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Phase Handoff 五类人工可编辑公开字段。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
@NoArgsConstructor
public final class PhaseHandoffRequest {

    @NotNull
    @Size(max = 8000)
    private String summary;

    @NotNull
    @Size(max = 50)
    private List<@NotNull @Valid DecisionRequest> decisions;

    @NotNull
    @Size(max = 50)
    private List<@NotNull @Valid OpenQuestionRequest> openQuestions;

    @NotNull
    @Size(max = 100)
    private List<@NotNull @Valid DocumentReferenceRequest> pinnedFiles;

    @NotNull
    @Size(max = 50)
    private List<@NotNull @Valid RunReferenceRequest> referencedRuns;

    @JsonAnySetter
    public void rejectInternalOrUnknownField(
            String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported phase handoff field: " + field);
    }

    /** Decision 输入。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static final class DecisionRequest {
        @NotBlank
        @Size(max = 2000)
        private String text;

        @Size(max = 2000)
        private String rationale;

        @JsonAnySetter
        public void rejectInternalOrUnknownField(
                String field, Object ignoredValue) {
            throw new IllegalArgumentException(
                    "unsupported phase handoff decision field: " + field);
        }
    }

    /** Open Question 输入。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static final class OpenQuestionRequest {
        @NotBlank
        @Size(max = 2000)
        private String text;

        @Size(max = 256)
        private String ownerHint;

        @JsonAnySetter
        public void rejectInternalOrUnknownField(
                String field, Object ignoredValue) {
            throw new IllegalArgumentException(
                    "unsupported phase handoff question field: " + field);
        }
    }

    /** Pinned File 输入。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static final class DocumentReferenceRequest {
        @NotBlank
        @Size(max = 160)
        private String repositoryKey;

        @NotBlank
        @Size(max = 4096)
        private String relativePath;

        @JsonAnySetter
        public void rejectInternalOrUnknownField(
                String field, Object ignoredValue) {
            throw new IllegalArgumentException(
                    "unsupported pinned file field: " + field);
        }
    }

    /** Referenced Run 输入只接受 runId。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static final class RunReferenceRequest {
        @NotBlank
        @Size(max = 128)
        private String runId;

        @JsonAnySetter
        public void rejectInternalOrUnknownField(
                String field, Object ignoredValue) {
            throw new IllegalArgumentException(
                    "unsupported referenced run field: " + field);
        }
    }
}
