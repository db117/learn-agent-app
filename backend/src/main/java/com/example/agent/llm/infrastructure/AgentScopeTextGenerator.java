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
import java.util.function.Consumer;

/** Collects text from one AgentScope model call for synchronous learning operations. */
final class AgentScopeTextGenerator {

    private static final ResponseFormat JSON_OBJECT = ResponseFormat.jsonObject();
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentScopeTextGenerator.class);

    private AgentScopeTextGenerator() {
    }

    static String generate(Model model, String prompt) {
        return generate(model, prompt, JSON_OBJECT, ignored -> {
        });
    }

    static String generate(Model model, String prompt, Consumer<String> onText) {
        return generate(model, prompt, JSON_OBJECT, onText);
    }

    static String generate(Model model, String prompt, ResponseFormat responseFormat) {
        return generate(model, prompt, responseFormat, ignored -> {
        });
    }

    static String generate(Model model, String prompt, ResponseFormat responseFormat, Consumer<String> onText) {
        GenerateOptions options = GenerateOptions.builder()
                .responseFormat(responseFormat)
                .build();
        LOGGER.info("[LLM-TRACE] request model={} responseFormat={} tools=[] prompt={}",
                model.getModelName(), responseFormat.getType(), prompt);
        AtomicInteger blockIndex = new AtomicInteger();
        String text = model.stream(List.of(new UserMessage(prompt)), List.of(), options)
                .flatMapIterable(response -> response.getContent() == null
                        ? List.<ContentBlock>of() : response.getContent())
                .doOnNext(block -> LOGGER.info("[LLM-TRACE] response.block index={} type={} value={}",
                        blockIndex.getAndIncrement(), block == null ? "null" : block.getClass().getSimpleName(),
                        block instanceof TextBlock textBlock ? textBlock.getText() : block))
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .doOnNext(onText)
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
