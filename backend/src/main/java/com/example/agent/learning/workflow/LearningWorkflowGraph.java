package com.example.agent.learning.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 执行一次学习动作，并将结果路由到确定性的状态转换处理器。 */
public final class LearningWorkflowGraph {

    /**
     * 执行一个动作节点，再根据节点返回的 route 调用对应的确定性处理器。
     *
     * <p>动作节点负责产生业务结果，路由处理器负责副作用；未知 route 会立即失败，避免状态转换被静默跳过。</p>
     *
     * @param action 动作名称
     * @param actionNode 产生动作结果的节点
     * @param routeNodes 按 route 分派结果的处理器
     * @return 动作节点返回的业务值
     */
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
