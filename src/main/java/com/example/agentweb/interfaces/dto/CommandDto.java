package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class CommandDto {
    private String name;
    private String description;
    private String argumentHint;

    public CommandDto(String name, String description, String argumentHint) {
        this.name = name;
        this.description = description;
        this.argumentHint = argumentHint;
    }
}
