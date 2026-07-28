package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.shared.AgentType;

import java.util.List;

public interface ToolInvocationEventExtractor {

    List<ToolInvocationEvent> extract(AgentType provider, String rawLine);
}
