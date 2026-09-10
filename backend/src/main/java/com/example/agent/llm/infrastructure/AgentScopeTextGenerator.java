package com.example.agent.llm.infrastructure;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;

import java.util.List;

/** Collects text from one AgentScope model call for synchronous learning operations. */
final class AgentScopeTextGenerator {

    private AgentScopeTextGenerator() {
    }

    static String generate(Model model, String prompt) {
        String text = model.stream(List.of(new UserMessage(prompt)), List.of(), null)
                .flatMapIterable(response -> response.getContent() == null
                        ? List.<ContentBlock>of() : response.getContent())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .block();
        if (text == null || text.isBlank()) throw new IllegalStateException("LLM returned no text");
        return text;
    }
}
