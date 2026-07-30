package com.example.agentweb.infra.chatrun;

import org.springframework.dao.DataAccessException;
import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Applies a small bounded retry to SQLite operations rejected by transient database locks.
 *
 * <p>{@code busy_timeout} covers ordinary {@code SQLITE_BUSY} waits, but SQLite shared-cache
 * table locks can return {@code SQLITE_LOCKED_SHAREDCACHE} immediately. Retrying only SQLite
 * base error codes 5 and 6 keeps constraint, syntax and connectivity failures fail-fast.</p>
 *
 * @author alex
 * @since 2026-07-30
 */
final class SqliteTransientLockRetry {

    private static final int MAX_ATTEMPTS = 6;
    private static final long INITIAL_BACKOFF_MILLIS = 10L;
    private static final long MAX_BACKOFF_MILLIS = 80L;
    private static final int SQLITE_BUSY_BASE_CODE = 5;
    private static final int SQLITE_LOCKED_BASE_CODE = 6;

    <T> T execute(Supplier<T> operation) {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DataAccessException failure) {
                if (attempt >= MAX_ATTEMPTS || !isTransientSqliteLock(failure)) {
                    throw failure;
                }
                pause(backoffMillis, failure);
                backoffMillis = Math.min(backoffMillis * 2L, MAX_BACKOFF_MILLIS);
            }
        }
    }

    private boolean isTransientSqliteLock(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLiteException) {
                SQLiteException sqliteFailure = (SQLiteException) current;
                if (isLockCode(sqliteFailure.getResultCode().code)) {
                    return true;
                }
            } else if (current instanceof SQLException) {
                SQLException sqlFailure = (SQLException) current;
                if (isLockCode(sqlFailure.getErrorCode())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isLockCode(int errorCode) {
        int baseCode = errorCode & 0xff;
        return baseCode == SQLITE_BUSY_BASE_CODE || baseCode == SQLITE_LOCKED_BASE_CODE;
    }

    private void pause(long backoffMillis, DataAccessException originalFailure) {
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            originalFailure.addSuppressed(interrupted);
            throw originalFailure;
        }
    }
}
