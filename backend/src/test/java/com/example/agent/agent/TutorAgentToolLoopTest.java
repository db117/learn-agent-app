package com.example.agent.agent;

import com.example.agent.llm.infrastructure.SpringAiLlm;
import com.example.agent.tool.EchoTool;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorAgentToolLoopTest {

    @Test
    void adkExecutesToolAndSendsTheToolResultBackToTheProvider() throws Exception {
        ScriptedChatModel provider = new ScriptedChatModel();
        Method echo = EchoTool.class.getMethod("echo", String.class);
        LlmAgent agent =
                LlmAgent.builder()
                        .name("test_tutor")
                        .instruction("Use echo once, then explain the result.")
                        .model(new SpringAiLlm(provider, "test-model"))
                        .tools(FunctionTool.create(new EchoTool(), echo))
                        .maxSteps(4)
                        .build();
        InMemorySessionService sessions = new InMemorySessionService();
        Runner runner =
                Runner.builder()
                        .agent(agent)
                        .appName("test-app")
                        .sessionService(sessions)
                        .build();
        sessions.createSession("test-app", "test-user", null, "test-session").blockingGet();

        List<Event> events =
                runner
                        .runAsync(
                                "test-user",
                                "test-session",
                                Content.fromParts(Part.fromText("verify hello")),
                                RunConfig.builder()
                                        .toolExecutionMode(RunConfig.ToolExecutionMode.SEQUENTIAL)
                                        .build())
                        .toList()
                        .blockingGet();

        assertEquals(2, provider.prompts.size());
        assertTrue(events.stream().anyMatch(event -> !event.functionCalls().isEmpty()));
        assertTrue(events.stream().anyMatch(event -> !event.functionResponses().isEmpty()));
        assertTrue(events.stream().anyMatch(event -> event.stringifyContent().contains("Echo observed: hello")));
        assertTrue(
                provider.prompts.get(1).getInstructions().stream()
                        .anyMatch(
                                message ->
                                        message instanceof ToolResponseMessage tool
                                                && tool.getResponses().stream()
                                                .anyMatch(response -> response.responseData().contains("hello"))));
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                return new ChatResponse(
                        List.of(
                                new Generation(
                                        AssistantMessage.builder()
                                                .toolCalls(
                                                        List.of(
                                                                new AssistantMessage.ToolCall(
                                                                        "call-1", "function", "echo", "{\"text\":\"hello\"}")))
                                                .build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Echo observed: hello"))));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
