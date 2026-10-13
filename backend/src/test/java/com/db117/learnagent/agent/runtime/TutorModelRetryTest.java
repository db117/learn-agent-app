package com.db117.learnagent.agent.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.exception.BadRequestException;
import io.agentscope.extensions.model.openai.exception.InternalServerException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TutorModelRetryTest {
    @Test
    void retriesServerErrorsAndReturnsTheSuccessfulResponse() {
        ScriptedModel model = new ScriptedModel(call -> call == 1
                ? Flux.error(new InternalServerException("upstream unavailable", 503, null, null))
                : Flux.just(response()));

        List<ChatResponse> responses = new TutorModel(model).stream(
                        List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(1, responses.size());
        assertEquals(2, model.calls.get());
    }

    @Test
    void retriesTheGatewayUnavailableErrorEvenWhenTheGatewayLabelsItBadRequest() {
        ScriptedModel model = new ScriptedModel(call -> call == 1
                ? Flux.error(new BadRequestException(
                "OpenAI API error in streaming response: Upstream service temporarily unavailable",
                null,
                "gateway error"))
                : Flux.just(response()));

        List<ChatResponse> responses = new TutorModel(model).stream(
                        List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(1, responses.size());
        assertEquals(2, model.calls.get());
    }

    @Test
    void doesNotRetryOtherBadRequests() {
        BadRequestException failure = new BadRequestException("invalid model request", "invalid_request", "bad input");
        ScriptedModel model = new ScriptedModel(call -> Flux.error(failure));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> new TutorModel(model).stream(
                        List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(5)));

        assertSame(failure, thrown);
        assertEquals(1, model.calls.get());
    }

    @Test
    void doesNotRetryAfterTheModelHasEmittedAResponse() {
        ScriptedModel model = new ScriptedModel(call -> Flux.concat(
                Flux.just(response()),
                Flux.error(new InternalServerException("upstream unavailable", 503, null, null))));

        assertThrows(RuntimeException.class, () -> new TutorModel(model).stream(
                        List.of(new UserMessage("hello")), List.of(), GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(5)));

        assertEquals(1, model.calls.get());
    }

    private static ChatResponse response() {
        return ChatResponse.builder().finishReason("stop").build();
    }

    private static final class ScriptedModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<Flux<ChatResponse>> responseForCall;

        private ScriptedModel(IntFunction<Flux<ChatResponse>> responseForCall) {
            this.responseForCall = responseForCall;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return responseForCall.apply(calls.incrementAndGet());
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }
    }
}
