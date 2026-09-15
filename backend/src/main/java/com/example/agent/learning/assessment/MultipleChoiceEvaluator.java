package com.example.agent.learning.assessment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 选择题的确定性全对得分判题器，不调用 LLM。 */
public final class MultipleChoiceEvaluator {

    public Result evaluate(Question question, List<String> selectedOptionIds) {
        if (question.type() != QuestionType.MULTIPLE_CHOICE) {
            throw new IllegalArgumentException("Question is not multiple choice: " + question.id());
        }
        MultipleChoiceConfig config = question.config();
        if (config == null) throw new IllegalArgumentException("Invalid multiple-choice config: " + question.id());
        Set<String> expected = Set.copyOf(config.correctOptionIds());
        List<String> selectedValues = selectedOptionIds == null ? List.of() : selectedOptionIds;
        Set<String> selected = new HashSet<>(selectedValues);
        boolean correct = !expected.isEmpty() && selected.size() == selectedValues.size() && expected.equals(selected);
        return new Result(correct ? question.points() : 0, correct);
    }

    /**
     * 选择题的确定性判题结果。
     *
     * @param score 全对时为题目满分，否则为 0
     * @param correct 是否与正确选项集合完全一致
     */
    public record Result(int score, boolean correct) {
    }
}
