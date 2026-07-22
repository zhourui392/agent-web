package com.example.agentweb.app.chatrun;

/**
 * Raised when a submit command omits or exceeds the idempotency-key contract.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class InvalidIdempotencyKeyException extends IllegalArgumentException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must contain 1 to 128 characters");
    }
}
