package com.example.agent.config;

import com.example.agent.learning.tutor.TutorContextService;
import com.example.agent.persistence.SqliteAgentStateStore;
import com.example.agent.tool.EchoTool;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

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
    Model agentScopeModel(ModelProviderConfigurationService configuration) {
        ModelProviderConfiguration value = configuration.current();
        if (value.apiKey() == null || value.apiKey().isBlank()) return new UnavailableModel();
        return configuration.createModel(value);
    }

    @Bean
    @DependsOn("databaseInitializer")
    AgentStateStore agentStateStore(DataSource dataSource) {
        return new SqliteAgentStateStore(dataSource);
    }

    @Bean
    HarnessAgent tutorAgent(
            Model model,
            EchoTool echoTool,
            ClasspathSkillRepository skillRepository,
            TutorContextService context,
            AgentStateStore stateStore) {
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
                .stateStore(stateStore)
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
            return Flux.error(new IllegalStateException(
                    "LLM is not configured; set OPENAI_API_KEY or configure it in the app"));
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
            return Mono.fromCallable(() -> currentPrompt + "\n\n" + context.promptForSession(runtime.getSessionId()))
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
