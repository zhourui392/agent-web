package com.example.agentweb.interfaces.dto;

public class CommandDto {
    private String name;
    private String description;
    private String argumentHint;

    public CommandDto(String name, String description, String argumentHint) {
        this.name = name;
        this.description = description;
        this.argumentHint = argumentHint;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArgumentHint() { return argumentHint; }
    public void setArgumentHint(String argumentHint) { this.argumentHint = argumentHint; }
}
