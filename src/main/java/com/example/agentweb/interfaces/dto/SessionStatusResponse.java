package com.example.agentweb.interfaces.dto;

public class SessionStatusResponse {
    private boolean running;

    public SessionStatusResponse(boolean running) {
        this.running = running;
    }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
}
