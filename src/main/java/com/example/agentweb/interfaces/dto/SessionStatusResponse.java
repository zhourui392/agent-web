package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class SessionStatusResponse {
    private boolean running;

    public SessionStatusResponse(boolean running) {
        this.running = running;
    }
}
