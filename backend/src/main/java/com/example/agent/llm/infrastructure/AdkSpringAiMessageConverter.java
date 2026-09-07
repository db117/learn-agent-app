package com.example.agent.llm.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts only the ADK message/tool shapes needed by the Phase 1 tutor. */
public final class AdkSpringAiMessageConverter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final ObjectMapper mapper = JsonBaseModel.getMapper();

    public Prompt toPrompt(LlmRequest request, ChatOptions modelDefaults) {
        List<Message> messages = new ArrayList<>();
        List<String> systemInstructions = new ArrayList<>(request.getSystemInstructions());
        for (Content content : request.contents()) {
            String role = content.role().orElse("user").toLowerCase(Locale.ROOT);
            switch (role) {
                case "system" -> systemInstructions.add(textOf(content));
                case "user" -> messages.addAll(userMessages(content));
                case "model", "assistant" -> messages.add(assistantMessage(content));
                default -> throw new IllegalArgumentException("Unsupported ADK content role: " + role);
            }
        }
        if (!systemInstructions.isEmpty()) {
            messages.add(0, new SystemMessage(String.join("\n\n", systemInstructions)));
        }
        return new Prompt(messages, options(request, modelDefaults));
    }

    public LlmResponse toResponse(ChatResponse response, boolean streaming) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return LlmResponse.builder().build();
        }
        Generation generation = response.getResult();
        AssistantMessage assistant = generation.getOutput();
        List<Part> parts = new ArrayList<>();
        if (assistant.getText() != null && !assistant.getText().isEmpty()) {
            parts.add(Part.fromText(assistant.getText()));
        }
        for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
            try {
                Map<String, Object> args = mapper.readValue(toolCall.arguments(), MAP_TYPE);
                FunctionCall functionCall =
                        FunctionCall.builder()
                                .id(toolCall.id())
                                .name(toolCall.name())
                                .args(args)
                                .build();
                parts.add(Part.builder().functionCall(functionCall).build());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid provider tool-call arguments", e);
            }
        }

        String finishReason =
                generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
        boolean complete = !streaming || (finishReason != null && !finishReason.isBlank());
        LlmResponse.Builder builder =
                LlmResponse.builder()
                        .content(Content.builder().role("model").parts(parts).build())
                        .partial(streaming && !complete)
                        .turnComplete(complete);
        if (finishReason != null && !finishReason.isBlank()) {
            builder.finishReason(new com.google.genai.types.FinishReason(finishReason));
        }
        if (response.getMetadata() != null) {
            if (response.getMetadata().getModel() != null) {
                builder.modelVersion(response.getMetadata().getModel());
            }
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                builder.usageMetadata(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(valueOrZero(usage.getPromptTokens()))
                                .candidatesTokenCount(valueOrZero(usage.getCompletionTokens()))
                                .totalTokenCount(valueOrZero(usage.getTotalTokens()))
                                .build());
            }
        }
        return builder.build();
    }

    private List<Message> userMessages(Content content) {
        StringBuilder text = new StringBuilder();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Part part : parts(content)) {
            part.text().ifPresent(text::append);
            part.functionResponse().ifPresent(response -> responses.add(toToolResponse(response)));
        }
        List<Message> messages = new ArrayList<>();
        if (text.length() > 0 || responses.isEmpty()) {
            messages.add(new UserMessage(text.toString()));
        }
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
        return messages;
    }

    private ToolResponseMessage.ToolResponse toToolResponse(FunctionResponse response) {
        return new ToolResponseMessage.ToolResponse(
                response.id().orElse(""),
                response.name().orElse(""),
                JsonSerializable.toJsonString(response.response().orElse(Map.of())));
    }

    private AssistantMessage assistantMessage(Content content) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (Part part : parts(content)) {
            part.text().ifPresent(text::append);
            part.functionCall()
                    .ifPresent(
                            call ->
                                    calls.add(
                                            new AssistantMessage.ToolCall(
                                                    call.id().orElse(""),
                                                    "function",
                                                    call.name().orElse(""),
                                                    JsonSerializable.toJsonString(call.args().orElse(Map.of())))));
        }
        return calls.isEmpty()
                ? new AssistantMessage(text.toString())
                : AssistantMessage.builder().content(text.toString()).toolCalls(calls).build();
    }

    private ChatOptions options(LlmRequest request, ChatOptions modelDefaults) {
        List<ToolCallback> callbacks =
                request.tools().values().stream().map(this::definitionCallback).toList();
        ChatOptions configOptions = request.config().map(this::toChatOptions).orElse(null);
        boolean needsOptions = !callbacks.isEmpty() || configOptions != null || request.model().isPresent();
        if (!needsOptions && modelDefaults != null) {
            return modelDefaults;
        }

        ToolCallingChatOptions.Builder<?> builder;
        if (modelDefaults instanceof ToolCallingChatOptions toolOptions) {
            builder = toolOptions.mutate();
        } else {
            builder = ToolCallingChatOptions.builder();
        }
        if (!callbacks.isEmpty()) {
            builder.toolCallbacks(callbacks);
        }
        apply(builder, configOptions);
        request.model().ifPresent(builder::model);
        return builder.build();
    }

    private ChatOptions toChatOptions(GenerateContentConfig config) {
        ChatOptions.Builder<?> builder = ChatOptions.builder();
        config.temperature().ifPresent(value -> builder.temperature(value.doubleValue()));
        config.maxOutputTokens().ifPresent(builder::maxTokens);
        config.topP().ifPresent(value -> builder.topP(value.doubleValue()));
        config.topK().ifPresent(value -> builder.topK(value.intValue()));
        config.stopSequences().filter(values -> !values.isEmpty()).ifPresent(builder::stopSequences);
        config.presencePenalty().ifPresent(value -> builder.presencePenalty(value.doubleValue()));
        config.frequencyPenalty().ifPresent(value -> builder.frequencyPenalty(value.doubleValue()));
        return builder.build();
    }

    private void apply(ChatOptions.Builder<?> target, ChatOptions source) {
        if (source == null) return;
        if (source.getTemperature() != null) target.temperature(source.getTemperature());
        if (source.getMaxTokens() != null) target.maxTokens(source.getMaxTokens());
        if (source.getTopP() != null) target.topP(source.getTopP());
        if (source.getTopK() != null) target.topK(source.getTopK());
        if (source.getStopSequences() != null) target.stopSequences(source.getStopSequences());
        if (source.getPresencePenalty() != null) target.presencePenalty(source.getPresencePenalty());
        if (source.getFrequencyPenalty() != null) target.frequencyPenalty(source.getFrequencyPenalty());
    }

    private ToolCallback definitionCallback(BaseTool tool) {
        FunctionDeclaration declaration =
                tool.declaration().orElseThrow(() -> new IllegalArgumentException("Tool has no declaration: " + tool.name()));
        String schema = declaration.parameters().map(this::schemaJson).orElseGet(() ->
                declaration.parametersJsonSchema().map(JsonSerializable::toJsonString).orElse("{}"));
        ToolDefinition definition =
                DefaultToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(schema)
                        .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                throw new UnsupportedOperationException("ADK owns tool execution");
            }
        };
    }

    private String schemaJson(Schema schema) {
        return JsonBaseModel.toJsonString(schemaMap(schema));
    }

    private Map<String, Object> schemaMap(Schema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        schema.type().ifPresent(type -> result.put("type", typeName(type)));
        schema.description().ifPresent(value -> result.put("description", value));
        schema.properties().ifPresent(properties -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            properties.forEach((name, value) -> mapped.put(name, schemaMap(value)));
            result.put("properties", mapped);
        });
        schema.required().ifPresent(value -> result.put("required", value));
        schema.items().ifPresent(value -> result.put("items", schemaMap(value)));
        schema.enum_().ifPresent(value -> result.put("enum", value));
        return result;
    }

    private String typeName(Type type) {
        return switch (type.knownEnum()) {
            case STRING -> "string";
            case NUMBER -> "number";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
            default -> type.toString().toLowerCase(Locale.ROOT);
        };
    }

    private List<Part> parts(Content content) {
        return content.parts().orElse(List.of());
    }

    private String textOf(Content content) {
        return parts(content).stream().flatMap(part -> part.text().stream()).collect(Collectors.joining());
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
