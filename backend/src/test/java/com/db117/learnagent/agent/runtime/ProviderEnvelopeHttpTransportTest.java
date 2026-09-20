package com.db117.learnagent.agent.runtime;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEnvelopeHttpTransportTest {
    private static final String ERROR_ENVELOPE = "{\"result\":-1,\"gpt_status\":404,\"message\":\"route failed\"}";
    private static final HttpRequest REQUEST = HttpRequest.builder()
            .url("http://provider.test/v1/chat/completions")
            .method("POST")
            .body("{}")
            .build();

    @Test
    void convertsNonStreamingErrorEnvelopeToRetryableGatewayStatus() {
        var delegate = new StubTransport(HttpResponse.builder()
                .statusCode(200)
                .headers(Map.of("content-type", "application/json"))
                .body(ERROR_ENVELOPE)
                .build());

        var response = new ProviderEnvelopeHttpTransport(delegate).execute(REQUEST);

        assertEquals(502, response.getStatusCode());
        assertEquals(ERROR_ENVELOPE, response.getBody());
    }

    @Test
    void convertsStreamingErrorEnvelopeToTransportException() {
        var delegate = new StubTransport(Flux.just("data: " + ERROR_ENVELOPE));

        var error = assertThrows(
                HttpTransportException.class,
                () -> new ProviderEnvelopeHttpTransport(delegate).stream(REQUEST).blockLast());

        assertEquals(502, error.getStatusCode());
        assertTrue(error.getMessage().contains("route failed"));
    }

    @Test
    void leavesSuccessfulResponsesAndDoneMarkerUntouched() {
        assertFalse(ProviderEnvelopeHttpTransport.isProviderError(
                "{\"result\":0,\"gpt_status\":200}"));
        assertFalse(ProviderEnvelopeHttpTransport.isProviderError("data: [DONE]"));
        assertTrue(ProviderEnvelopeHttpTransport.isProviderError(ERROR_ENVELOPE));
    }

    private static final class StubTransport implements HttpTransport {
        private final HttpResponse response;
        private final Flux<String> stream;

        private StubTransport(HttpResponse response) {
            this.response = response;
            this.stream = Flux.empty();
        }

        private StubTransport(Flux<String> stream) {
            this.response = HttpResponse.builder().statusCode(200).body("{}").build();
            this.stream = stream;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            return response;
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return stream;
        }

        @Override
        public void close() {
        }
    }
}
