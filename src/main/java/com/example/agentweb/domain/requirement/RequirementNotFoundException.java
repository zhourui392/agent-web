package com.example.agentweb.domain.requirement;

/**
 * 需求不存在。映射为 404。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementNotFoundException extends RuntimeException {

    public RequirementNotFoundException(String requirementId) {
        super("requirement not found: " + requirementId);
    }
}
