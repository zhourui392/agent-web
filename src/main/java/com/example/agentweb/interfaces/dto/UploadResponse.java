package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class UploadResponse {
    private boolean success;
    private String path;
    private long size;

    public UploadResponse(boolean success, String path, long size) {
        this.success = success;
        this.path = path;
        this.size = size;
    }
}
