package com.db117.learnagent.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/** 在 AgentScope Chat adapter 与 OpenAI Responses API 之间转换协议结构。 */
final class OpenAIResponsesHttpTransport implements HttpTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpTransport delegate;

    OpenAIResponsesHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        HttpResponse response = delegate.execute(toResponsesRequest(request));
        if (!response.isSuccessful()) {
            return response;
        }
        return HttpResponse.builder()
                .statusCode(response.getStatusCode())
                .headers(response.getHeaders())
                .body(toChatResponse(response.getBody()))
                .build();
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        HttpRequest responsesRequest = toResponsesRequest(request);
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return delegate.stream(responsesRequest)
                    .handle((data, sink) -> {
                        String chatChunk = toChatStreamChunk(data, state);
                        if (chatChunk != null) {
                            sink.next(chatChunk);
                        }
                    });
        });
    }

    private static HttpRequest toResponsesRequest(HttpRequest request) {
        ObjectNode source = parseObject(request.getBody(), "Invalid Chat Completions request");
        ObjectNode target = JSON.createObjectNode();
        copy(source, target, "model", "model");
        target.set("input", responsesInput(source.path("messages")));
        copy(source, target, "stream", "stream");
        copy(source, target, "temperature", "temperature");
        copy(source, target, "top_p", "top_p");
        copy(source, target, "max_completion_tokens", "max_output_tokens");
        if (!target.has("max_output_tokens")) {
            copy(source, target, "max_tokens", "max_output_tokens");
        }
        copy(source, target, "parallel_tool_calls", "parallel_tool_calls");
        copy(source, target, "store", "store");
        mapTools(source.get("tools"), target);
        mapToolChoice(source.get("tool_choice"), target);
        mapTextFormat(source.get("response_format"), target);
        mapReasoning(source.get("reasoning_effort"), target);
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(request.getHeaders())
                .body(writeJson(target))
                .build();
    }

    private static ArrayNode responsesInput(JsonNode messages) {
        ArrayNode input = JSON.createArrayNode();
        if (!messages.isArray()) {
            return input;
        }
        // Chat 的工具历史要拆成 Responses function_call 与 function_call_output 才能续接。
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            if ("tool".equals(role)) {
                ObjectNode output = input.addObject();
                output.put("type", "function_call_output");
                copy(message, output, "tool_call_id", "call_id");
                copy(message, output, "content", "output");
                if (!output.has("output")) {
                    output.put("output", "");
                }
                continue;
            }

            JsonNode toolCalls = message.get("tool_calls");
            if (!"assistant".equals(role) || toolCalls == null || !toolCalls.isArray()) {
                input.add(message.deepCopy());
                continue;
            }

            JsonNode content = message.get("content");
            if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                ObjectNode assistantMessage = ((ObjectNode) message).deepCopy();
                assistantMessage.remove("tool_calls");
                input.add(assistantMessage);
            }
            for (JsonNode toolCall : toolCalls) {
                ObjectNode functionCall = input.addObject();
                functionCall.put("type", "function_call");
                copy(toolCall, functionCall, "id", "call_id");
                JsonNode function = toolCall.path("function");
                copy(function, functionCall, "name", "name");
                copy(function, functionCall, "arguments", "arguments");
            }
        }
        return input;
    }

    private static void mapTools(JsonNode tools, ObjectNode target) {
        if (tools == null || !tools.isArray()) {
            return;
        }
        ArrayNode responsesTools = target.putArray("tools");
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            if (!function.isObject()) {
                responsesTools.add(tool.deepCopy());
                continue;
            }
            ObjectNode responseTool = responsesTools.addObject();
            responseTool.put("type", "function");
            function.fields().forEachRemaining(field ->
                    responseTool.set(field.getKey(), field.getValue().deepCopy()));
        }
    }

    private static void mapToolChoice(JsonNode choice, ObjectNode target) {
        if (choice == null || choice.isNull()) {
            return;
        }
        if (choice.isObject() && "function".equals(choice.path("type").asText())) {
            ObjectNode mapped = target.putObject("tool_choice");
            mapped.put("type", "function");
            copy(choice.path("function"), mapped, "name", "name");
        } else {
            target.set("tool_choice", choice.deepCopy());
        }
    }

    private static void mapTextFormat(JsonNode format, ObjectNode target) {
        if (format == null || !format.isObject()) {
            return;
        }
        // Responses 把 Chat 的 response_format 放到 text.format，并将 schema 配置扁平化。
        ObjectNode text = target.putObject("text");
        ObjectNode responseFormat = text.putObject("format");
        String type = format.path("type").asText();
        responseFormat.put("type", type);
        if ("json_schema".equals(type)) {
            JsonNode schema = format.path("json_schema");
            copy(schema, responseFormat, "name", "name");
            copy(schema, responseFormat, "description", "description");
            copy(schema, responseFormat, "schema", "schema");
            copy(schema, responseFormat, "strict", "strict");
        }
    }

    private static void mapReasoning(JsonNode effort, ObjectNode target) {
        if (effort == null || effort.isNull()) {
            return;
        }
        target.putObject("reasoning").set("effort", effort.deepCopy());
    }

    private static String toChatResponse(String body) {
        ObjectNode response = parseObject(body, "Invalid Responses API response");
        JsonNode error = response.get("error");
        if ((error != null && !error.isNull()) || "failed".equals(response.path("status").asText())) {
            return writeJson(chatError(error, "Responses API request failed"));
        }

        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            throw new HttpTransportException("Responses API response has no output array", 502, body);
        }

        ObjectNode chatResponse = JSON.createObjectNode();
        copy(response, chatResponse, "id", "id");
        chatResponse.put("object", "chat.completion");
        copy(response, chatResponse, "created_at", "created");
        copy(response, chatResponse, "model", "model");

        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = JSON.createArrayNode();
        String refusal = null;
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())) {
                ObjectNode toolCall = toolCalls.addObject();
                copy(item, toolCall, "call_id", "id");
                if (!toolCall.has("id")) {
                    copy(item, toolCall, "id", "id");
                }
                toolCall.put("type", "function");
                ObjectNode function = toolCall.putObject("function");
                copy(item, function, "name", "name");
                function.put("arguments", argumentText(item.get("arguments")));
            } else if ("message".equals(item.path("type").asText())) {
                JsonNode contents = item.path("content");
                if (contents.isArray()) {
                    for (JsonNode part : contents) {
                        String partType = part.path("type").asText();
                        if ("output_text".equals(partType)) {
                            text.append(part.path("text").asText());
                        } else if ("refusal".equals(partType)) {
                            refusal = part.path("refusal").asText();
                        }
                    }
                } else if (contents.isTextual()) {
                    text.append(contents.asText());
                }
            }
        }

        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        if (!text.isEmpty()) {
            message.put("content", text.toString());
        }
        if (refusal != null) {
            message.put("refusal", refusal);
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }

        ObjectNode choice = JSON.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
        if ("incomplete".equals(response.path("status").asText())) {
            finishReason = "length";
        }
        choice.put("finish_reason", finishReason);
        chatResponse.putArray("choices").add(choice);
        mapUsage(response.get("usage"), chatResponse);
        return writeJson(chatResponse);
    }

    private static String toChatStreamChunk(String data, StreamState state) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return null;
        }
        String json = data.trim();
        if (json.startsWith("data:")) {
            json = json.substring("data:".length()).trim();
        }
        ObjectNode event = parseObject(json, "Invalid Responses API stream event");
        // AgentScope 只解析 Chat chunk；将 Responses 语义事件投影为文本、工具和结束事件。
        String type = event.path("type").asText();
        String responseId = event.path("response_id").asText();
        if (!responseId.isBlank() && state.id == null) {
            state.id = responseId;
        }

        if ("response.created".equals(type) || "response.in_progress".equals(type)) {
            state.capture(event.path("response"));
            return null;
        }
        if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
            ObjectNode delta = JSON.createObjectNode();
            delta.put("content", event.path("delta").asText());
            return writeJson(streamChunk(state, delta, null, null));
        }
        if ("response.output_item.added".equals(type)) {
            JsonNode item = event.path("item");
            if (!"function_call".equals(item.path("type").asText())) {
                return null;
            }
            state.hasToolCall = true;
            int index = state.toolIndex(event.path("output_index").asInt());
            ObjectNode delta = JSON.createObjectNode();
            ObjectNode toolCall = delta.putArray("tool_calls").addObject();
            toolCall.put("index", index);
            copy(item, toolCall, "call_id", "id");
            toolCall.put("type", "function");
            ObjectNode function = toolCall.putObject("function");
            copy(item, function, "name", "name");
            function.put("arguments", argumentText(item.get("arguments")));
            return writeJson(streamChunk(state, delta, null, null));
        }
        if ("response.function_call_arguments.delta".equals(type)) {
            state.hasToolCall = true;
            ObjectNode delta = JSON.createObjectNode();
            ObjectNode toolCall = delta.putArray("tool_calls").addObject();
            toolCall.put("index", state.toolIndex(event.path("output_index").asInt()));
            toolCall.putObject("function").put("arguments", event.path("delta").asText());
            return writeJson(streamChunk(state, delta, null, null));
        }
        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            JsonNode response = event.path("response");
            state.capture(response);
            state.hasToolCall |= hasFunctionCall(response.path("output"));
            String finishReason = "response.incomplete".equals(type)
                    ? "length"
                    : state.hasToolCall ? "tool_calls" : "stop";
            ObjectNode usage = mapUsage(response.get("usage"));
            return writeJson(streamChunk(state, JSON.createObjectNode(), finishReason, usage));
        }
        if ("response.failed".equals(type) || "error".equals(type)) {
            JsonNode error = event.path("response").path("error");
            if (error.isMissingNode() || error.isNull()) {
                error = event.get("error");
            }
            if (error == null || error.isNull()) {
                error = event;
            }
            return writeJson(chatError(error, "Responses API request failed"));
        }
        return null;
    }

    private static ObjectNode streamChunk(
            StreamState state, ObjectNode delta, String finishReason, ObjectNode usage) {
        ObjectNode chunk = JSON.createObjectNode();
        if (state.id != null) {
            chunk.put("id", state.id);
        }
        chunk.put("object", "chat.completion.chunk");
        if (state.created != null) {
            chunk.set("created", JSON.getNodeFactory().numberNode(state.created));
        }
        if (state.model != null) {
            chunk.put("model", state.model);
        }
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        if (usage != null && !usage.isEmpty()) {
            chunk.set("usage", usage);
        }
        return chunk;
    }

    private static ObjectNode mapUsage(JsonNode source) {
        if (source == null || !source.isObject()) {
            return null;
        }
        ObjectNode usage = JSON.createObjectNode();
        copy(source, usage, "input_tokens", "prompt_tokens");
        copy(source, usage, "output_tokens", "completion_tokens");
        copy(source, usage, "total_tokens", "total_tokens");
        return usage;
    }

    private static void mapUsage(JsonNode source, ObjectNode target) {
        ObjectNode usage = mapUsage(source);
        if (usage != null && !usage.isEmpty()) {
            target.set("usage", usage);
        }
    }

    private static ObjectNode chatError(JsonNode error, String fallbackMessage) {
        ObjectNode response = JSON.createObjectNode();
        ObjectNode mappedError = response.putObject("error");
        if (error != null && error.isObject()) {
            error.fields().forEachRemaining(field ->
                    mappedError.set(field.getKey(), field.getValue().deepCopy()));
        } else {
            mappedError.put("message", fallbackMessage);
        }
        return response;
    }

    private static boolean hasFunctionCall(JsonNode output) {
        if (!output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String argumentText(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return "";
        }
        return arguments.isTextual() ? arguments.asText() : writeJson(arguments);
    }

    private static ObjectNode parseObject(String body, String errorMessage) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException error) {
            throw new HttpTransportException(errorMessage, 502, body);
        }
        throw new HttpTransportException(errorMessage, 502, body);
    }

    private static void copy(JsonNode source, ObjectNode target, String from, String to) {
        if (source == null) {
            return;
        }
        JsonNode value = source.get(from);
        if (value != null && !value.isNull()) {
            target.set(to, value.deepCopy());
        }
    }

    private static String writeJson(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new HttpTransportException("Failed to serialize OpenAI API payload", 502, null);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static final class StreamState {
        private String id;
        private String model;
        private Long created;
        private boolean hasToolCall;
        private final Map<Integer, Integer> toolIndexes = new HashMap<>();
        private int nextToolIndex;

        private int toolIndex(int outputIndex) {
            Integer toolIndex = toolIndexes.get(outputIndex);
            if (toolIndex == null) {
                toolIndex = nextToolIndex++;
                toolIndexes.put(outputIndex, toolIndex);
            }
            return toolIndex;
        }

        private void capture(JsonNode response) {
            JsonNode responseId = response.get("id");
            if (responseId != null && responseId.isTextual()) {
                id = responseId.asText();
            }
            JsonNode responseModel = response.get("model");
            if (responseModel != null && responseModel.isTextual()) {
                model = responseModel.asText();
            }
            JsonNode timestamp = response.get("created_at");
            if (timestamp != null && timestamp.isNumber()) {
                created = timestamp.longValue();
            }
        }
    }
}
