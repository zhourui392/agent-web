package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TruncateResult {
    private int deletedCount;
    private String prefillContent;
    private boolean resumeIdCleared;

    public TruncateResult(int deletedCount, String prefillContent, boolean resumeIdCleared) {
        this.deletedCount = deletedCount;
        this.prefillContent = prefillContent;
        this.resumeIdCleared = resumeIdCleared;
    }
}
