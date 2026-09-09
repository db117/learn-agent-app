package com.example.agent.api;

import com.example.agent.persistence.SqliteRepository;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 后端、SQLite、AgentScope TutorAgent 和 LLM 提供商的健康检查接口。 */
@RestController
@RequestMapping("/api")
public class HealthController {

  private final SqliteRepository repository;
  private final HarnessAgent tutorAgent;
  private final Model model;

  public HealthController(SqliteRepository repository, HarnessAgent tutorAgent, Model model) {
    this.repository = repository;
    this.tutorAgent = tutorAgent;
    this.model = model;
  }

  /** 返回各运行时依赖的可用状态。 */
  @GetMapping("/health")
  public HealthResponse health() {
    try {
      repository.probe();
      return new HealthResponse(
              "UP", "UP", tutorAgent == null ? "DOWN" : "UP", model.getClass().getSimpleName(), Instant.now());
    } catch (RuntimeException error) {
      return new HealthResponse("DOWN", "DOWN", "UP", model.getClass().getSimpleName(), Instant.now());
    }
  }
}
