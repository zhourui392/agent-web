package com.example.agentweb.interfaces.dto;

public class EnvDto {
    private String key;
    private String label;
    private String color;

    public EnvDto(String key, String label, String color) {
        this.key = key;
        this.label = label;
        this.color = color;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
}
