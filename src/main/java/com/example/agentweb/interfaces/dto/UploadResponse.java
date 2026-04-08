package com.example.agentweb.interfaces.dto;

public class UploadResponse {
    private boolean success;
    private String path;
    private long size;

    public UploadResponse(boolean success, String path, long size) {
        this.success = success;
        this.path = path;
        this.size = size;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
}
