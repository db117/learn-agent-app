package com.example.agent.learning.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Executes one learning action and routes its result to the deterministic transition handler. */
public final class LearningWorkflowGraph {

    public <T> T execute(
            String action,
            Supplier<Action<T>> actionNode,
            Map<String, Consumer<T>> routeNodes) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionNode, "actionNode");
        if (routeNodes == null || routeNodes.isEmpty()) throw new IllegalArgumentException("workflow routes are required");

        Action<T> result = Objects.requireNonNull(actionNode.get(), "workflow action result");
        if (!routeNodes.containsKey(result.route())) {
            throw new IllegalStateException("Unknown workflow route: " + result.route());
        }
        routeNodes.get(result.route()).accept(result.value());
        return result.value();
    }

    public record Action<T>(String route, T value) {
        public Action {
            if (route == null || route.isBlank()) throw new IllegalArgumentException("workflow route is required");
        }
    }
}
