package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class SendMessageRequest {
    @NotBlank
    private String message;
}
