package com.example.agentweb.app.chatrun;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class TransactionalChatRunSubmissionExecutorTest {

    @Test
    void execute_should_serialize_submission_transactions() throws Exception {
        TransactionalChatRunSubmissionExecutor executor =
                new TransactionalChatRunSubmissionExecutor(new NoOpTransactionManager());
        ExecutorService callers = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<Future<ChatRunSubmission>> futures = new ArrayList<Future<ChatRunSubmission>>();
        try {
            for (int i = 0; i < 4; i++) {
                final int value = i;
                futures.add(callers.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return executor.execute(() -> {
                        int current = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(current, Math::max);
                        try {
                            Thread.yield();
                            com.example.agentweb.domain.chatrun.ChatRun run =
                                    com.example.agentweb.domain.chatrun.ChatRun.submit(
                                            com.example.agentweb.domain.chatrun.ChatRunId.of("run-" + value),
                                            "session-" + value, value + 1L, "key-" + value,
                                            java.time.Instant.parse("2026-07-22T10:00:00Z"));
                            return ChatRunSubmission.from(run, false);
                        } finally {
                            concurrent.decrementAndGet();
                        }
                    });
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (int i = 0; i < futures.size(); i++) {
                assertEquals("run-" + i, futures.get(i).get(5, TimeUnit.SECONDS).getRunId());
            }
            assertEquals(1, maxConcurrent.get());
        } finally {
            callers.shutdownNow();
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
