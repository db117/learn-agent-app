package com.example.agent.config;

import com.example.agent.tool.EchoTool;
import com.example.agent.learning.tutor.TutorContextService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AgentScope runtime for the single TutorAgent. */
@Configuration(proxyBeanMethods = false)
public class AgentScopeConfiguration {

    @Bean(destroyMethod = "close")
    ClasspathSkillRepository agentScopeSkillRepository() {
        try {
            return new ClasspathSkillRepository("agent-skills");
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load AgentScope skills", error);
        }
    }

    @Bean
    @ConditionalOnMissingBean(Model.class)
    Model agentScopeOpenAiModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.model:gpt-4.1-mini}") String modelName) {
        if (apiKey == null || apiKey.isBlank()) return new UnavailableModel();
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();
    }

    @Bean
    HarnessAgent tutorAgent(
            Model model,
            EchoTool echoTool,
            ClasspathSkillRepository skillRepository,
            TutorContextService context) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(echoTool);
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setAllow(List.of("echo", "load_skill_through_path"));
        return HarnessAgent.builder()
                .name("tutor_agent")
                .description("A programming-language tutor for the Desktop Learning Agent.")
                .sysPrompt("You are TutorAgent. Teach clearly and never change learning state.")
                .model(model)
                .toolkit(toolkit)
                .toolsConfig(toolsConfig)
                .skillRepository(skillRepository)
                .stateStore(new InMemoryAgentStateStore())
                .middleware(new TutorContextMiddleware(context))
                .enableAgentTracingLog(false)
                .disableCompaction()
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableTranscript()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableDefaultWorkspaceSkills()
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService agentExecutor() {
        return Executors.newCachedThreadPool();
    }

    private static final class UnavailableModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new IllegalStateException("LLM is not configured; set OPENAI_API_KEY"));
        }

        @Override
        public String getModelName() {
            return "unavailable";
        }
    }

    private static final class TutorContextMiddleware implements MiddlewareBase {

        private final TutorContextService context;

        private TutorContextMiddleware(TutorContextService context) {
            this.context = context;
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext runtime, String currentPrompt) {
            if (runtime == null || runtime.getSessionId() == null) return Mono.just(currentPrompt);
            return Mono.fromCallable(() -> currentPrompt + "\n\n" + context.forSession(runtime.getSessionId()))
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
