package com.example.agent;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.data-dir=target/dev-without-key-data-v3",
                "app.database=target/dev-without-key-data-v3/context.db",
                "app.openai.api-key="
        })
class DevWithoutApiKeyTest {

    @Autowired
    private Model model;

    @Test
    void startsWithoutProviderCredentialsAndFailsOnlyWhenModelIsRequested() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> model.stream(java.util.List.of(new UserMessage("health check")), java.util.List.of(), null)
                        .blockLast());

        assertEquals("LLM is not configured; set OPENAI_API_KEY", error.getMessage());
    }
}
