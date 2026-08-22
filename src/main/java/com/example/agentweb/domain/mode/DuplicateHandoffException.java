package com.example.agentweb.domain.mode;

/**
 * 同一 (源会话, 幂等键) 已存在交接记录（并发重复提交由唯一索引兜底）。
 *
 * @author alex
 * @since 2026-08-20
 */
public class DuplicateHandoffException extends RuntimeException {

    public DuplicateHandoffException(String message) {
        super(message);
    }
}
