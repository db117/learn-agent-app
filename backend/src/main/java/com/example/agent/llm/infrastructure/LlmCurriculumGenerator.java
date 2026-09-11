package com.example.agent.llm.infrastructure;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmCurriculumGenerator.class);
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
                  "learnUnits":[{
                    "languageCode":"...","code":"...","name":"...","description":"...",
                    "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":70,
                    "learningObjectives":["..."],"keyConcepts":["..."]
                  }]
                }
                用户指定的目标编程语言是：%s
                只生成这个目标语言，不要生成其他语言；languages 数组必须只有一个元素。
                生成足以覆盖学习目标的循序渐进 LearnUnit，数量按内容需要决定，不设上限，也不要为了凑数重复内容。
                学习者的目标和背景如下，请让知识点顺序和前置关系与其相关：%s
                至少有一个无前置 LearnUnit 的起点；前置 LearnUnit 只能引用同一语言中已经生成的 code，不能循环。
                code 使用稳定、简短、适合 URL 的英文标识；每个 LearnUnit 的 code 必须唯一。
                name、description、learningObjectives、keyConcepts 使用中文，但技术术语和语言名称可以保留英文。
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
    public LearnUnit generateContent(LearnUnit outline, String learningContext) {
        if (outline == null) throw new IllegalArgumentException("LearnUnit outline is required");
        if (outline.hasDetailedContent()) return outline;
        String prompt = """
                你是一个学习系统的教学内容作者。请为下面这个已经确认的 LearnUnit 生成教学正文，返回 JSON，不要返回 Markdown：
                {
                  "lessonIntro":"...",
                  "examples":["..."]
                }
                学习者背景：%s
                LearnUnit 大纲：code=%s, name=%s, description=%s, objectives=%s, concepts=%s
                内容必须具体、可学习；examples 至少一个；不要生成题目、答案或评分规则。
                """.formatted(
                learningContext == null ? "" : learningContext.trim(), outline.code(), outline.name(),
                outline.description(), outline.learningObjectives(), outline.keyConcepts());
        try {
            JsonNode root = MAPPER.readTree(extractJson(AgentScopeTextGenerator.generate(model, prompt)));
            String intro = requiredText(root, "lessonIntro");
            List<String> examples = strings(root.get("examples"));
            if (examples.isEmpty()) {
                throw new IllegalArgumentException("Detailed LearnUnit content must not be empty");
            }
            return outline.withDetailedContent(outline.learningObjectives(), intro, outline.keyConcepts(), examples);
        } catch (Exception error) {
            throw new IllegalArgumentException("LearnUnit content generator returned invalid JSON", error);
        }
    }

    /**
     * 请求模型生成课程 JSON，并将响应解析为领域对象。
     *
     * <p>模型响应只负责提供候选内容；解析阶段和领域校验会拒绝缺失、重复或不符合题型规则的数据，
     * 不会把无效响应静默转换为默认课程。</p>
     */
    @Override
    public GeneratedCurriculum generate(String requestedLanguage, String learningContext) {
        if (requestedLanguage == null || requestedLanguage.isBlank()) {
            throw new IllegalArgumentException("Requested language must not be blank");
        }
        String language = requestedLanguage.trim();
        long startedAt = System.nanoTime();
        LOGGER.info("curriculum.generate.start language={} model={}", language, model.getClass().getSimpleName());
        String prompt = """
                你是一个学习系统的课程架构师。请为编程学习者生成一份可执行的课程目录，
                返回 JSON，不要返回 Markdown 或解释文字：
                {
                  "languages":[{"code":"...","name":"...","description":"..."}],
                  "learnUnits":[{
                    "languageCode":"...","code":"...","name":"...","description":"...",
                    "sequence":1,"prerequisiteLearnUnitCodes":[],"passScore":80,"minCodingScore":70,
                    "learningObjectives":["..."],"lessonIntro":"...","keyConcepts":["..."],"examples":["..."]
                  }],
                  "questions":[{
                    "learnUnitCode":"...","type":"MULTIPLE_CHOICE","difficulty":2,"prompt":"...",
                    "points":20,"options":[{"id":"A","text":"..."},{"id":"B","text":"..."}],"correctOptionIds":["A"],
                    "multiple":false,"referenceConcepts":["..."],
                    "language":"...","starterCode":"...",
                    "rubric":{"correctness":60,"languageUsage":20,"clarity":20}
                  }]
                }
                用户指定的目标编程语言是：%s
                只生成这个目标语言，不要生成其他语言；languages 数组必须只有一个元素。
                生成足以覆盖学习目标的循序渐进 LearnUnit，数量按内容需要决定，不设上限，也不要为了凑数重复内容。
                学习者的目标和背景如下，请让 LearnUnit 顺序和教学内容与其相关：%s
                至少有一个无前置 LearnUnit 的起点；前置 LearnUnit 只能引用同一语言中已经生成的 code，不能循环。
                code 使用稳定、简短、适合 URL 的英文标识；每个 LearnUnit 的 code 必须唯一。
                name、description、learningObjectives、lessonIntro、keyConcepts、examples 使用中文，
                但技术术语和语言名称可以保留英文。每个 LearnUnit 都要有可讲授的内容。
                passScore 和 minCodingScore 为 0 到 100 的整数；没有编码学习目标时 minCodingScore 必须为 null。
                如果某个 LearnUnit 没有编码学习目标，不要为它生成 CODING 题；有编码学习目标时，
                为每个 LearnUnit 生成至少一道有效的 MULTIPLE_CHOICE 和一道有效的 CODING 题，
                没有编码学习目标时至少生成一道有效的 MULTIPLE_CHOICE 题。
                questions 中的选择题必须包含 options、correctOptionIds 和 multiple；Coding 题必须包含非空 language
                和对象形式的 rubric。题目只能引用已经生成的 LearnUnit code。
                """.formatted(language, learningContext == null ? "" : learningContext.trim());
        try {
            String response = AgentScopeTextGenerator.generate(model, prompt);
            LOGGER.info("curriculum.generate.response language={} chars={} elapsedMs={}",
                    language, response.length(), elapsedMillis(startedAt));
            String extractedJson = extractJson(response);
            LOGGER.info("[LLM-TRACE] curriculum.extract language={} chars={} value={}",
                    language, extractedJson.length(), extractedJson);
            JsonNode root = MAPPER.readTree(extractedJson);
            GeneratedCurriculum generated = parse(root);
            LOGGER.info("curriculum.generate.success language={} learnUnits={} questions={} elapsedMs={}",
                    language, generated.learnUnits().size(), generated.questions().size(), elapsedMillis(startedAt));
            return generated;
        } catch (Exception error) {
            LOGGER.error("curriculum.generate.failed language={} elapsedMs={}", language, elapsedMillis(startedAt), error);
            throw new IllegalArgumentException("Curriculum generator returned invalid JSON", error);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 将课程 JSON 转换为语言、LearnUnit 和题目领域对象，并校验引用关系。
     *
     * @param root 模型返回的 JSON 根节点
     * @return 可供领域服务继续校验和持久化的课程结果
     */
    private GeneratedCurriculum parse(JsonNode root) {
        JsonNode languageNodes = root == null ? null : root.get("languages");
        JsonNode learnUnitNodes = root == null ? null : root.get("learnUnits");
        if (languageNodes == null || !languageNodes.isArray() || languageNodes.isEmpty()
                || learnUnitNodes == null || !learnUnitNodes.isArray() || learnUnitNodes.isEmpty()) {
            throw new IllegalArgumentException("languages and learnUnits must be non-empty arrays");
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

        List<LearnUnit> learnUnits = new ArrayList<>();
        Set<String> learnUnitCodes = new HashSet<>();
        for (JsonNode node : learnUnitNodes) {
            String code = requiredText(node, "code");
            String languageCode = requiredText(node, "languageCode");
            if (!languageCodes.contains(languageCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown language: " + code);
            }
            if (!learnUnitCodes.add(code)) throw new IllegalArgumentException("Duplicate LearnUnit code: " + code);
            learnUnits.add(new LearnUnit(
                    "generated-learn-unit-" + UUID.randomUUID(), languageCode, code, requiredText(node, "name"),
                    requiredText(node, "description"), requiredBoundedInt(node, "sequence", 1, 1000),
                    strings(node.get("prerequisiteLearnUnitCodes")), requiredBoundedInt(node, "passScore", 0, 100),
                    nullableBoundedInt(node, "minCodingScore", 0, 100), true,
                    strings(node.get("learningObjectives")), optionalText(node, "lessonIntro"),
                    strings(node.get("keyConcepts")), strings(node.get("examples")), true));
        }
        for (LearnUnit learnUnit : learnUnits) {
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                if (!learnUnitCodes.contains(prerequisite)) {
                    throw new IllegalArgumentException("Unknown prerequisite LearnUnit: " + prerequisite);
                }
            }
        }
        return new GeneratedCurriculum(languages, learnUnits, parseQuestions(root, learnUnitCodes));
    }

    private GeneratedOutline parseOutline(JsonNode root) {
        JsonNode languageNodes = root == null ? null : root.get("languages");
        JsonNode learnUnitNodes = root == null ? null : root.get("learnUnits");
        if (languageNodes == null || !languageNodes.isArray() || languageNodes.isEmpty()
                || learnUnitNodes == null || !learnUnitNodes.isArray() || learnUnitNodes.isEmpty()) {
            throw new IllegalArgumentException("languages and learnUnits must be non-empty arrays");
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
        List<LearnUnit> learnUnits = new ArrayList<>();
        Set<String> learnUnitCodes = new HashSet<>();
        for (JsonNode node : learnUnitNodes) {
            String code = requiredText(node, "code");
            String languageCode = requiredText(node, "languageCode");
            if (!languageCodes.contains(languageCode)) {
                throw new IllegalArgumentException("LearnUnit belongs to unknown language: " + code);
            }
            if (!learnUnitCodes.add(code)) throw new IllegalArgumentException("Duplicate LearnUnit code: " + code);
            List<String> objectives = strings(node.get("learningObjectives"));
            List<String> concepts = strings(node.get("keyConcepts"));
            if (objectives.isEmpty() || concepts.isEmpty()) {
                throw new IllegalArgumentException("Outline LearnUnit needs objectives and keyConcepts");
            }
            learnUnits.add(new LearnUnit(
                    "generated-learn-unit-" + UUID.randomUUID(), languageCode, code, requiredText(node, "name"),
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
        return new GeneratedOutline(languages, learnUnits);
    }

    /**
     * 解析题目数组，构建选择题配置或 Coding 题评分规则，并确认题目引用已生成的 LearnUnit。
     *
     * @param root 模型返回的 JSON 根节点
     * @param learnUnitCodes 已生成的 LearnUnit 编码集合
     * @return 解析并校验后的题目
     */
    private List<Question> parseQuestions(JsonNode root, Set<String> learnUnitCodes) {
        JsonNode questionNodes = root.get("questions");
        if (questionNodes == null) throw new IllegalArgumentException("questions is required");
        if (!questionNodes.isArray() || questionNodes.isEmpty()) {
            throw new IllegalArgumentException("questions must be a non-empty array");
        }
        List<Question> questions = new ArrayList<>();
        for (JsonNode node : questionNodes) {
            String learnUnitCode = requiredText(node, "learnUnitCode");
            if (!learnUnitCodes.contains(learnUnitCode)) {
                throw new IllegalArgumentException("Question belongs to unknown LearnUnit: " + learnUnitCode);
            }
            QuestionType type = QuestionType.valueOf(requiredText(node, "type").toUpperCase(Locale.ROOT));
            JsonNode options = node.get("options");
            JsonNode correctOptionIds = node.get("correctOptionIds");
            JsonNode multiple = node.get("multiple");
            String config = null;
            if (type == QuestionType.MULTIPLE_CHOICE) {
                if (options == null || correctOptionIds == null || multiple == null || !multiple.isBoolean()) {
                    throw new IllegalArgumentException("Multiple-choice multiple must be boolean");
                }
                var configNode = MAPPER.createObjectNode();
                configNode.set("options", options);
                configNode.set("correctOptionIds", correctOptionIds);
                configNode.set("multiple", multiple);
                config = configNode.toString();
            }
            String rubric = type == QuestionType.CODING
                    ? node.has("rubric") && !node.get("rubric").isNull() ? node.get("rubric").toString() : null
                    : null;
            JsonNode referenceConcepts = node.get("referenceConcepts");
            Question question = new Question(
                    "generated-question-" + UUID.randomUUID(), learnUnitCode, type,
                    requiredBoundedInt(node, "difficulty", 1, 5), requiredText(node, "prompt"),
                    requiredBoundedInt(node, "points", 1, 1000),
                    config, rubric, optionalText(node, "language"), optionalText(node, "starterCode"),
                    referenceConcepts == null || referenceConcepts.isNull() ? "[]" : referenceConcepts.toString(),
                    node.path("diagnosticEligible").asBoolean(true));
            QuestionStructureValidator.validate(question);
            questions.add(question);
        }
        return questions;
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
