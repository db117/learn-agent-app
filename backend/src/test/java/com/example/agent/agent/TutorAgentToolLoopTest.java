package com.example.agent.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.agent.tool.EchoTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.support.ToolCallbacks;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorAgentToolLoopTest {

    @Test
    void saaExecutesToolAndSendsTheToolResultBackToTheProvider() throws Exception {
        ScriptedChatModel provider = new ScriptedChatModel();
        ReactAgent agent = ReactAgent.builder()
                .name("test_tutor")
                .instruction("Use echo once, then explain the result.")
                .model(provider)
                .tools(ToolCallbacks.from(new EchoTool()))
                .build();

        List<Message> messages = agent.streamMessages(
                        List.of(new UserMessage("verify hello")),
                        RunnableConfig.builder().threadId("test-session").build())
                .collectList()
                .block();

        assertEquals(2, provider.prompts.size());
        assertTrue(messages.stream().anyMatch(message -> message instanceof AssistantMessage assistant
                && !assistant.getToolCalls().isEmpty()));
        assertTrue(messages.stream().anyMatch(ToolResponseMessage.class::isInstance));
        assertTrue(messages.stream().anyMatch(message -> message.getText() != null
                && message.getText().contains("Echo observed: hello")));
        assertTrue(provider.receivedToolResult());
    }

    @Test
    void graphRunsJavaNodeAgentNodeAndConditionalRoute() throws Exception {
        ScriptedChatModel provider = new ScriptedChatModel();
        ReactAgent agent = ReactAgent.builder()
                .name("graph_tutor")
                .instruction("Explain the supplied message.")
                .model(provider)
                .build();
        StateGraph graph = new StateGraph();
        graph.addNode("prepare", state -> CompletableFuture.completedFuture(Map.of("ready", true)));
		graph.addNode(agent.name(), agent.asNode(true, false));
        graph.addNode("complete", state -> CompletableFuture.completedFuture(Map.of("completed", true)));
        graph.addEdge(StateGraph.START, "prepare");
        graph.addConditionalEdges("prepare", state -> CompletableFuture.completedFuture(agent.name()),
                Map.of(agent.name(), agent.name()));
        graph.addEdge(agent.name(), "complete");
        graph.addEdge("complete", StateGraph.END);

        CompiledGraph compiled = graph.compile();
        var result = compiled.invoke(
                Map.of("messages", List.of(new UserMessage("hello"))),
                RunnableConfig.builder().threadId("graph-session").build());

        assertTrue(result.isPresent());
        assertEquals(true, result.orElseThrow().value("ready").orElse(false));
        assertEquals(true, result.orElseThrow().value("completed").orElse(false));
        assertEquals(1, provider.prompts.size());
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
