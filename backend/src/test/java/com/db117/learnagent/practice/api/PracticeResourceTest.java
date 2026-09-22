package com.db117.learnagent.practice.api;

import com.db117.learnagent.practice.domain.ChoiceOption;
import com.db117.learnagent.practice.domain.ChoiceQuestion;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeResourceTest {
    @Test
    void choiceProjectionExposesOnlyThePersistedQuestionAndOptions() throws Exception {
        PracticeTask task = PracticeTask.create(
                        7L,
                        8L,
                        "typescript",
                        "CHOICE",
                        "选择题：类型检查",
                        "选择题练习",
                        1,
                        "",
                        new ChoiceQuestion(
                                "TypeScript 的类型检查主要发生在哪里？",
                                List.of(
                                        new ChoiceOption("a", "编译期"),
                                        new ChoiceOption("b", "运行时")),
                                "a"),
                        new VerificationPolicy(false, false, false, false, true),
                        Instant.parse("2026-01-01T00:00:00Z"))
                .withPersistedIds(11L, List.of());

        String body = new ObjectMapper().writeValueAsString(PracticeResource.ChoiceResponse.from(task, false));

        assertTrue(body.contains("选择题：类型检查"));
        assertTrue(body.contains("编译期"));
        assertFalse(body.contains("correctOptionId"));
        assertFalse(body.contains("choiceCorrect"));
        assertFalse(body.contains("\"codeVerified\":true"));
    }
}
