package com.db117.learnagent.learning.application;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnUnitContentGeneratorTest {
    @Test
    void generatesExplainExampleAndPracticeAfterEnteringAnOutlineUnit() {
        var model = new StubModel("""
                {
                  "concept": "类型约束可以减少错误。",
                  "example": "const answer: number = 42;",
                  "practice": "修复 src/index.ts 的类型错误。"
                }
                """);
        var generator = new LearnUnitContentGenerator(model);
        var unit = LearnUnit.create(
                "variables",
                "变量与类型",
                "能够声明变量并理解基本类型",
                "",
                0,
                "basics",
                Set.of());

        var content = generator.generate(unit);

        assertTrue(content.contains("## Concept\n类型约束可以减少错误。"));
        assertTrue(content.contains("## Example\nconst answer: number = 42;"));
        assertTrue(content.contains("## Practice\n修复 src/index.ts 的类型错误。"));
        assertTrue(model.prompt.contains("变量与类型"));
        assertTrue(model.prompt.contains("能够声明变量并理解基本类型"));
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
            return "learn-unit-content-test-model";
        }
    }
}
