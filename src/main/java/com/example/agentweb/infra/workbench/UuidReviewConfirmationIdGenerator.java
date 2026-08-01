package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.review.ReviewConfirmationIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Review Modify Confirmation 的 UUID ID 生成适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class UuidReviewConfirmationIdGenerator
        implements ReviewConfirmationIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
