package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.domain.Answer;

import java.util.List;
import java.util.Set;

/** 当前 LearnUnit 的 Assessment 提交；不接受客户端提供 score。 */
public record AssessmentSubmissionRequest(
        /** 当前评估对应的 LearnUnit 编码。 */
        String learnUnitCode,
        /** 当前 Assessment 的全部答案。 */
        List<AnswerRequest> answers) {

    public AssessmentSubmissionRequest {
        answers = List.copyOf(answers == null ? List.of() : answers);
    }

    public List<Answer> toDomain() {
        return answers.stream().map(AnswerRequest::toDomain).toList();
    }

    /** 一个问题的选择题或 Workspace 引用答案。 */
    public record AnswerRequest(
            /** Assessment 问题编码。 */
            String questionCode,
            /** 选择题答案编码。 */
            List<String> selectedOptionIds,
            /** 编码题使用的 Workspace/Artifact 引用。 */
            String workspaceReference) {
        public AnswerRequest {
            selectedOptionIds = List.copyOf(selectedOptionIds == null ? List.of() : selectedOptionIds);
        }

        private Answer toDomain() {
            return new Answer(questionCode, Set.copyOf(selectedOptionIds), workspaceReference);
        }
    }
}
