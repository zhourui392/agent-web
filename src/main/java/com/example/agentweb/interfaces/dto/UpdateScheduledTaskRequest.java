package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class UpdateScheduledTaskRequest {
    private String name;
    private String cronExpr;
    private String prompt;
    private String workingDir;
}
