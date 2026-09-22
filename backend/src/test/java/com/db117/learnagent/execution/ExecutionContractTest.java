package com.db117.learnagent.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionContractTest {
    @Test
    void requestCopiesArgumentsAndKeepsOperationStructured() {
        ArrayList<String> arguments = new ArrayList<>(java.util.List.of("src/index.ts"));
        ExecutionRequest request = new ExecutionRequest(ExecutionOperation.COMPILE, arguments);

        arguments.add("--watch");

        assertEquals(ExecutionOperation.COMPILE, request.operation());
        assertEquals(java.util.List.of("src/index.ts"), request.arguments());
    }

    @Test
    void requestRejectsBlankArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, java.util.List.of(" ")));
    }

    @Test
    void resultRejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionResult(false, 1, "failed", Duration.ofSeconds(-1)));
    }
}
