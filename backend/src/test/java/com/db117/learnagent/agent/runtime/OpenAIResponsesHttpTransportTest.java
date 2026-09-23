package com.db117.learnagent.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIResponsesHttpTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void convertsChatRequestsAndResponsesWithToolsAndStructuredOutput() throws Exception {
        StubTransport delegate = new StubTransport(HttpResponse.builder()
                .statusCode(200)
                .headers(Map.of("content-type", "application/json"))
                .body("""
                        {
                          "id":"resp_1",
                          "object":"response",
                          "created_at":1770000000,
                          "model":"gpt-test",
                          "status":"completed",
                          "output":[
                            {"id":"msg_1","type":"message","content":[{"type":"output_text","text":"plan"}]},
                            {
                              "id":"fc_1","call_id":"call_1","type":"function_call",
                              "name":"lookup","arguments":"{\\"id\\":1}"
                            }
                          ],
                          "usage":{"input_tokens":3,"output_tokens":5,"total_tokens":8}
                        }
                        """)
                .build());
        OpenAIResponsesHttpTransport transport = new OpenAIResponsesHttpTransport(delegate);

        HttpResponse response = transport.execute(HttpRequest.builder()
                .url("https://provider.test/v1/responses")
                .method("POST")
                .headers(Map.of("authorization", "Bearer test"))
                .body("""
                        {
                          "model":"gpt-test",
                          "stream":false,
                          "max_tokens":100,
                          "messages":[
                            {"role":"user","content":"make a plan"},
                            {
                              "role":"assistant","content":null,
                              "tool_calls":[{"id":"call_old","type":"function","function":{"name":"lookup","arguments":"{}"}}]
                            },
                            {"role":"tool","tool_call_id":"call_old","content":"done"}
                          ],
                          "tools":[
                            {
                              "type":"function",
                              "function":{
                                "name":"lookup","description":"look up",
                                "parameters":{
                                  "type":"object",
                                  "properties":{"id":{"type":"string","description":"record id"}},
                                  "required":["id"]
                                }
                              }
                            }
                          ],
                          "tool_choice":{"type":"function","function":{"name":"lookup"}},
                          "response_format":{
                            "type":"json_schema",
                            "json_schema":{"name":"plan","schema":{"type":"object"},"strict":true}
                          }
                        }
                        """)
                .build());

        JsonNode sent = JSON.readTree(delegate.request.getBody());
        assertEquals("gpt-test", sent.path("model").asText());
        assertEquals(100, sent.path("max_output_tokens").asInt());
        assertNull(sent.get("messages"));
        assertEquals("function_call", sent.path("input").get(1).path("type").asText());
        assertEquals("call_old", sent.path("input").get(1).path("call_id").asText());
        assertEquals("function_call_output", sent.path("input").get(2).path("type").asText());
        assertEquals("lookup", sent.path("tools").get(0).path("name").asText());
        assertFalse(sent.path("tools").get(0).has("function"));
        assertTrue(sent.path("tools").get(0).path("parameters").has("required"));
        assertEquals("lookup", sent.path("tool_choice").path("name").asText());
        assertEquals("json_schema", sent.path("text").path("format").path("type").asText());
        assertEquals("plan", sent.path("text").path("format").path("name").asText());
        assertTrue(sent.path("text").path("format").path("strict").asBoolean());

        JsonNode completion = JSON.readTree(response.getBody());
        assertEquals("chat.completion", completion.path("object").asText());
        JsonNode completionChoice = completion.path("choices").get(0);
        JsonNode completionMessage = completionChoice.path("message");
        assertEquals("plan", completionMessage.path("content").asText());
        assertEquals("tool_calls", completionChoice.path("finish_reason").asText());
        assertEquals("call_1", completionMessage.path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"id\":1}", completionMessage.path("tool_calls").get(0)
                .path("function").path("arguments").asText());
        assertEquals(3, completion.path("usage").path("prompt_tokens").asInt());
    }

    @Test
    void convertsResponsesTextAndToolStreamEventsToChatChunks() throws Exception {
        StubTransport delegate = new StubTransport(Flux.just(
                """
                        {"type":"response.created","response":{"id":"resp_stream","model":"gpt-test","created_at":1770000000}}
                        """,
                """
                        {"type":"response.output_text.delta","response_id":"resp_stream","delta":"hello"}
                        """,
                """
                        {
                          "type":"response.output_item.added","response_id":"resp_stream","output_index":2,
                          "item":{"type":"function_call","call_id":"call_stream","name":"lookup","arguments":""}
                        }
                        """,
                """
                        {"type":"response.function_call_arguments.delta","response_id":"resp_stream","output_index":2,"delta":"{\\"id\\":1}"}
                        """,
                """
                        {
                          "type":"response.completed",
                          "response":{
                            "id":"resp_stream","model":"gpt-test","status":"completed",
                            "output":[{"type":"message"},{"type":"function_call"}],
                            "usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}
                          }
                        }
                        """));
        OpenAIResponsesHttpTransport transport = new OpenAIResponsesHttpTransport(delegate);

        List<String> chunks = transport.stream(HttpRequest.builder()
                        .url("https://provider.test/v1/responses")
                        .method("POST")
                        .body("{\"model\":\"gpt-test\",\"messages\":[],\"stream\":true}")
                        .build())
                .collectList()
                .block();

        assertEquals(4, chunks.size());
        JsonNode textChunk = JSON.readTree(chunks.get(0));
        assertEquals("hello", textChunk.path("choices").get(0).path("delta").path("content").asText());
        JsonNode toolChunk = JSON.readTree(chunks.get(1));
        assertEquals(0, toolChunk.path("choices").get(0).path("delta").path("tool_calls")
                .get(0).path("index").asInt());
        assertEquals("lookup", toolChunk.path("choices").get(0).path("delta").path("tool_calls")
                .get(0).path("function").path("name").asText());
        JsonNode argumentsChunk = JSON.readTree(chunks.get(2));
        assertEquals(0, argumentsChunk.path("choices").get(0).path("delta").path("tool_calls")
                .get(0).path("index").asInt());
        assertEquals("{\"id\":1}", argumentsChunk.path("choices").get(0).path("delta")
                .path("tool_calls").get(0).path("function").path("arguments").asText());
        JsonNode finalChunk = JSON.readTree(chunks.get(3));
        assertEquals("tool_calls", finalChunk.path("choices").get(0).path("finish_reason").asText());
        assertEquals(5, finalChunk.path("usage").path("total_tokens").asInt());
    }

    @Test
    void rejectsResponsesBodiesThatCannotBeTranslated() {
        StubTransport delegate = new StubTransport(HttpResponse.builder()
                .statusCode(200)
                .body("{\"id\":\"resp_1\",\"status\":\"completed\"}")
                .build());

        assertThrows(HttpTransportException.class, () ->
                new OpenAIResponsesHttpTransport(delegate).execute(HttpRequest.builder()
                        .url("https://provider.test/v1/responses")
                        .method("POST")
                        .body("{\"model\":\"gpt-test\",\"messages\":[]}")
                        .build()));
    }

    private static final class StubTransport implements HttpTransport {
        private final HttpResponse response;
        private final Flux<String> stream;
        private HttpRequest request;

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
            this.request = request;
            return response;
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            this.request = request;
            return stream;
        }

        @Override
        public void close() {
        }
    }
}
