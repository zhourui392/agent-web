package com.example.agentweb.interfaces.workbench.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * Workbench Phase Run 提交请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
public class SubmitWorkbenchRunRequest {

    @NotBlank
    @Size(max = 32000)
    private String message;

    @NotBlank
    @Size(max = 64)
    private String runMode;

    @PositiveOrZero
    private Long handoffSourceVersion;

    @Size(max = 128)
    private String reviewConfirmationId;

    @Valid
    @NotNull
    @Size(max = 8)
    private List<WorkbenchRunAttachmentRequest> attachments =
            Collections.emptyList();
}
