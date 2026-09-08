package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断选题的确定性 fallback。
 *
 * <p>当 LLM 不可用时，按 LearnUnit 各取一题选择题和一题 Coding
 * 题，保证诊断仍能创建且题集结构可预测。</p>
 */
public final class DeterministicDiagnosticQuestionPlanner implements DiagnosticQuestionPlanner {

    /** 根据数据库中的活动题目生成最小诊断题集。 */
    @Override
    public List<Question> plan(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> availableQuestions,
            LearnerProfile profile) {
        List<Question> result = new ArrayList<>();
        for (LearnUnit learnUnit : learnUnits) {
            availableQuestions.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .filter(question -> question.type() == QuestionType.MULTIPLE_CHOICE)
                    .findFirst()
                    .ifPresent(result::add);
            availableQuestions.stream()
                    .filter(question -> question.learnUnitCode().equals(learnUnit.code()))
                    .filter(question -> question.type() == QuestionType.CODING)
                    .findFirst()
                    .ifPresent(result::add);
        }
        return result;
    }
}
