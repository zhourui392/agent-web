package com.example.agentweb.app.workflow;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工作流运行命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowRunCommand {

    private final Map<String, Object> inputs;

    /**
     * 创建运行命令。
     *
     * @param inputs 运行输入
     */
    public WorkflowRunCommand(Map<String, Object> inputs) {
        this.inputs = inputs == null ? Collections.emptyMap() : new HashMap<>(inputs);
    }
}
