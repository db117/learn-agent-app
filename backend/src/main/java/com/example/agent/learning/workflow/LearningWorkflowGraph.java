package com.example.agent.learning.workflow;

import com.alibaba.cloud.ai.graph.StateGraph;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Executes one learning action as a short-lived SAA Graph invocation. */
public final class LearningWorkflowGraph {

    public <T> T execute(
            String action,
            Supplier<Action<T>> actionNode,
            Map<String, Consumer<T>> routeNodes) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionNode, "actionNode");
        if (routeNodes == null || routeNodes.isEmpty()) throw new IllegalArgumentException("workflow routes are required");

        AtomicReference<Action<T>> result = new AtomicReference<>();
        try {
            StateGraph graph = new StateGraph();
            graph.addNode("action", state -> {
                Action<T> next = Objects.requireNonNull(actionNode.get(), "workflow action result");
                if (!routeNodes.containsKey(next.route())) {
                    throw new IllegalStateException("Unknown workflow route: " + next.route());
                }
                result.set(next);
                return CompletableFuture.completedFuture(Map.of("route", next.route()));
            });
            for (Map.Entry<String, Consumer<T>> entry : routeNodes.entrySet()) {
                String route = entry.getKey();
                Consumer<T> node = entry.getValue();
                graph.addNode(
                        route,
                        state -> {
                            node.accept(result.get().value());
                            return CompletableFuture.completedFuture(Map.of("route", route));
                        });
            }
            graph.addEdge(StateGraph.START, "action");
            Map<String, String> routes = routeNodes.keySet().stream()
                    .collect(Collectors.toMap(Function.identity(), Function.identity()));
            graph.addConditionalEdges(
                    "action",
                    state -> CompletableFuture.completedFuture(state.<String>value("route").orElseThrow()),
                    routes);
            for (String route : routeNodes.keySet()) graph.addEdge(route, StateGraph.END);
            graph.compile().invoke(Map.of("action", action));
            return result.get().value();
        } catch (Exception error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Learning workflow graph failed for action: " + action, error);
        }
    }

    public record Action<T>(String route, T value) {
        public Action {
            if (route == null || route.isBlank()) throw new IllegalArgumentException("workflow route is required");
        }
    }
}
