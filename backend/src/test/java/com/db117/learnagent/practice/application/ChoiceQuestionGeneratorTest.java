package com.db117.learnagent.practice.application;

import com.db117.learnagent.learning.domain.LearnUnit;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChoiceQuestionGeneratorTest {
    @Test
    void generatesAValidatedQuestionFromTheCurrentLearnUnit() {
        var model = new StubModel("""
                {
                  "prompt": "哪一项能声明带类型的函数？",
                  "options": [
                    {"id":"a","label":"只写函数名"},
                    {"id":"b","label":"为参数和返回值声明类型"},
                    {"id":"c","label":"删除函数体"},
                    {"id":"d","label":"跳过类型检查"}
                  ],
                  "correctOptionId": "b"
                }
                """);
        var generator = new ChoiceQuestionGenerator(model);
        var unit = LearnUnit.create(
                "functions",
                "函数",
                "能够声明带类型的函数",
                "## Concept\n函数描述可复用的行为。\n## Practice\n为函数补充类型。",
                0,
                "basics",
                Set.of());

        var question = generator.generate(unit);

        assertEquals("哪一项能声明带类型的函数？", question.prompt());
        assertEquals(4, question.options().size());
        assertTrue(question.isCorrect("b"));
        assertTrue(model.prompt.contains("函数"));
        assertTrue(model.prompt.contains("能够声明带类型的函数"));
    }

    private static final class StubModel implements Model {
        private final String response;
        private String prompt = "";

        private StubModel(String response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            prompt = messages.stream()
                    .flatMap(message -> message.getContentBlocks(TextBlock.class).stream())
                    .map(TextBlock::getText)
                    .reduce("", (left, right) -> left + right);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(response).build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "choice-test-model";
        }
    }
}
