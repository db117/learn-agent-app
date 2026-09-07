package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.journey.LearnerProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断选题的确定性 fallback。
 *
 * <p>当 LLM 不可用或返回非法内容时，按技能各取一题选择题和一题 Coding
 * 题，保证诊断仍能创建且题集结构可预测。</p>
 */
public final class DeterministicDiagnosticQuestionPlanner implements DiagnosticQuestionPlanner {

    /** 根据数据库中的活动题目生成最小诊断题集。 */
    @Override
    public List<Question> plan(
            LearningLanguage language,
            List<LearningSkill> skills,
            List<Question> availableQuestions,
            LearnerProfile profile) {
        List<Question> result = new ArrayList<>();
        for (LearningSkill skill : skills) {
            if (!skill.diagnosticEligible()) continue;
            availableQuestions.stream()
                    .filter(question -> question.skillCode().equals(skill.code()))
                    .filter(question -> question.type() == QuestionType.MULTIPLE_CHOICE)
                    .findFirst()
                    .ifPresent(result::add);
            availableQuestions.stream()
                    .filter(question -> question.skillCode().equals(skill.code()))
                    .filter(question -> question.type() == QuestionType.CODING)
                    .findFirst()
                    .ifPresent(result::add);
        }
        return result;
    }
}
