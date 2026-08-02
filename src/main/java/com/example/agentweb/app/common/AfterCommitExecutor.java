package com.example.agentweb.app.common;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs infrastructure side effects only after the surrounding database transaction commits.
 *
 * <p>通用组件，chat 域和 workbench 域共用。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class AfterCommitExecutor {

    public void execute(final Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}