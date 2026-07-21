package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class RunTaskResponse {
    private boolean success;
    private String message;

    public RunTaskResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}
