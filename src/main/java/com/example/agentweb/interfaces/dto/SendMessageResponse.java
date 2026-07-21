package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class SendMessageResponse {
    private String output;

    public SendMessageResponse(String output) {
        this.output = output;
    }
}
