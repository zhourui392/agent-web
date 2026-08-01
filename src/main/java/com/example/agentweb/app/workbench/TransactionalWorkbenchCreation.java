package com.example.agentweb.app.workbench;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单实例内串行化 Workbench 创建并包裹 SQLite 事务。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class TransactionalWorkbenchCreation implements WorkbenchCreationTransaction {

    private final TransactionTemplate transactions;
    private final Lock creationLock = new ReentrantLock();

    public TransactionalWorkbenchCreation(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public WorkbenchCreationResult execute(Supplier<WorkbenchCreationResult> action) {
        if (action == null) {
            throw new IllegalArgumentException("workbench creation action is required");
        }
        creationLock.lock();
        try {
            return transactions.execute(status -> action.get());
        } finally {
            creationLock.unlock();
        }
    }
}
