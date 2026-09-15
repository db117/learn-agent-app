package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.CodingRubric;
import com.example.agent.learning.assessment.MultipleChoiceConfig;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionOption;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearnUnitContentValidator;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.llm.contract.LlmContracts;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 使用 AgentScope Model 按用户指定的目标语言按需生成 LearnUnit 内容。
 *
 * <p>这里是唯一的提供商适配边界；生成结果进入领域校验和 SQLite 后，运行时不再
 * 依赖模型响应的临时状态。</p>
 */
@Component
public final class LlmCurriculumGenerator implements CurriculumGenerator {

    private static final ResponseFormat CONTENT_RESPONSE_FORMAT = ResponseFormat.jsonSchema(
            JsonSchema.builder()
                    .name("learn_unit_content")
                    .description("Structured teaching content and independent questions for one LearnUnit")
                    .schema(contentSchema())
                    .strict(true)
                    .build());
    private final Model model;
    private final ObjectMapper mapper;

    public LlmCurriculumGenerator(Model model) {
        this(model, new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    @Autowired
    public LlmCurriculumGenerator(Model model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public GeneratedOutline generateOutline(String requestedLanguage, String learningContext) {
        return generateOutline(requestedLanguage, learningContext, ignored -> {
        });
    }

    @Override
    public GeneratedOutline generateOutline(
            String requestedLanguage, String learningContext, Consumer<String> onText) {
        if (requestedLanguage == null || requestedLanguage.isBlank()) {
            throw new IllegalArgumentException("Requested language must not be blank");
        }
        String language = requestedLanguage.trim();
        String prompt = """
                你是一个学习系统的课程架构师。请只生成学习大纲，返回 JSON，不要返回 Markdown 或解释文字：
                {
                  "languages":[{"code":"...","name":"...","description":"..."}],
                  "chapters":[{
                    "code":"...","name":"...","goal":"...","sequence":1,"prerequisiteChapterCodes":[]
                  }],
                  "learnUnits":[{
                    "languageCode":"...","chapterCode":"...","code":"...","name":"...","description":"...",
                    "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":70,
                    "learningObjectives":["..."],"keyConcepts":["..."]
                  }]
                }
                用户指定的目标编程语言是：%s
                只生成这个目标语言，不要生成其他语言；languages 数组必须只有一个元素。
                生成足以覆盖学习目标的循序渐进 Chapter 和 LearnUnit，Chapter 数量按内容需要决定，不要为了凑数重复内容。
                每个 Chapter 大约包含 3 到 7 个 LearnUnit；每个 LearnUnit 只表达一个可独立验证的能力。
                学习者的目标和背景如下，请让 Chapter、知识点顺序和前置关系与其相关：%s
                至少有一个无前置 Chapter 和一个无前置 LearnUnit 的起点；前置关系只能引用同一响应中已经生成的 code，不能循环。
                每个 LearnUnit 必须引用一个 Chapter；Chapter 和 LearnUnit 的 code 都必须唯一。
                code 使用稳定、简短、适合 URL 的英文标识；每个 LearnUnit 的 code 必须唯一。
                name、goal、description、learningObjectives、keyConcepts 使用中文，但技术术语和语言名称可以保留英文。
                每个 LearnUnit 必须提供至少一个学习目标和一个关键知识点。
                如果上下文包含用户调整要求，只调整知识点、单元说明和学习顺序/前置关系，不修改 passScore、minCodingScore 或诊断规则。
                不要生成 lessonIntro、examples、questions 或任何考试内容。
                """.formatted(language, learningContext == null ? "" : learningContext.trim());
        try {
            LlmContracts.Outline root = mapper.readValue(
                    extractJson(AgentScopeTextGenerator.generate(model, prompt, onText)), LlmContracts.Outline.class);
            return parseOutline(root);
        } catch (Exception error) {
            throw new IllegalArgumentException("Curriculum outline generator returned invalid JSON", error);
        }
    }

    @Override
    public GeneratedLearnUnitContent generateContent(LearnUnit outline, String learningContext) {
        return generateContent(outline, learningContext, ignored -> {
        });
    }

    @Override
    public GeneratedLearnUnitContent generateContent(
            LearnUnit outline, String learningContext, Consumer<String> onText) {
        if (outline == null) throw new IllegalArgumentException("LearnUnit outline is required");
        if (outline.hasDetailedContent()) return new GeneratedLearnUnitContent(outline, List.of());
        String prompt = """
                你是一个学习系统的教学内容作者。请为下面这个已经确认的 LearnUnit 生成一个短小、结构化的教学循环，返回符合 response schema 的 JSON，不要在 JSON 外返回 Markdown 或解释文字。
                学习者背景：%s
                LearnUnit 大纲：目标语言=%s, code=%s, name=%s, description=%s, objectives=%s, concepts=%s
                ability 必须只有一个能力，estimatedMinutes 必须是 1 到 30 的整数。
                lessonIntro 不超过 2000 字；examples 至少一个且不超过 5 个；guidedPracticePrompt 和 independentCheckPrompt 必须具体、自洽、仅凭页面内容即可回答。
                guidedPracticePrompt 必须能在文本输入框中完成：题面内给出所有必要的代码、输入和数据，并明确要求输出或解释；不得要求创建或修改文件、运行终端命令、安装包、访问网络、使用 IDE，或依赖未说明的版本、配置和运行环境。
                independentCheckPrompt 和每道 question.prompt 也必须提供足够上下文，不得依赖题面外的文件、命令、配置、网络或未说明的版本；CODING 题必须能在页面编辑器中完成。
                教学文本需要分段时使用换行；展示代码时将代码放在单独的 fenced code block 中，代码围栏内注明语言。
                guidedPracticeHints 必须是 0 到 3 个提示，不能超过 3 个，每个不超过 300 字。
                questions 必须包含 1 到 5 道固定的独立检查题，只能使用 MULTIPLE_CHOICE 或 CODING，且必须能验证这个 LearnUnit。
                CODING 题必须使用目标语言，并且题目内容要能验证这个 LearnUnit 的能力。
                不要生成诊断题、分数结论或多个能力。
                """.formatted(
                learningContext == null ? "" : learningContext.trim(), outline.languageCode(),
                outline.code(), outline.name(), outline.description(), outline.learningObjectives(), outline.keyConcepts());
        String response = AgentScopeTextGenerator.generate(model, prompt, CONTENT_RESPONSE_FORMAT, onText);
        LlmContracts.Content generatedContent;
        try {
            generatedContent = mapper.readValue(extractJson(response), LlmContracts.Content.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("LearnUnit content generator returned invalid JSON", error);
        }
        try {
            String ability = requiredText(generatedContent.ability(), "ability");
            int estimatedMinutes = requiredBoundedInt(generatedContent.estimatedMinutes(), "estimatedMinutes", 1, 30);
            String intro = boundedText(generatedContent.lessonIntro(), "lessonIntro", 2000);
            List<String> examples = boundedStrings(generatedContent.examples(), "examples", 1, 5, 2000);
            String guidedPrompt = boundedText(generatedContent.guidedPracticePrompt(), "guidedPracticePrompt", 1000);
            List<String> guidedHints = boundedStrings(generatedContent.guidedPracticeHints(), "guidedPracticeHints", 0, 3, 300);
            String independentPrompt = boundedText(generatedContent.independentCheckPrompt(), "independentCheckPrompt", 1000);
            List<Question> questions = parseIndependentQuestions(generatedContent.questions(), outline);
            LearnUnit content = new LearnUnit(
                    outline.id(), outline.languageCode(), outline.code(), outline.chapterCode(), outline.name(),
                    outline.description(), outline.sequence(), outline.prerequisiteLearnUnitCodes(), outline.passScore(),
                    outline.minCodingScore(), outline.enabled(), outline.learningObjectives(), intro,
                    outline.keyConcepts(), examples, outline.diagnosticEligible(), ability, estimatedMinutes,
                    guidedPrompt, guidedHints, independentPrompt);
            LearnUnitContentValidator.validate(outline, content, questions);
            return new GeneratedLearnUnitContent(content, questions);
        } catch (Exception error) {
            String reason = error.getMessage();
            throw new IllegalArgumentException(
                    "LearnUnit content generator returned invalid content"
                            + (reason == null || reason.isBlank() ? "" : ": " + reason), error);
        }
    }

    private static Map<String, Object> contentSchema() {
        return objectSchema(Map.of(
                "ability", Map.of("type", "string"),
                "estimatedMinutes", Map.of("type", "integer"),
                "lessonIntro", Map.of("type", "string"),
                "examples", arraySchema(Map.of("type", "string")),
                "guidedPracticePrompt", Map.of("type", "string"),
                "guidedPracticeHints", Map.of(
                        "type", "array", "items", Map.of("type", "string"), "maxItems", 3),
                "independentCheckPrompt", Map.of("type", "string"),
                "questions", arraySchema(questionSchema())));
    }

    private static Map<String, Object> questionSchema() {
        return Map.of("anyOf", List.of(
                objectSchema(Map.ofEntries(
                        Map.entry("type", Map.of("type", "string", "enum", List.of("MULTIPLE_CHOICE"))),
                        Map.entry("difficulty", Map.of("type", "integer")),
                        Map.entry("prompt", Map.of("type", "string")),
                        Map.entry("points", Map.of("type", "integer")),
                        Map.entry("options", arraySchema(optionSchema())),
                        Map.entry("correctOptionIds", arraySchema(Map.of("type", "string"))),
                        Map.entry("multiple", Map.of("type", "boolean")),
                        Map.entry("language", nullableSchema("string")),
                        Map.entry("starterCode", nullableSchema("string")),
                        Map.entry("rubric", nullableObjectSchema(rubricProperties())),
                        Map.entry("referenceConcepts", arraySchema(Map.of("type", "string"))))),
                objectSchema(Map.ofEntries(
                        Map.entry("type", Map.of("type", "string", "enum", List.of("CODING"))),
                        Map.entry("difficulty", Map.of("type", "integer")),
                        Map.entry("prompt", Map.of("type", "string")),
                        Map.entry("points", Map.of("type", "integer")),
                        Map.entry("options", nullableArraySchema(optionSchema())),
                        Map.entry("correctOptionIds", nullableArraySchema(Map.of("type", "string"))),
                        Map.entry("multiple", nullableSchema("boolean")),
                        Map.entry("language", Map.of("type", "string")),
                        Map.entry("starterCode", Map.of("type", "string")),
                        Map.entry("rubric", rubricSchema()),
                        Map.entry("referenceConcepts", arraySchema(Map.of("type", "string")))))));
    }

    private static Map<String, Object> optionSchema() {
        return objectSchema(Map.of(
                "id", Map.of("type", "string"),
                "text", Map.of("type", "string")));
    }

    private static Map<String, Object> rubricSchema() {
        return objectSchema(rubricProperties());
    }

    private static Map<String, Object> rubricProperties() {
        return Map.of(
                "correctness", rubricWeightSchema(),
                "languageUsage", rubricWeightSchema(),
                "clarity", rubricWeightSchema());
    }

    private static Map<String, Object> rubricWeightSchema() {
        return Map.of("type", "integer", "minimum", 0, "maximum", 100);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> nullableArraySchema(Map<String, Object> items) {
        return Map.of("type", List.of("array", "null"), "items", items);
    }

    private static Map<String, Object> nullableSchema(String type) {
        return Map.of("type", List.of(type, "null"));
    }

    private static Map<String, Object> nullableObjectSchema(Map<String, Object> properties) {
        return Map.of(
                "type", List.of("object", "null"),
                "properties", properties,
                "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.copyOf(properties.keySet()),
                "additionalProperties", false);
    }

    private List<Question> parseIndependentQuestions(List<LlmContracts.Question> nodes, LearnUnit outline) {
        if (nodes == null || nodes.size() < 1 || nodes.size() > 5) {
            throw new IllegalArgumentException("independent questions must contain 1 to 5 items");
        }
        List<Question> result = new ArrayList<>();
        for (LlmContracts.Question node : nodes) {
            QuestionType type = requireQuestionType(node.type());
            MultipleChoiceConfig config = null;
            CodingRubric rubric = null;
            if (type == QuestionType.MULTIPLE_CHOICE) {
                config = multipleChoiceConfig(node);
            } else {
                rubric = codingRubric(node.rubric());
            }
            Question question = new Question(
                    "generated-independent-question-" + UUID.randomUUID(), outline.code(), type,
                    requiredBoundedInt(node.difficulty(), "difficulty", 1, 5), requiredText(node.prompt(), "prompt"),
                    requiredBoundedInt(node.points(), "points", 1, 1000), config, rubric,
                    nullableText(node.language()), nullableText(node.starterCode()),
                    strings(node.referenceConcepts(), "referenceConcepts"), false,
                    QuestionRole.INDEPENDENT);
            QuestionStructureValidator.validate(question, outline);
            result.add(question);
        }
        return result;
    }

    private GeneratedOutline parseOutline(LlmContracts.Outline root) {
        List<LlmContracts.Language> languageNodes = requiredItems(root == null ? null : root.languages(), "languages");
        List<LlmContracts.Chapter> chapterNodes = requiredItems(root == null ? null : root.chapters(), "chapters");
        List<LlmContracts.LearnUnit> learnUnitNodes = requiredItems(root == null ? null : root.learnUnits(), "learnUnits");
        if (languageNodes.isEmpty() || chapterNodes.isEmpty() || learnUnitNodes.isEmpty()) {
            throw new IllegalArgumentException("languages, chapters and learnUnits must be non-empty arrays");
        }
        List<LearningLanguage> languages = new ArrayList<>();
        Set<String> languageCodes = new HashSet<>();
        for (LlmContracts.Language node : languageNodes) {
            String code = requiredText(node.code(), "code");
            if (!languageCodes.add(code)) throw new IllegalArgumentException("Duplicate language code: " + code);
            languages.add(new LearningLanguage(
                    "generated-language-" + UUID.randomUUID(), code, requiredText(node.name(), "name"),
                    requiredText(node.description(), "description"), true));
        }
        List<Chapter> chapters = new ArrayList<>();
        Set<String> chapterCodes = new HashSet<>();
        for (LlmContracts.Chapter node : chapterNodes) {
            String code = requiredText(node.code(), "code");
            if (!chapterCodes.add(code)) throw new IllegalArgumentException("Duplicate Chapter code: " + code);
            chapters.add(new Chapter(
                    "generated-chapter-" + UUID.randomUUID(), code, requiredText(node.name(), "name"),
                    requiredText(node.goal(), "goal"), requiredBoundedInt(node.sequence(), "sequence", 1, 1000),
                    strings(node.prerequisiteChapterCodes(), "prerequisiteChapterCodes")));
        }
        for (Chapter chapter : chapters) {
            Set<String> prerequisites = new HashSet<>();
            for (String prerequisite : chapter.prerequisiteChapterCodes()) {
                if (!prerequisites.add(prerequisite) || !chapterCodes.contains(prerequisite)
                        || prerequisite.equals(chapter.code())) {
                    throw new IllegalArgumentException("Invalid prerequisite Chapter: " + chapter.code());
                }
            }
        }
        List<LearnUnit> learnUnits = new ArrayList<>();
        Set<String> learnUnitCodes = new HashSet<>();
        for (LlmContracts.LearnUnit node : learnUnitNodes) {
            String code = requiredText(node.code(), "code");
            String languageCode = requiredText(node.languageCode(), "languageCode");
            String chapterCode = requiredText(node.chapterCode(), "chapterCode");
            if (!languageCodes.contains(languageCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown language: " + code);
            }
            if (!chapterCodes.contains(chapterCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown Chapter: " + code);
            }
            if (!learnUnitCodes.add(code)) throw new IllegalArgumentException("Duplicate LearnUnit code: " + code);
            List<String> objectives = strings(node.learningObjectives(), "learningObjectives");
            List<String> concepts = strings(node.keyConcepts(), "keyConcepts");
            if (objectives.isEmpty() || concepts.isEmpty()) {
                throw new IllegalArgumentException("Outline LearnUnit needs objectives and keyConcepts");
            }
            learnUnits.add(new LearnUnit(
                    "generated-learn-unit-" + UUID.randomUUID(), languageCode, code, chapterCode,
                    requiredText(node.name(), "name"),
                    requiredText(node.description(), "description"), requiredBoundedInt(node.sequence(), "sequence", 1, 1000),
                    strings(node.prerequisiteLearnUnitCodes(), "prerequisiteLearnUnitCodes"),
                    requiredBoundedInt(node.passScore(), "passScore", 0, 100),
                    nullableBoundedInt(node.minCodingScore(), "minCodingScore", 0, 100), true, objectives, "", concepts, List.of(), true));
        }
        for (LearnUnit learnUnit : learnUnits) {
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                if (!learnUnitCodes.contains(prerequisite)) {
                    throw new IllegalArgumentException("Unknown prerequisite LearnUnit: " + prerequisite);
                }
            }
        }
        return new GeneratedOutline(languages, chapters, learnUnits);
    }

    private int requiredBoundedInt(Integer value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException("Missing or invalid " + field);
        int result = value;
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private Integer nullableBoundedInt(Integer value, String field, int min, int max) {
        if (value == null) return null;
        int result = value;
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private String boundedText(String raw, String field, int maxLength) {
        String value = requiredText(raw, field);
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    private List<String> boundedStrings(List<String> raw, String field, int min, int max, int itemMaxLength) {
        List<String> values = strings(raw, field);
        if (values.size() < min || values.size() > max) {
            throw new IllegalArgumentException(field + " has an invalid size");
        }
        if (values.stream().anyMatch(value -> value.length() > itemMaxLength)) {
            throw new IllegalArgumentException("Array field contains oversized text");
        }
        return values;
    }

    private List<String> strings(List<String> values, String field) {
        if (values == null) throw new IllegalArgumentException("Missing " + field);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Array fields must contain non-empty strings");
            }
            result.add(value.trim());
        }
        return result;
    }

    private String requiredText(String raw, String field) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private String nullableText(String raw) {
        return raw == null ? null : raw.trim();
    }

    private MultipleChoiceConfig multipleChoiceConfig(LlmContracts.Question question) {
        if (question.options() == null || question.correctOptionIds() == null || question.multiple() == null) {
            throw new IllegalArgumentException("Multiple choice question needs options, correctOptionIds and multiple");
        }
        List<QuestionOption> options = question.options().stream()
                .map(option -> new QuestionOption(requiredText(option.id(), "option.id"), requiredText(option.text(), "option.text")))
                .toList();
        return new MultipleChoiceConfig(options, strings(question.correctOptionIds(), "correctOptionIds"), question.multiple());
    }

    private CodingRubric codingRubric(LlmContracts.Rubric rubric) {
        if (rubric == null) throw new IllegalArgumentException("Coding question rubric is required");
        return new CodingRubric(rubric.correctness(), rubric.languageUsage(), rubric.clarity());
    }

    private QuestionType requireQuestionType(QuestionType type) {
        if (type == null) throw new IllegalArgumentException("Missing question type");
        return type;
    }

    private <T> List<T> requiredItems(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException("Missing " + field);
        return values;
    }

    private String extractJson(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty curriculum response");
        String trimmed = value.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int newline = trimmed.indexOf('\n');
            trimmed = newline >= 0
                    ? trimmed.substring(newline + 1, trimmed.length() - 3).trim()
                    : trimmed.substring(3, trimmed.length() - 3).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Response is not a JSON object");
        return trimmed.substring(start, end + 1);
    }
}
