package com.db117.learnagent.practice;

import com.db117.learnagent.practice.domain.ChoiceOption;
import com.db117.learnagent.practice.domain.ChoiceQuestion;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChoicePracticeDomainTest {
    @Test
    void choiceQuestionAcceptsOnlyDeclaredOptions() {
        var question = new ChoiceQuestion(
                "哪个目标属于本单元？",
                List.of(
                        new ChoiceOption("objective", "理解变量类型"),
                        new ChoiceOption("distractor", "配置数据库")),
                "objective");

        assertTrue(question.hasOption("objective"));
        assertFalse(question.hasOption("missing"));
        assertTrue(question.isCorrect("objective"));
        assertFalse(question.isCorrect("distractor"));
        assertThrows(IllegalArgumentException.class,
                () -> new ChoiceQuestion("题目", question.options(), "missing"));
    }

    @Test
    void choicePolicyRequiresCorrectAnswer() {
        var policy = new VerificationPolicy(false, false, false, false, true);
        var failed = new PracticeEvidence(
                false, false, 0, false, RuntimeResult.NOT_RUN, List.of(), Instant.now(), false);
        var passed = new PracticeEvidence(
                false, false, 0, false, RuntimeResult.NOT_RUN, List.of(), Instant.now(), true);

        assertFalse(policy.accepts(failed));
        assertTrue(policy.accepts(passed));
        assertTrue(passed.isVerified(policy));
    }

    @Test
    void choiceTaskKeepsTheGeneratedQuestionSnapshot() {
        var question = new ChoiceQuestion(
                "哪一项正确？",
                List.of(new ChoiceOption("a", "正确"), new ChoiceOption("b", "错误")),
                "a");

        var task = PracticeTask.create(
                1,
                1,
                "typescript",
                "CHOICE",
                "选择题",
                "练习",
                1,
                "",
                question,
                new VerificationPolicy(false, false, false, false, true),
                Instant.now());

        assertEquals(question, task.choiceQuestion());
    }
}
