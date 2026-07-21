package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class MessageDto {
    private Long id;
    private String role;
    private String content;
    private String timestamp;
    /** Public chat recall replay JSON, {@code {query,status,hits:[...]}}; present only on hit assistant messages. */
    private String recall;

    public MessageDto(String role, String content, String timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public MessageDto(Long id, String role, String content, String timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }
}
