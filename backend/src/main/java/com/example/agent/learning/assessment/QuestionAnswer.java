package com.example.agent.learning.assessment;

import java.util.List;

/**
 * 客户端提交的一道题的答案，不包含服务端计算出的分数。
 *
 * @param questionId 题目编码
 * @param selectedOptionIds 选择题选中的选项编码
 * @param submittedCode Coding 题提交的代码
 */
public record QuestionAnswer(
        String questionId,
        List<String> selectedOptionIds,
        String submittedCode) {

    public QuestionAnswer {
        selectedOptionIds = List.copyOf(selectedOptionIds == null ? List.of() : selectedOptionIds);
    }
}
