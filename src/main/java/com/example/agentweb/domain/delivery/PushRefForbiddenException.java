package com.example.agentweb.domain.delivery;

/**
 * push ref 违反 req/* 白名单。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class PushRefForbiddenException extends RuntimeException {

    public PushRefForbiddenException(String branch) {
        super("push 目标分支不在 req/* 白名单内: " + branch);
    }
}
