package com.example.agent.learning.path;

import java.time.Instant;

/** 一次不参与评分的引导练习交互。 */
public record GuidedPracticeEntry(String response, String feedback, Instant createdAt) {
}
