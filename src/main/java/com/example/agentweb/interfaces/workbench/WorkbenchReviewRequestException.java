package com.example.agentweb.interfaces.workbench;

/**
 * Review Owner HTTP 输入格式错误。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchReviewRequestException extends IllegalArgumentException {

    WorkbenchReviewRequestException() {
        super("workbench review request is invalid");
    }
}
