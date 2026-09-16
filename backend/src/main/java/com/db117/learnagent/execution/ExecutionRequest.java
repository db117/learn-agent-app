package com.db117.learnagent.execution;

import java.util.List;
import java.util.Objects;

/**
 * 执行环境的结构化请求；操作由枚举限定，参数只作为未来操作的受限参数传递。
 *
 * @param operation 受限执行操作
 * @param arguments 操作参数，不包含可直接执行的完整 shell 命令
 */
public record ExecutionRequest(ExecutionOperation operation, List<String> arguments) {
    public ExecutionRequest {
        operation = Objects.requireNonNull(operation, "operation must not be null");
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        if (arguments.stream().anyMatch(argument -> argument == null || argument.isBlank())) {
            throw new IllegalArgumentException("arguments must not contain blank values");
        }
    }
}
