package com.example.agent.learning.scoring;

import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionType;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 评估总分计算器。
 *
 * <p>选择题和 Coding 题分别计算百分比；两类题同时存在时按 40%/60% 合成总分，结果不依赖 LLM。
 */
@Component
public class AssessmentScoreEngine {

    public AssessmentScore score(List<ScoredQuestion> questions) {
        int choiceEarned = 0;
        int choiceMax = 0;
        int codingEarned = 0;
        int codingMax = 0;
        for (ScoredQuestion question : questions) {
            if (question.maxScore() <= 0 || question.score() < 0 || question.score() > question.maxScore()) {
                throw new IllegalArgumentException("Question score is outside its maximum");
            }
            if (question.type() == QuestionType.MULTIPLE_CHOICE) {
                choiceEarned += question.score();
                choiceMax += question.maxScore();
            } else if (question.type() == QuestionType.CODING) {
                codingEarned += question.score();
                codingMax += question.maxScore();
            }
        }
        boolean hasChoice = choiceMax > 0;
        boolean hasCoding = codingMax > 0;
        int choiceScore = hasChoice ? percent(choiceEarned, choiceMax) : 0;
        int codingScore = hasCoding ? percent(codingEarned, codingMax) : 0;
        int total = hasChoice && hasCoding
                ? Math.round(choiceScore * 0.4f + codingScore * 0.6f)
                : hasChoice ? choiceScore : hasCoding ? codingScore : 0;
        return new AssessmentScore(choiceScore, codingScore, total, hasChoice, hasCoding);
    }

    public AssessmentScore scoreAttempts(List<QuestionAttempt> attempts, Map<String, QuestionType> types) {
        return score(attempts.stream()
                .map(attempt -> {
                    QuestionType type = types.get(attempt.questionId());
                    if (type == null) throw new IllegalArgumentException("Unknown question: " + attempt.questionId());
                    if (attempt.score() == null) {
                        throw new IllegalArgumentException("Question attempt has not been evaluated: " + attempt.questionId());
                    }
                    return new ScoredQuestion(type, attempt.score(), attempt.maxScore());
                })
                .toList());
    }

    private int percent(int earned, int max) {
        return (int) Math.round(earned * 100.0 / max);
    }

    /**
     * Score Engine 计算单题百分比时使用的最小输入。
     *
     * @param type 题型
     * @param score 本题实际得分
     * @param maxScore 本题满分
     */
    public record ScoredQuestion(QuestionType type, int score, int maxScore) {
        public ScoredQuestion {
            if (type == null) throw new IllegalArgumentException("Question type is required");
            if (maxScore <= 0) throw new IllegalArgumentException("Question maximum is required");
        }
    }
}
