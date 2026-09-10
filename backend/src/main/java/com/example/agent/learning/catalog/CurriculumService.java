package com.example.agent.learning.catalog;

import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.persistence.LearningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课程目录生命周期服务。
 *
 * <p>应用启动不加载固定课程。每次创建新 Journey 时调用 {@link CurriculumGenerator}
 * 生成该 Journey 专属的 LearnUnit，生成结果通过 Repository 写入 SQLite；同一
 * Journey 后续只读取已保存的关联。进度服务仍使用 Java 规则计算路径和状态。</p>
 */
@Service
public class CurriculumService {

    private final LearningRepository repository;
    private final CurriculumGenerator generator;
    public CurriculumService(LearningRepository repository, CurriculumGenerator generator) {
        this.repository = repository;
        this.generator = generator;
    }

    /** 查询已经持久化的语言目录；不会因为读取接口自动调用 LLM。 */
    public List<LearningLanguage> listLanguages() {
        return repository.listLanguages();
    }

    /**
     * 为一个新 Journey 生成独立课程；即使数据库已有同语言课程，也不会复用。
     *
     * <p>生成完成后先执行结构、内容、前置关系和题型覆盖校验，再把业务标识限定到当前 Journey。</p>
     */
    public CurriculumGenerator.GeneratedCurriculum generateForJourney(
            String journeyId, String requestedLanguage, String learningContext) {
        if (journeyId == null || journeyId.isBlank()) throw new IllegalArgumentException("journeyId must not be blank");
        if (requestedLanguage == null || requestedLanguage.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        CurriculumGenerator.GeneratedCurriculum generated;
        String requested = requestedLanguage.trim();
        try {
            generated = generator.generate(requested, learningContext == null ? "" : learningContext.trim());
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to generate learning curriculum", error);
        }
        validate(generated);
        if (generated.languages().size() != 1) {
            throw new IllegalStateException("Generated curriculum must contain exactly one requested language");
        }
        LearningLanguage language = generated.languages().get(0);
        if (!sameLanguage(requested, language)) {
            throw new IllegalStateException("Generated curriculum does not match requested language: " + requested);
        }
        return scopeToJourney(journeyId, generated);
    }

    /** 先写入语言元数据，满足 Journey 的语言外键约束。 */
    public void persistLanguages(CurriculumGenerator.GeneratedCurriculum generated) {
        repository.insertGeneratedCatalog(generated.languages(), List.of());
    }

    /** 写入 Journey 专属 LearnUnit 并建立关联。 */
    @Transactional
    public void persistJourneyCurriculum(
            String journeyId, CurriculumGenerator.GeneratedCurriculum generated) {
        repository.insertGeneratedCatalogForJourney(journeyId, generated.languages(), generated.learnUnits());
        generated.questions().forEach(repository::insertGeneratedQuestion);
    }

    /**
     * 将生成结果的 LearnUnit 和 Question 标识限定到当前 Journey，并同步重写前置关系。
     *
     * @param journeyId Journey 标识
     * @param generated 未限定范围的生成结果
     * @return 标识已限定到 Journey 的课程结果
     */
    private CurriculumGenerator.GeneratedCurriculum scopeToJourney(
            String journeyId, CurriculumGenerator.GeneratedCurriculum generated) {
        Map<String, String> scopedCodes = generated.learnUnits().stream()
                .collect(Collectors.toMap(LearnUnit::code, learnUnit -> journeyId + "." + learnUnit.code()));
        List<LearnUnit> learnUnits = generated.learnUnits().stream()
                .map(learnUnit -> new LearnUnit(
                        journeyId + "." + learnUnit.id(), learnUnit.languageCode(), scopedCodes.get(learnUnit.code()),
                        learnUnit.name(), learnUnit.description(), learnUnit.sequence(),
                        learnUnit.prerequisiteLearnUnitCodes().stream().map(scopedCodes::get).toList(),
                        learnUnit.passScore(), learnUnit.minCodingScore(), learnUnit.enabled(), learnUnit.learningObjectives(),
                        learnUnit.lessonIntro(), learnUnit.keyConcepts(), learnUnit.examples(), learnUnit.diagnosticEligible()))
                .toList();
        List<Question> questions = generated.questions().stream()
                .map(question -> new Question(
                        journeyId + "." + question.id(), scopedCodes.get(question.learnUnitCode()), question.type(),
                        question.difficulty(), question.prompt(), question.points(), question.configJson(),
                        question.rubricJson(), question.language(), question.starterCode(),
                        question.referenceConceptsJson(), question.diagnosticEligible()))
                .toList();
        return new CurriculumGenerator.GeneratedCurriculum(generated.languages(), learnUnits, questions);
    }

    private boolean sameLanguage(String requested, LearningLanguage generated) {
        return generated.code().trim().equalsIgnoreCase(requested.trim())
                || generated.name().trim().equalsIgnoreCase(requested.trim());
    }

    /**
     * 校验生成课程的完整结构，确保后续写入 SQLite 的数据满足领域约束。
     *
     * <p>校验覆盖语言、LearnUnit 内容、分数规则、重复内容、前置关系、无环路径和题目覆盖。</p>
     */
    private void validate(CurriculumGenerator.GeneratedCurriculum generated) {
        if (generated == null || generated.languages().isEmpty() || generated.learnUnits().isEmpty()) {
            throw new IllegalStateException("Generated curriculum must contain languages and LearnUnits");
        }
        for (LearningLanguage language : generated.languages()) {
            if (language.code() == null || language.name() == null || language.description() == null
                    || language.code().isBlank() || language.name().isBlank() || language.description().isBlank()) {
                throw new IllegalStateException("Generated language has missing required fields: " + language.code());
            }
        }
        Map<String, LearningLanguage> languages = generated.languages().stream()
                .collect(Collectors.toMap(LearningLanguage::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated language: " + left.code());
                }));
        Map<String, LearnUnit> learnUnits = generated.learnUnits().stream()
                .collect(Collectors.toMap(LearnUnit::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated LearnUnit: " + left.code());
                }));
        Set<String> learnUnitLanguages = new HashSet<>();
        Set<String> learnUnitContent = new HashSet<>();
        for (LearnUnit learnUnit : generated.learnUnits()) {
            if (learnUnit.code() == null || learnUnit.languageCode() == null || learnUnit.name() == null || learnUnit.description() == null
                    || !languages.containsKey(learnUnit.languageCode())) {
                throw new IllegalStateException("LearnUnit belongs to unknown language: " + learnUnit.code());
            }
            if (learnUnit.code().isBlank() || learnUnit.name().isBlank() || learnUnit.description().isBlank()) {
                throw new IllegalStateException("Generated LearnUnit has missing required fields: " + learnUnit.code());
            }
            if (learnUnit.sequence() < 1 || learnUnit.learningObjectives().isEmpty()
                    || learnUnit.lessonIntro() == null || learnUnit.lessonIntro().isBlank()
                    || learnUnit.keyConcepts().isEmpty() || learnUnit.examples().isEmpty()
                    || hasBlank(learnUnit.learningObjectives()) || hasBlank(learnUnit.keyConcepts())
                    || hasBlank(learnUnit.examples())) {
                throw new IllegalStateException("Generated LearnUnit has incomplete teaching content: " + learnUnit.code());
            }
            if (learnUnit.passScore() < 0 || learnUnit.passScore() > 100
                    || learnUnit.minCodingScore() != null && (learnUnit.minCodingScore() < 0 || learnUnit.minCodingScore() > 100)) {
                throw new IllegalStateException("Generated LearnUnit has invalid score rules: " + learnUnit.code());
            }
            String content = String.join("\u001f", learnUnit.name().trim(), learnUnit.description().trim(),
                    learnUnit.lessonIntro().trim(), learnUnit.learningObjectives().toString(),
                    learnUnit.keyConcepts().toString(), learnUnit.examples().toString());
            if (!learnUnitContent.add(content)) {
                throw new IllegalStateException("Generated LearnUnits contain duplicate teaching content");
            }
            learnUnitLanguages.add(learnUnit.languageCode());
            Set<String> prerequisites = new HashSet<>();
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                LearnUnit prerequisiteLearnUnit = learnUnits.get(prerequisite);
                if (!prerequisites.add(prerequisite) || prerequisite == null || prerequisite.isBlank()
                        || prerequisite.equals(learnUnit.code()) || prerequisiteLearnUnit == null
                        || !prerequisiteLearnUnit.languageCode().equals(learnUnit.languageCode())) {
                    throw new IllegalStateException("Generated LearnUnit has invalid prerequisite: " + learnUnit.code());
                }
            }
        }
        if (learnUnitLanguages.size() != languages.size()) {
            throw new IllegalStateException("Every generated language needs at least one LearnUnit");
        }
        validateAcyclic(learnUnits);
        validateQuestions(generated.questions(), learnUnits);
    }

    private boolean hasBlank(List<String> values) {
        return values.stream().anyMatch(value -> value == null || value.isBlank());
    }

    private void validateQuestions(List<Question> questions, Map<String, LearnUnit> learnUnits) {
        if (questions.isEmpty()) throw new IllegalStateException("Generated Question set must not be empty");
        Set<String> ids = new HashSet<>();
        for (Question question : questions) {
            if (!ids.add(question.id())) throw new IllegalStateException("Duplicate generated Question: " + question.id());
            LearnUnit learnUnit = learnUnits.get(question.learnUnitCode());
            try {
                QuestionStructureValidator.validate(question, learnUnit);
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("Generated Question is invalid: " + question.id(), error);
            }
        }
        for (LearnUnit learnUnit : learnUnits.values()) {
            boolean hasChoice = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.type() == QuestionType.MULTIPLE_CHOICE);
            boolean hasCoding = questions.stream().anyMatch(question ->
                    question.learnUnitCode().equals(learnUnit.code())
                            && question.type() == QuestionType.CODING);
            if (!hasChoice || learnUnit.minCodingScore() != null && !hasCoding) {
                throw new IllegalStateException("Generated Question coverage is incomplete for " + learnUnit.code());
            }
        }
    }

    /** 使用深度优先遍历检查 LearnUnit 前置关系是否存在环路。 */
    private void validateAcyclic(Map<String, LearnUnit> learnUnits) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String code : learnUnits.keySet()) visit(code, learnUnits, visiting, visited);
    }

    private void visit(
            String code,
            Map<String, LearnUnit> learnUnits,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(code)) return;
        if (!visiting.add(code)) throw new IllegalStateException("Generated learnUnits contain a prerequisite cycle");
        for (String prerequisite : learnUnits.get(code).prerequisiteLearnUnitCodes()) {
            visit(prerequisite, learnUnits, visiting, visited);
        }
        visiting.remove(code);
        visited.add(code);
    }
}
