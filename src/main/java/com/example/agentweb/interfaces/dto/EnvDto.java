package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class EnvDto {
    private String key;
    private String label;
    private String color;

    public EnvDto(String key, String label, String color) {
        this.key = key;
        this.label = label;
        this.color = color;
    }
}
