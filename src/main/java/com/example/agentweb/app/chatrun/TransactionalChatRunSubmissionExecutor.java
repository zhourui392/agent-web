package com.example.agentweb.app.chatrun;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Single-instance submission gate. The lock covers the complete database transaction so
 * capacity checks and inserts cannot be interleaved by concurrent request threads.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class TransactionalChatRunSubmissionExecutor implements ChatRunSubmissionExecutor {

    private final TransactionTemplate transactions;
    private final Lock submissionLock = new ReentrantLock();

    public TransactionalChatRunSubmissionExecutor(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public ChatRunSubmission execute(final Supplier<ChatRunSubmission> action) {
        submissionLock.lock();
        try {
            return transactions.execute(status -> action.get());
        } finally {
            submissionLock.unlock();
        }
    }
}
