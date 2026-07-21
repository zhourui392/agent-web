package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流运行请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
@Setter
public class WorkflowRunRequest {

    private Map<String, Object> inputs = new HashMap<>();
}
