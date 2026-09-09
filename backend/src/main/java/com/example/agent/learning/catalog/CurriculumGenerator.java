package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;

import java.util.List;

/**
 * 学习课程目录生成接口。
 *
 * <p>实现可以使用 LLM 生成语言和 LearnUnit 内容；调用方负责校验后写入
 * SQLite。接口不暴露 Spring AI 类型，保证 Learning 领域与模型提供商解耦。</p>
 */
public interface CurriculumGenerator {

    /** 根据用户提出的目标语言和学习背景，按需生成一份可持久化的课程目录。 */
    GeneratedCurriculum generate(String requestedLanguage, String learningContext);

    /**
     * 一次目录生成的完整结果。
     *
     * @param languages 学习语言目录
     * @param learnUnits 语言下的 LearnUnit 教学内容
     * @param questions LearnUnit 的适用题目
     */
    record GeneratedCurriculum(
            List<LearningLanguage> languages,
            List<LearnUnit> learnUnits,
            List<Question> questions) {

        public GeneratedCurriculum {
            languages = List.copyOf(languages == null ? List.of() : languages);
            learnUnits = List.copyOf(learnUnits == null ? List.of() : learnUnits);
            questions = List.copyOf(questions == null ? List.of() : questions);
        }
    }
}
