package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import lombok.Getter;

public interface ToolInvocationEvent {

    @Getter
    final class Started implements ToolInvocationEvent {
        private final String callId;
        private final ToolInvocationKind kind;
        private final String toolName;
        private final String itemType;
        private final String initialInputJson;

        public Started(String callId, ToolInvocationKind kind, String toolName,
                       String itemType, String initialInputJson) {
            this.callId = callId;
            this.kind = kind;
            this.toolName = toolName;
            this.itemType = itemType;
            this.initialInputJson = initialInputJson;
        }
    }

    @Getter
    final class InputDelta implements ToolInvocationEvent {
        private final String callId;
        private final String partialJson;

        public InputDelta(String callId, String partialJson) {
            this.callId = callId;
            this.partialJson = partialJson;
        }
    }

    @Getter
    final class Completed implements ToolInvocationEvent {
        private final String callId;
        private final String outputText;
        private final boolean error;
        private final Integer exitCode;
        private final String providerStatus;

        public Completed(String callId, String outputText, boolean error,
                         Integer exitCode, String providerStatus) {
            this.callId = callId;
            this.outputText = outputText;
            this.error = error;
            this.exitCode = exitCode;
            this.providerStatus = providerStatus;
        }
    }
}
