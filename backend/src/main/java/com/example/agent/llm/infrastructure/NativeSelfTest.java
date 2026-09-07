package com.example.agent.llm.infrastructure;

import com.example.agent.agent.EventHub;
import com.example.agent.config.AppProperties;
import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.PersistedEvent;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.tool.EchoTool;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Runs the deterministic ADK/provider/tool/SSE fixture when native:check opts in. */
@Component
final class NativeSelfTest implements ApplicationRunner {

  static final String SESSION_ID = "native-self-test";
  private static final String RUN_ID = "native-self-test-run";

  private final AppProperties properties;
  private final SqliteRepository repository;
  private final EventHub eventHub;
  private final EchoTool echoTool;

  NativeSelfTest(
      AppProperties properties, SqliteRepository repository, EventHub eventHub, EchoTool echoTool) {
    this.properties = properties;
    this.repository = repository;
    this.eventHub = eventHub;
    this.echoTool = echoTool;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!args.containsOption("app.native-self-test")) return;
    Instant now = Instant.now();
    SessionRecord session =
        new SessionRecord(SESSION_ID, properties.userId(), "native self-test", now, now);
    repository.insertSession(session);

    Method echo = EchoTool.class.getMethod("echo", String.class);
    ScriptedChatModel model = new ScriptedChatModel();
    LlmAgent agent =
        LlmAgent.builder()
            .name("native_test_tutor")
            .instruction("Use echo once, then explain the result.")
            .model(new SpringAiLlm(model, "native-test-model"))
            .tools(FunctionTool.create(echoTool, echo))
            .maxSteps(4)
            .build();
    InMemorySessionService sessions = new InMemorySessionService();
    sessions.createSession(properties.appName(), session.userId(), null, session.id()).blockingGet();
    Runner runner =
        Runner.builder()
            .agent(agent)
            .appName(properties.appName())
            .sessionService(sessions)
            .build();

    List<Event> events =
        runner
            .runAsync(
                session.userId(),
                session.id(),
                Content.fromParts(Part.fromText("verify hello")),
                RunConfig.builder()
                    .toolExecutionMode(RunConfig.ToolExecutionMode.SEQUENTIAL)
                    .build())
            .toList()
            .blockingGet();
    if (events.stream().noneMatch(event -> !event.functionCalls().isEmpty())
        || events.stream().noneMatch(event -> !event.functionResponses().isEmpty())
        || events.stream().noneMatch(event -> event.stringifyContent().contains("Echo observed: hello"))
        || !model.receivedToolResult()) {
      throw new IllegalStateException("Native ADK tool loop did not complete");
    }

    for (Event event : events) {
      PersistedEvent persisted = PersistedEvent.from(session.id(), RUN_ID, event);
      repository.insertEvent(persisted);
      eventHub.publish(persisted);
      if (event.finalResponse() && !event.stringifyContent().isBlank()) {
        repository.insertMessage(
            new MessageRecord(event.id(), session.id(), "assistant", event.stringifyContent(), persisted.timestamp()));
      }
    }
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
      if (prompts.size() > 2) throw new IllegalStateException("Native self-test requested too many model calls");
      return new ChatResponse(List.of(new Generation(new AssistantMessage("Echo observed: hello"))));
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
    public ChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }
  }
}
