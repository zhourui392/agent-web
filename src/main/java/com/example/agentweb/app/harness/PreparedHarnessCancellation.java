package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.CancellationDirective;
import lombok.Getter;

/**
 * 取消意图事务提交后交给 Launcher 的领域指令和响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class PreparedHarnessCancellation {

    private final HarnessMutationResult result;
    private final CancellationDirective directive;

    public PreparedHarnessCancellation(HarnessMutationResult result,
                                       CancellationDirective directive) {
        if (result == null || directive == null) {
            throw new IllegalArgumentException("prepared cancellation result is incomplete");
        }
        this.result = result;
        this.directive = directive;
    }
}
