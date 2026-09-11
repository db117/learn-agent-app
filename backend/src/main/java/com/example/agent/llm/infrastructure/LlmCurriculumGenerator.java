package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearnUnitContentValidator;
import com.example.agent.learning.catalog.LearningLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Model model;

    public LlmCurriculumGenerator(Model model) {
        this.model = model;
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
            JsonNode root = MAPPER.readTree(extractJson(AgentScopeTextGenerator.generate(model, prompt, onText)));
            return parseOutline(root);
        } catch (Exception error) {
            throw new IllegalArgumentException("Curriculum outline generator returned invalid JSON", error);
        }
    }

    @Override
    public GeneratedLearnUnitContent generateContent(LearnUnit outline, String learningContext) {
        if (outline == null) throw new IllegalArgumentException("LearnUnit outline is required");
        if (outline.hasDetailedContent()) return new GeneratedLearnUnitContent(outline, List.of());
        String prompt = """
                你是一个学习系统的教学内容作者。请为下面这个已经确认的 LearnUnit 生成一个短小、结构化的教学循环，返回 JSON，不要返回 Markdown：
                {
                  "ability":"本单元唯一可独立验证的能力",
                  "estimatedMinutes":10,
                  "lessonIntro":"...",
                  "examples":["..."],
                  "guidedPracticePrompt":"...",
                  "guidedPracticeHints":["..."],
                  "independentCheckPrompt":"...",
                  "questions":[{"type":"MULTIPLE_CHOICE","difficulty":1,"prompt":"...","points":20,
                    "options":[{"id":"A","text":"..."},{"id":"B","text":"..."}],
                    "correctOptionIds":["A"],"multiple":false,"referenceConcepts":["..."]}]
                }
                学习者背景：%s
                LearnUnit 大纲：code=%s, name=%s, description=%s, objectives=%s, concepts=%s
                ability 必须只有一个能力，estimatedMinutes 必须是 1 到 30 的整数。
                lessonIntro 不超过 2000 字；examples 至少一个且不超过 5 个；guidedPracticePrompt 和 independentCheckPrompt 必须具体。
                questions 必须包含 1 到 5 道固定的独立检查题，只能使用 MULTIPLE_CHOICE 或 CODING，且必须能验证这个 LearnUnit。
                不要生成诊断题、分数结论或多个能力。
                """.formatted(
                learningContext == null ? "" : learningContext.trim(), outline.code(), outline.name(),
                outline.description(), outline.learningObjectives(), outline.keyConcepts());
        try {
            JsonNode root = MAPPER.readTree(extractJson(AgentScopeTextGenerator.generate(model, prompt)));
            JsonNode abilities = root.get("abilities");
            if (abilities != null && (!abilities.isArray() || abilities.size() != 1)) {
                throw new IllegalArgumentException("LearnUnit content must contain exactly one ability");
            }
            String ability = requiredText(root, "ability");
            int estimatedMinutes = requiredBoundedInt(root, "estimatedMinutes", 1, 30);
            String intro = boundedText(root, "lessonIntro", 2000);
            List<String> examples = boundedStrings(root.get("examples"), 1, 5, 2000);
            String guidedPrompt = boundedText(root, "guidedPracticePrompt", 1000);
            List<String> guidedHints = boundedStrings(root.get("guidedPracticeHints"), 0, 3, 300);
            String independentPrompt = boundedText(root, "independentCheckPrompt", 1000);
            List<Question> questions = parseIndependentQuestions(root.get("questions"), outline);
            LearnUnit content = new LearnUnit(
                    outline.id(), outline.languageCode(), outline.code(), outline.chapterCode(), outline.name(),
                    outline.description(), outline.sequence(), outline.prerequisiteLearnUnitCodes(), outline.passScore(),
                    outline.minCodingScore(), outline.enabled(), outline.learningObjectives(), intro,
                    outline.keyConcepts(), examples, outline.diagnosticEligible(), ability, estimatedMinutes,
                    guidedPrompt, guidedHints, independentPrompt);
            LearnUnitContentValidator.validate(outline, content, questions);
            return new GeneratedLearnUnitContent(content, questions);
        } catch (Exception error) {
            throw new IllegalArgumentException("LearnUnit content generator returned invalid JSON", error);
        }
    }

    private List<Question> parseIndependentQuestions(JsonNode nodes, LearnUnit outline) {
        if (nodes == null || !nodes.isArray() || nodes.size() < 1 || nodes.size() > 5) {
            throw new IllegalArgumentException("independent questions must contain 1 to 5 items");
        }
        List<Question> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            QuestionType type;
            try {
                type = QuestionType.valueOf(requiredText(node, "type").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid independent question type", error);
            }
            String config = null;
            String rubric = null;
            if (type == QuestionType.MULTIPLE_CHOICE) {
                JsonNode options = node.get("options");
                JsonNode correct = node.get("correctOptionIds");
                JsonNode multiple = node.get("multiple");
                if (options == null || !options.isArray() || correct == null || !correct.isArray()
                        || multiple == null || !multiple.isBoolean()) {
                    throw new IllegalArgumentException("Multiple choice question needs options, correctOptionIds and multiple");
                }
                ObjectNode configNode = MAPPER.createObjectNode();
                configNode.set("options", options);
                configNode.set("correctOptionIds", correct);
                configNode.set("multiple", multiple);
                config = configNode.toString();
            } else {
                JsonNode rubricNode = node.get("rubric");
                if (rubricNode == null || rubricNode.isNull()) {
                    throw new IllegalArgumentException("Coding question rubric is required");
                }
                rubric = rubricNode.toString();
            }
            Question question = new Question(
                    "generated-independent-question-" + UUID.randomUUID(), outline.code(), type,
                    requiredBoundedInt(node, "difficulty", 1, 5), requiredText(node, "prompt"),
                    requiredBoundedInt(node, "points", 1, 1000), config, rubric,
                    nullableText(node, "language"), nullableText(node, "starterCode"),
                    node.has("referenceConcepts") ? node.get("referenceConcepts").toString() : "[]", false,
                    QuestionRole.INDEPENDENT);
            QuestionStructureValidator.validate(question, outline);
            result.add(question);
        }
        return result;
    }

    private GeneratedOutline parseOutline(JsonNode root) {
        JsonNode languageNodes = root == null ? null : root.get("languages");
        JsonNode chapterNodes = root == null ? null : root.get("chapters");
        JsonNode learnUnitNodes = root == null ? null : root.get("learnUnits");
        if (languageNodes == null || !languageNodes.isArray() || languageNodes.isEmpty()
                || chapterNodes == null || !chapterNodes.isArray() || chapterNodes.isEmpty()
                || learnUnitNodes == null || !learnUnitNodes.isArray() || learnUnitNodes.isEmpty()) {
            throw new IllegalArgumentException("languages, chapters and learnUnits must be non-empty arrays");
        }
        List<LearningLanguage> languages = new ArrayList<>();
        Set<String> languageCodes = new HashSet<>();
        for (JsonNode node : languageNodes) {
            String code = requiredText(node, "code");
            if (!languageCodes.add(code)) throw new IllegalArgumentException("Duplicate language code: " + code);
            languages.add(new LearningLanguage(
                    "generated-language-" + UUID.randomUUID(), code, requiredText(node, "name"),
                    requiredText(node, "description"), true));
        }
        List<Chapter> chapters = new ArrayList<>();
        Set<String> chapterCodes = new HashSet<>();
        for (JsonNode node : chapterNodes) {
            String code = requiredText(node, "code");
            if (!chapterCodes.add(code)) throw new IllegalArgumentException("Duplicate Chapter code: " + code);
            chapters.add(new Chapter(
                    "generated-chapter-" + UUID.randomUUID(), code, requiredText(node, "name"),
                    requiredText(node, "goal"), requiredBoundedInt(node, "sequence", 1, 1000),
                    strings(node.get("prerequisiteChapterCodes"))));
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
        for (JsonNode node : learnUnitNodes) {
            String code = requiredText(node, "code");
            String languageCode = requiredText(node, "languageCode");
            String chapterCode = requiredText(node, "chapterCode");
            if (!languageCodes.contains(languageCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown language: " + code);
            }
            if (!chapterCodes.contains(chapterCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown Chapter: " + code);
            }
            if (!learnUnitCodes.add(code)) throw new IllegalArgumentException("Duplicate LearnUnit code: " + code);
            List<String> objectives = strings(node.get("learningObjectives"));
            List<String> concepts = strings(node.get("keyConcepts"));
            if (objectives.isEmpty() || concepts.isEmpty()) {
                throw new IllegalArgumentException("Outline LearnUnit needs objectives and keyConcepts");
            }
            learnUnits.add(new LearnUnit(
                    "generated-learn-unit-" + UUID.randomUUID(), languageCode, code, chapterCode,
                    requiredText(node, "name"),
                    requiredText(node, "description"), requiredBoundedInt(node, "sequence", 1, 1000),
                    strings(node.get("prerequisiteLearnUnitCodes")), requiredBoundedInt(node, "passScore", 0, 100),
                    nullableBoundedInt(node, "minCodingScore", 0, 100), true, objectives, "", concepts, List.of(), true));
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

    private int requiredBoundedInt(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException("Missing or invalid " + field);
        int result = value.asInt(Integer.MIN_VALUE);
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private Integer nullableBoundedInt(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        int result = value.asInt(Integer.MIN_VALUE);
        if (result < min || result > max) throw new IllegalArgumentException("Invalid " + field);
        return result;
    }

    private String boundedText(JsonNode node, String field, int maxLength) {
        String value = requiredText(node, field);
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    private List<String> boundedStrings(JsonNode node, int min, int max, int itemMaxLength) {
        List<String> values = strings(node);
        if (values.size() < min || values.size() > max) {
            throw new IllegalArgumentException("Array field has an invalid size");
        }
        if (values.stream().anyMatch(value -> value.length() > itemMaxLength)) {
            throw new IllegalArgumentException("Array field contains oversized text");
        }
        return values;
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("Array fields must contain non-empty strings");
            }
            result.add(value.textValue().trim());
        }
        return result;
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText().trim();
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
