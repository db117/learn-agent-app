package com.example.agent.llm.infrastructure;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** The small ADK-to-Spring-AI provider seam. ADK still owns the execution loop. */
public final class SpringAiLlm extends BaseLlm {

  private final ChatModel chatModel;
  private final AdkSpringAiMessageConverter converter;

  public SpringAiLlm(ChatModel chatModel, String modelName) {
    super(Objects.requireNonNull(modelName, "modelName"));
    this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    this.converter = new AdkSpringAiMessageConverter();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    Prompt prompt = converter.toPrompt(request, chatModel.getOptions());
    if (!stream) {
      return Flowable.fromCallable(() -> converter.toResponse(chatModel.call(prompt), false));
    }
    return Flowable.create(
        emitter -> {
          var subscription =
              chatModel
                  .stream(prompt)
                  .subscribe(
                      response -> emitter.onNext(converter.toResponse(response, true)),
                      emitter::onError,
                      emitter::onComplete);
          emitter.setCancellable(subscription::dispose);
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException("Spring AI live connections are not part of Phase 1");
  }
}
