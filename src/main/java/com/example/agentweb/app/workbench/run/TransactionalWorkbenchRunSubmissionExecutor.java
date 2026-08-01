package com.example.agentweb.app.workbench.run;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Workbench Run 提交的单实例串行事务边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class TransactionalWorkbenchRunSubmissionExecutor
        implements WorkbenchRunSubmissionExecutor {

    private final TransactionTemplate transactions;
    private final Lock submissionLock = new ReentrantLock();

    public TransactionalWorkbenchRunSubmissionExecutor(
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public WorkbenchRunSubmissionResult execute(
            Supplier<WorkbenchRunSubmissionResult> action) {
        submissionLock.lock();
        try {
            return transactions.execute(status -> action.get());
        } finally {
            submissionLock.unlock();
        }
    }
}
