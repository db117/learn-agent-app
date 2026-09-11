package com.example.agent.learning.journey;

/** 首次 Journey 大纲生成所需的用户输入，不包含 HTTP 或 AgentScope 类型。 */
public record JourneyDraftInput(
        String languageCode,
        String goal,
        String primaryLanguage,
        Integer experienceYears,
        String selfDescription,
        String learningGoal) {
}
