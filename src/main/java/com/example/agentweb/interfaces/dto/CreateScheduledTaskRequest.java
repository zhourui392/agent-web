package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class CreateScheduledTaskRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String cronExpr;
    @NotBlank
    private String prompt;
    @NotBlank
    private String workingDir;
}
