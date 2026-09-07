package com.example.agent.llm.infrastructure;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SpringAiModelFactory {

  private final ChatModel chatModel;
  private final String modelName;

  public SpringAiModelFactory(
      ChatModel chatModel,
      @Value("${spring.ai.openai.chat.model:gpt-5-mini}") String modelName) {
    this.chatModel = chatModel;
    this.modelName = modelName;
  }

  public SpringAiLlm tutorModel() {
    return new SpringAiLlm(chatModel, modelName);
  }
}
