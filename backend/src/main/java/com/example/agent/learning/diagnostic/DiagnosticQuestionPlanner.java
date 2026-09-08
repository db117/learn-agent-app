package com.example.agent.learning.diagnostic;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.LearnerProfile;

import java.util.List;

/**
 * 诊断和 LearnUnit 评估选题策略接口，与 LLM 提供商解耦。
 *
 * <p>实现可以从题库选择题目或提出新题，但最终必须经过 Java 校验并写入
 * SQLite；已存在的题目定义不能被覆盖。</p>
 */
public interface DiagnosticQuestionPlanner {

    /**
     * 为一次新的评估规划固定题集。
     *
     * @param language 目标学习语言
     * @param learnUnits 本次评估允许覆盖的 LearnUnit
     * @param availableQuestions 当前数据库中的活动题目
     * @param profile 学习者背景
     * @return 候选题集，由调用方校验后持久化
     */
    List<Question> plan(
            LearningLanguage language,
            List<LearnUnit> learnUnits,
            List<Question> availableQuestions,
            LearnerProfile profile);
}
