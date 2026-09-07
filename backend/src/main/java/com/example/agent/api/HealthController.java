package com.example.agent.api;

import com.example.agent.persistence.SqliteRepository;
import com.google.adk.agents.LlmAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final SqliteRepository repository;
  private final LlmAgent tutorAgent;
  private final ChatModel chatModel;

  public HealthController(SqliteRepository repository, LlmAgent tutorAgent, ChatModel chatModel) {
    this.repository = repository;
    this.tutorAgent = tutorAgent;
    this.chatModel = chatModel;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    try {
      repository.probe();
      return new HealthResponse(
              "UP", "UP", tutorAgent == null ? "DOWN" : "UP", chatModel.getClass().getSimpleName(), Instant.now());
    } catch (RuntimeException error) {
      return new HealthResponse("DOWN", "DOWN", "UP", chatModel.getClass().getSimpleName(), Instant.now());
    }
  }
}
