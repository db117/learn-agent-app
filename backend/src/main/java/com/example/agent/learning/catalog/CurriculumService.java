package com.example.agent.learning.catalog;

import com.example.agent.learning.persistence.LearningRepository;
import org.springframework.stereotype.Service;

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

    /** 为一个新 Journey 生成独立课程；即使数据库已有同语言课程，也不会复用。 */
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
    public void persistJourneyCurriculum(
            String journeyId, CurriculumGenerator.GeneratedCurriculum generated) {
        repository.insertGeneratedCatalogForJourney(journeyId, generated.languages(), generated.learnUnits());
    }

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
        return new CurriculumGenerator.GeneratedCurriculum(generated.languages(), learnUnits);
    }

    private boolean sameLanguage(String requested, LearningLanguage generated) {
        return generated.code().equalsIgnoreCase(requested) || generated.name().equalsIgnoreCase(requested);
    }

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
        for (LearnUnit learnUnit : generated.learnUnits()) {
            if (learnUnit.code() == null || learnUnit.languageCode() == null || learnUnit.name() == null || learnUnit.description() == null
                    || !languages.containsKey(learnUnit.languageCode())) {
                throw new IllegalStateException("LearnUnit belongs to unknown language: " + learnUnit.code());
            }
            if (learnUnit.code().isBlank() || learnUnit.name().isBlank() || learnUnit.description().isBlank()) {
                throw new IllegalStateException("Generated LearnUnit has missing required fields: " + learnUnit.code());
            }
            if (learnUnit.learningObjectives().isEmpty() || learnUnit.lessonIntro() == null || learnUnit.lessonIntro().isBlank()
                    || learnUnit.keyConcepts().isEmpty() || learnUnit.examples().isEmpty()) {
                throw new IllegalStateException("Generated LearnUnit has incomplete teaching content: " + learnUnit.code());
            }
            if (learnUnit.passScore() < 0 || learnUnit.passScore() > 100
                    || learnUnit.minCodingScore() != null && (learnUnit.minCodingScore() < 0 || learnUnit.minCodingScore() > 100)) {
                throw new IllegalStateException("Generated LearnUnit has invalid score rules: " + learnUnit.code());
            }
            learnUnitLanguages.add(learnUnit.languageCode());
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                LearnUnit prerequisiteLearnUnit = learnUnits.get(prerequisite);
                if (prerequisite.equals(learnUnit.code()) || prerequisiteLearnUnit == null
                        || !prerequisiteLearnUnit.languageCode().equals(learnUnit.languageCode())) {
                    throw new IllegalStateException("Generated LearnUnit has invalid prerequisite: " + learnUnit.code());
                }
            }
        }
        if (learnUnitLanguages.size() != languages.size()) {
            throw new IllegalStateException("Every generated language needs at least one LearnUnit");
        }
        validateAcyclic(learnUnits);
    }

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
