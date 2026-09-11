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

    /** 只生成 Journey 确认前需要展示的课程大纲，不生成教学正文或题目。 */
    GeneratedOutline generateOutline(String requestedLanguage, String learningContext);

    /** 生成大纲时把模型文本增量交给上层 UI；旧实现默认退化为一次性生成。 */
    default GeneratedOutline generateOutline(
            String requestedLanguage, String learningContext, Consumer<String> onText) {
        return generateOutline(requestedLanguage, learningContext);
    }

    /** 为已经确认的单元按需生成完整教学内容和固定的独立检查题目。 */
    default GeneratedLearnUnitContent generateContent(LearnUnit outline, String learningContext) {
        throw new IllegalStateException("Curriculum generator does not support LearnUnit content generation");
    }

    /** Journey 确认前可持久化的结构化大纲。 */
    record GeneratedOutline(
            List<LearningLanguage> languages,
            List<Chapter> chapters,
            List<LearnUnit> learnUnits) {

        public GeneratedOutline {
            languages = List.copyOf(languages == null ? List.of() : languages);
            chapters = List.copyOf(chapters == null ? List.of() : chapters);
            learnUnits = List.copyOf(learnUnits == null ? List.of() : learnUnits);
        }

    }

    /** 首次进入 LearnUnit 时一次性生成并原子持久化的内容。 */
    record GeneratedLearnUnitContent(LearnUnit learnUnit, List<Question> independentQuestions) {

        public GeneratedLearnUnitContent {
            independentQuestions = List.copyOf(independentQuestions == null ? List.of() : independentQuestions);
        }
    }
}
