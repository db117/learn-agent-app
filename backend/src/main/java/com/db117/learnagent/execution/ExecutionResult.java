package com.db117.learnagent.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * 执行结果摘要；完整 stdout/stderr 不属于默认长期保存的 Runtime 事实。
 *
 * @param success 操作是否成功
 * @param exitCode 进程退出码；尚未执行或无进程退出码时由实现约定
 * @param summary 面向上层的结果摘要
 * @param duration 执行耗时，不能为负数
 */
public record ExecutionResult(boolean success, int exitCode, String summary, Duration duration) {
    public ExecutionResult {
        summary = Objects.requireNonNull(summary, "summary must not be null");
        duration = Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }
}
