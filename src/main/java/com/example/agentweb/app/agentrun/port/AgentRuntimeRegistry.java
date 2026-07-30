package com.example.agentweb.app.agentrun.port;

import com.example.agentweb.domain.agentrun.AgentRuntimeAvailability;

import java.util.List;

/**
 * Read port exposing the currently registered agent runtimes.
 *
 * @author alex
 * @since 2026-07-29
 */
public interface AgentRuntimeRegistry {

    List<AgentRuntimeAvailability> availability();
}
