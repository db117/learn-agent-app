package com.example.agent.config;

import com.example.agent.llm.infrastructure.SpringAiModelFactory;
import com.example.agent.tool.EchoTool;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.class)
public class AgentConfiguration {

  @Bean
  LlmAgent tutorAgent(SpringAiModelFactory models, EchoTool echoTool) throws NoSuchMethodException {
    Method echo = EchoTool.class.getMethod("echo", String.class);
    return LlmAgent.builder()
        .name("tutor_agent")
        .description("A programming-language tutor for the Desktop Learning Agent.")
        .instruction("You are TutorAgent. Help the user learn programming clearly and patiently. Use the echo tool when it helps verify tool calling.")
        .model(models.tutorModel())
        .tools(FunctionTool.create(echoTool, echo))
        .disallowTransferToParent(true)
        .disallowTransferToPeers(true)
        .maxSteps(8)
        .build();
  }

  @Bean
  InMemorySessionService adkSessionService() {
    return new InMemorySessionService();
  }

  @Bean
  Runner adkRunner(LlmAgent tutorAgent, InMemorySessionService sessions, AppProperties properties) {
    return Runner.builder()
        .agent(tutorAgent)
        .appName(properties.appName())
        .sessionService(sessions)
        .build();
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService agentExecutor() {
    return Executors.newCachedThreadPool();
  }
}
