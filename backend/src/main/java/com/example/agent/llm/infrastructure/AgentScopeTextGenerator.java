package com.example.agent.llm.infrastructure;

import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects text from one AgentScope model call for synchronous learning operations. */
final class AgentScopeTextGenerator {

    private static final GenerateOptions JSON_OPTIONS = GenerateOptions.builder()
            .responseFormat(ResponseFormat.jsonObject())
            .build();
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScopeTextGenerator.class);

    private AgentScopeTextGenerator() {
    }

    static String generate(Model model, String prompt) {
        LOGGER.info("[LLM-TRACE] request model={} responseFormat={} tools=[] prompt={}",
                model.getModelName(), JSON_OPTIONS.getResponseFormat().getType(), prompt);
        AtomicInteger blockIndex = new AtomicInteger();
        String text = model.stream(List.of(new UserMessage(prompt)), List.of(), JSON_OPTIONS)
                .flatMapIterable(response -> response.getContent() == null
                        ? List.<ContentBlock>of() : response.getContent())
                .doOnNext(block -> LOGGER.info("[LLM-TRACE] response.block index={} type={} value={}",
                        blockIndex.getAndIncrement(), block == null ? "null" : block.getClass().getSimpleName(),
                        block instanceof TextBlock textBlock ? textBlock.getText() : block))
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .doOnSuccess(value -> LOGGER.info("[LLM-TRACE] response.complete chars={} value={}",
                        value == null ? 0 : value.length(), value == null ? "" : value))
                .doOnError(error -> LOGGER.error("[LLM-TRACE] response.failed", error))
                .block();
        if (text == null || text.isBlank()) throw new IllegalStateException("LLM returned no text");
        return text;
    }
}
