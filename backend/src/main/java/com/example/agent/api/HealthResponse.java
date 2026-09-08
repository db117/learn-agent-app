package com.example.agent.api;

import java.time.Instant;

/**
 * 后端健康检查结果。
 *
 * @param status 总体状态
 * @param sqlite SQLite 可用性
 * @param agent Agent 运行时可用性
 * @param llm LLM 提供商可用性
 * @param checkedAt 检查时间
 */
public record HealthResponse(
        String status, String sqlite, String agent, String llm, Instant checkedAt) {
}
