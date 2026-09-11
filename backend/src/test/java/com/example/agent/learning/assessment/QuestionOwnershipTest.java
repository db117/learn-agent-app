package com.example.agent.learning.assessment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuestionOwnershipTest {

    @Test
    void synthesisQuestionHasChapterOwnershipInsteadOfLearnUnitOwnership() {
        Question question = new Question(
                "synthesis-question", null, "chapter-a", QuestionType.MULTIPLE_CHOICE, 1,
                "Which ability belongs to this chapter?", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[\"learnUnit-a\"]", false, QuestionRole.SYNTHESIS);

        assertEquals("chapter-a", question.chapterCode());
        assertEquals(QuestionRole.SYNTHESIS, question.role());
        assertEquals(null, question.learnUnitCode());
    }

    @Test
    void synthesisQuestionCannotUseLearnUnitOwnership() {
        assertThrows(IllegalArgumentException.class, () -> new Question(
                "synthesis-question", "learnUnit-a", "chapter-a", QuestionType.MULTIPLE_CHOICE, 1,
                "Question", 20, "{\"options\":[],\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", false, QuestionRole.SYNTHESIS));
    }
}
