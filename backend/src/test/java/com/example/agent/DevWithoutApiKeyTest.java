package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.data-dir=target/dev-without-key-data",
                "app.database=target/dev-without-key-data/context.db",
                "spring.ai.model.chat=none"
        })
class DevWithoutApiKeyTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void startsWithoutProviderCredentialsAndFailsOnlyWhenChatIsRequested() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> chatModel.call(new Prompt(new UserMessage("health check"))));

        assertEquals("LLM is not configured; set OPENAI_API_KEY", error.getMessage());
    }
}
