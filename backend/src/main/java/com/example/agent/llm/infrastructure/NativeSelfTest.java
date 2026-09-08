package com.example.agent.llm.infrastructure;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.agent.tool.EchoTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Deterministic SAA Agent, tool and streaming smoke test used by native:check. */
@Component
final class NativeSelfTest implements ApplicationRunner {

    private final EchoTool echoTool;

    NativeSelfTest(EchoTool echoTool) {
        this.echoTool = echoTool;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("app.native-self-test")) return;

        ScriptedChatModel model = new ScriptedChatModel();
        ReactAgent agent = ReactAgent.builder()
                .name("native_test_tutor")
                .instruction("Use echo once, then explain the result.")
                .model(model)
                .tools(ToolCallbacks.from(echoTool))
                .build();
        List<Message> messages = agent.streamMessages(
                        List.of(new org.springframework.ai.chat.messages.UserMessage("verify hello")),
                        RunnableConfig.builder().threadId("native-self-test").build())
                .collectList()
                .block();
        if (messages == null
                || messages.stream().noneMatch(message -> message instanceof AssistantMessage assistant
                && !assistant.getToolCalls().isEmpty())
                || messages.stream().noneMatch(message -> message instanceof ToolResponseMessage)
                || messages.stream().noneMatch(message -> message.getText() != null
                && message.getText().contains("Echo observed: hello"))
                || !model.receivedToolResult()) {
            throw new IllegalStateException("Native SAA tool loop did not complete");
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder()
                                .toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "call-1", "function", "echo", "{\"text\":\"hello\"}")))
                                .build())));
            }
            if (prompts.size() > 2) throw new IllegalStateException("Native self-test requested too many model calls");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Echo observed: hello"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        boolean receivedToolResult() {
            return prompts.size() == 2
                    && prompts.get(1).getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> response.responseData().contains("hello"));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
