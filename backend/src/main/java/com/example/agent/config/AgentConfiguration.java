package com.example.agent.config;

import com.example.agent.llm.infrastructure.SpringAiModelFactory;
import com.example.agent.learning.tutor.TutorContextService;
import com.example.agent.tool.EchoTool;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.reactivex.rxjava3.core.Single;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.class)
public class AgentConfiguration {

    @Bean
    LlmAgent tutorAgent(SpringAiModelFactory models, EchoTool echoTool, TutorContextService context) throws NoSuchMethodException {
        Method echo = EchoTool.class.getMethod("echo", String.class);
        return LlmAgent.builder()
                .name("tutor_agent")
                .description("A programming-language tutor for the Desktop Learning Agent.")
                .instruction(new Instruction.Provider(readOnly -> Single.just(
                        context.forSession(readOnly.sessionId())
                                + "\nUse the echo tool when it helps verify tool calling.")))
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
