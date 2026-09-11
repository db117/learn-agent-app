package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;

import java.util.List;
import java.util.function.Consumer;

/**
 * 学习课程目录生成接口。
 *
 * <p>实现可以使用 LLM 生成语言和 LearnUnit 内容；调用方负责校验后写入
 * SQLite。接口不暴露模型提供商类型，保证 Learning 领域与模型提供商解耦。</p>
 */
public interface CurriculumGenerator {

    /** 根据用户提出的目标语言和学习背景，按需生成一份可持久化的课程目录。 */
    GeneratedCurriculum generate(String requestedLanguage, String learningContext);

    /** 只生成 Journey 确认前需要展示的课程大纲，不生成教学正文或题目。 */
    default GeneratedOutline generateOutline(String requestedLanguage, String learningContext) {
        GeneratedCurriculum generated = generate(requestedLanguage, learningContext);
        return new GeneratedOutline(generated.languages(), generated.learnUnits());
    }

    /** 生成大纲时把模型文本增量交给上层 UI；旧实现默认退化为一次性生成。 */
    default GeneratedOutline generateOutline(
            String requestedLanguage, String learningContext, Consumer<String> onText) {
        return generateOutline(requestedLanguage, learningContext);
    }

    /** 为已经确认的单元按需生成教学正文。 */
    default LearnUnit generateContent(LearnUnit outline, String learningContext) {
        if (outline.hasDetailedContent()) return outline;
        throw new IllegalStateException("Curriculum generator does not support LearnUnit content generation");
    }

    /** Journey 确认前可持久化的结构化大纲。 */
    record GeneratedOutline(
            List<LearningLanguage> languages,
            List<LearnUnit> learnUnits) {

        public GeneratedOutline {
            languages = List.copyOf(languages == null ? List.of() : languages);
            learnUnits = List.copyOf(learnUnits == null ? List.of() : learnUnits);
        }
    }

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
