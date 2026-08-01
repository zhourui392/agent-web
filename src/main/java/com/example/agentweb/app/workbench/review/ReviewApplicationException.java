package com.example.agentweb.app.workbench.review;

import lombok.Getter;

import java.util.Objects;

/**
 * Review Owner 应用异常；冲突时只携带安全 Current Opinion 投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewApplicationException extends IllegalStateException {

    private final ReviewApplicationErrorCode code;
    private final ReviewOpinionView currentOpinion;

    public ReviewApplicationException(
            ReviewApplicationErrorCode code, String message) {
        this(code, message, null);
    }

    public ReviewApplicationException(
            ReviewApplicationErrorCode code, String message,
            ReviewOpinionView currentOpinion) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.currentOpinion = currentOpinion;
    }
}
