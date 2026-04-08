package com.example.agentweb.interfaces.dto;

public class SendMessageResponse {
    private String output;

    public SendMessageResponse(String output) {
        this.output = output;
    }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
}
