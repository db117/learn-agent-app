package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.HashSet;
import java.util.List;

/**
 * Journey 内某个 LearnUnit 的独立评估定义，题目评分不依赖 LLM。
 *
 * @param id SQLite 自增主键；创建时为 {@code null}
 * @param learnUnitCode 当前 Journey 内被评估的 LearnUnit 编码
 * @param passingScore 固定为 {@link LearningPathItem#DEFAULT_PASSING_SCORE} 的及格线
 * @param questions 当前评估的不可变问题快照，至少包含一个问题
 */
public record Assessment(Long id, String learnUnitCode, int passingScore, List<Question> questions) {
    public Assessment {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        learnUnitCode = DomainChecks.text(learnUnitCode, "learnUnitCode");
        DomainChecks.score(passingScore);
        if (passingScore != LearningPathItem.DEFAULT_PASSING_SCORE) {
            throw new DomainRuleViolation("passingScore must be 70");
        }
        if (questions == null || questions.isEmpty()) {
            throw new DomainRuleViolation("questions must not be empty");
        }
        questions = List.copyOf(questions);
        var codes = new HashSet<String>();
        for (Question question : questions) {
            if (!codes.add(question.code())) {
                throw new DomainRuleViolation("question codes must be unique");
            }
        }
    }

    public static Assessment create(String learnUnitCode, int passingScore, List<Question> questions) {
        return new Assessment(null, learnUnitCode, passingScore, questions);
    }

    public Assessment withId(long persistedId, List<Question> persistedQuestions) {
        return new Assessment(
                DomainChecks.id(persistedId, "id"), learnUnitCode, passingScore, persistedQuestions);
    }
}
