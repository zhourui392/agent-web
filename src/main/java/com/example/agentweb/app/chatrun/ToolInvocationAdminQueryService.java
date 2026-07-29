package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocation;
import lombok.Getter;

import java.util.List;
import java.util.Map;

public interface ToolInvocationAdminQueryService {
    ToolInvocationAdminPage findPage(ToolInvocationAdminFilter filter);
    Map<String, Long> overview();
    ToolInvocation findById(long id);

    @Getter
    final class ToolInvocationAdminPage {
        private final List<ToolInvocationAdminRow> items;
        private final long total;
        private final int page;
        private final int size;

        public ToolInvocationAdminPage(List<ToolInvocationAdminRow> items, long total, int page, int size) {
            this.items = items;
            this.total = total;
            this.page = page;
            this.size = size;
        }
    }

    final class ToolInvocationAdminRow {
        private final ToolInvocation invocation;
        private final String displayToolName;
        private final String inputSummary;
        private final String outputSummary;

        public ToolInvocationAdminRow(ToolInvocation invocation, String displayToolName,
                                      String inputSummary, String outputSummary) {
            this.invocation = invocation;
            this.displayToolName = displayToolName;
            this.inputSummary = inputSummary;
            this.outputSummary = outputSummary;
        }

        public Long getId() { return invocation.getId(); }
        public String getSessionId() { return invocation.getSessionId(); }
        public String getRunId() { return invocation.getRunId(); }
        public String getProvider() { return invocation.getProvider().name(); }
        public String getInvocationKind() { return invocation.getInvocationKind().name(); }
        public String getToolName() { return invocation.getToolName(); }
        public String getDisplayToolName() { return displayToolName; }
        public String getSkillName() { return invocation.getSkillName(); }
        public String getStatus() { return invocation.getStatus().name(); }
        public String getTriggerSource() { return invocation.getTriggerSource().name(); }
        public Long getStartedAt() { return invocation.getStartedAt(); }
        public String getInputSummary() { return inputSummary; }
        public String getOutputSummary() { return outputSummary; }
    }
}
