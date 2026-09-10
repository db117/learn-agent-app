package com.example.agent.config;

import java.time.Instant;

/** 项目稳定错误类型；过期快照确认响应可附带两个时间戳。 */
public final class DatabaseImportException extends RuntimeException {

    private final String errorCode;
    private final Instant snapshotCreatedAt;
    private final Instant currentDatabaseAt;

    public DatabaseImportException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null, null);
    }

    public DatabaseImportException(
            String errorCode,
            String message,
            Throwable cause,
            Instant snapshotCreatedAt,
            Instant currentDatabaseAt) {
        super(message, cause);
        this.errorCode = errorCode;
        this.snapshotCreatedAt = snapshotCreatedAt;
        this.currentDatabaseAt = currentDatabaseAt;
    }

    public String errorCode() {
        return errorCode;
    }

    public Instant snapshotCreatedAt() {
        return snapshotCreatedAt;
    }

    public Instant currentDatabaseAt() {
        return currentDatabaseAt;
    }
}
