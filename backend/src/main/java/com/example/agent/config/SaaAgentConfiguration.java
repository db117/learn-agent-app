package com.example.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.example.agent.learning.tutor.TutorContextService;
import com.example.agent.tool.EchoTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spring AI Alibaba runtime foundation for the single TutorAgent and workflow graph. */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.class)
public class SaaAgentConfiguration {

    @Bean(destroyMethod = "close")
    ClasspathSkillRegistry agentSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("agent-skills")
                .autoLoad(true)
                .build();
    }

    @Bean
    ReactAgent saaTutorAgent(
            ChatModel chatModel,
            EchoTool echoTool,
            SkillRegistry agentSkillRegistry,
            TutorContextService context) {
        return ReactAgent.builder()
                .name("tutor_agent")
                .description("A programming-language tutor for the Desktop Learning Agent.")
                .model(chatModel)
                .instruction(context.forSession("saa-runtime-foundation"))
                .tools(ToolCallbacks.from(echoTool))
                .hooks(SkillsAgentHook.builder().skillRegistry(agentSkillRegistry).build())
                .build();
    }

    @Bean
    CompiledGraph saaWorkflowGraph(ReactAgent saaTutorAgent) {
        try {
            StateGraph graph = new StateGraph();
            graph.addNode("prepare", state -> CompletableFuture.completedFuture(Map.of("ready", true)));
            String tutorNode = saaTutorAgent.name();
            graph.addNode(tutorNode, saaTutorAgent.asNode(true, false));
            graph.addNode("complete", state -> CompletableFuture.completedFuture(Map.of("completed", true)));
            graph.addEdge(StateGraph.START, "prepare");
            graph.addConditionalEdges(
                    "prepare",
                    state -> CompletableFuture.completedFuture(tutorNode),
                    Map.of(tutorNode, tutorNode));
            graph.addEdge(tutorNode, "complete");
            graph.addEdge("complete", StateGraph.END);
            return graph.compile();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to compile the SAA workflow graph", error);
        }
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService agentExecutor() {
        return Executors.newCachedThreadPool();
    }
}
