package com.example.agentweb.interfaces.dto;

import javax.validation.constraints.NotBlank;

public class SendMessageRequest {
    @NotBlank
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
