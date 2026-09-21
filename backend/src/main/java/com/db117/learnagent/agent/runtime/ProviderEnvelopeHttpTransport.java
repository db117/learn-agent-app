package com.db117.learnagent.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

/**
 * 把供应商以 HTTP 200 返回的错误 envelope 转换为 AgentScope 可识别的 HTTP 异常。
 *
 * <p>供应商路由失败时返回 {@code result=-1} 和 {@code gpt_status>=400}，外层 HTTP 状态仍是 200；
 * 如果不转换，OpenAI adapter 会把它当成空模型响应，无法进入默认重试判定。
 */
final class ProviderEnvelopeHttpTransport implements HttpTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpTransport delegate;

    ProviderEnvelopeHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        var response = delegate.execute(normalizeToolSchemas(request));
        if (!response.isSuccessful() || !isProviderError(response.getBody())) {
            return response;
        }
        return HttpResponse.builder()
                .statusCode(502)
                .headers(response.getHeaders())
                .body(response.getBody())
                .build();
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(normalizeToolSchemas(request))
                .handle((data, sink) -> {
                    if (isProviderError(data)) {
                        sink.error(new HttpTransportException(
                                "Provider returned an error envelope", 502, data));
                    } else {
                        sink.next(data);
                    }
                });
    }

    static boolean isProviderError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        var payload = body.trim();
        if (payload.startsWith("data:")) {
            payload = payload.substring("data:".length()).trim();
        }
        if ("[DONE]".equals(payload)) {
            return false;
        }
        try {
            JsonNode response = JSON.readTree(payload);
            return response != null
                    && ((response.has("result") && response.path("result").asInt(0) < 0)
                    || (response.has("gpt_status") && response.path("gpt_status").asInt(200) >= 400));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 兼容当前 OpenAI-compatible 网关只接受基础工具参数 schema 的限制；工具实现仍在服务端校验真实参数。
     */
    private static HttpRequest normalizeToolSchemas(HttpRequest request) {
        if (request.getBody() == null || !request.getBody().contains("\"tools\"")) {
            return request;
        }
        try {
            var root = JSON.readTree(request.getBody());
            var tools = root == null ? null : root.get("tools");
            var changed = false;
            if (tools != null && tools.isArray()) {
                for (var tool : tools) {
                    var parameters = tool.path("function").get("parameters");
                    if (parameters != null) {
                        changed |= stripUnsupportedKeywords(parameters);
                    }
                }
            }
            if (!changed) {
                return request;
            }
            return HttpRequest.builder()
                    .url(request.getUrl())
                    .method(request.getMethod())
                    .headers(request.getHeaders())
                    .body(JSON.writeValueAsString(root))
                    .build();
        } catch (Exception ignored) {
            return request;
        }
    }

    private static boolean stripUnsupportedKeywords(JsonNode node) {
        var changed = false;
        if (node instanceof ObjectNode object) {
            changed = object.remove("required") != null;
            changed |= object.remove("enum") != null;
            changed |= object.remove("description") != null;
            var fields = object.fields();
            while (fields.hasNext()) {
                changed |= stripUnsupportedKeywords(fields.next().getValue());
            }
        } else if (node != null && node.isArray()) {
            for (var child : node) {
                changed |= stripUnsupportedKeywords(child);
            }
        }
        return changed;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
