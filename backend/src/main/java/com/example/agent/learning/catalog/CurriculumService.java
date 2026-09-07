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
 * 生成该 Journey 专属的技能和 Lesson，生成结果通过 Repository 写入 SQLite；同一
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

    /** 写入 Journey 专属技能并建立关联。 */
    public void persistJourneyCurriculum(
            String journeyId, CurriculumGenerator.GeneratedCurriculum generated) {
        repository.insertGeneratedCatalogForJourney(journeyId, generated.languages(), generated.skills());
    }

    private CurriculumGenerator.GeneratedCurriculum scopeToJourney(
            String journeyId, CurriculumGenerator.GeneratedCurriculum generated) {
        Map<String, String> scopedCodes = generated.skills().stream()
                .collect(Collectors.toMap(LearningSkill::code, skill -> journeyId + "." + skill.code()));
        List<LearningSkill> skills = generated.skills().stream()
                .map(skill -> new LearningSkill(
                        skill.id(), skill.languageCode(), scopedCodes.get(skill.code()), skill.name(), skill.description(),
                        skill.sequence(), skill.prerequisiteSkillCodes().stream().map(scopedCodes::get).toList(),
                        skill.passScore(), skill.minCodingScore(), skill.enabled(), skill.learningObjectives(),
                        skill.lessonIntro(), skill.keyConcepts(), skill.examples(), skill.diagnosticEligible()))
                .toList();
        return new CurriculumGenerator.GeneratedCurriculum(generated.languages(), skills);
    }

    private boolean sameLanguage(String requested, LearningLanguage generated) {
        return generated.code().equalsIgnoreCase(requested) || generated.name().equalsIgnoreCase(requested);
    }

    private void validate(CurriculumGenerator.GeneratedCurriculum generated) {
        if (generated == null || generated.languages().isEmpty() || generated.skills().isEmpty()) {
            throw new IllegalStateException("Generated curriculum must contain languages and skills");
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
        Map<String, LearningSkill> skills = generated.skills().stream()
                .collect(Collectors.toMap(LearningSkill::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated skill: " + left.code());
                }));
        Set<String> skillsPerLanguage = new HashSet<>();
        for (LearningSkill skill : generated.skills()) {
            if (skill.code() == null || skill.languageCode() == null || skill.name() == null || skill.description() == null
                    || !languages.containsKey(skill.languageCode())) {
                throw new IllegalStateException("Skill belongs to unknown language: " + skill.code());
            }
            if (skill.code().isBlank() || skill.name().isBlank() || skill.description().isBlank()) {
                throw new IllegalStateException("Generated skill has missing required fields: " + skill.code());
            }
            if (skill.passScore() < 0 || skill.passScore() > 100
                    || skill.minCodingScore() != null && (skill.minCodingScore() < 0 || skill.minCodingScore() > 100)) {
                throw new IllegalStateException("Generated skill has invalid score rules: " + skill.code());
            }
            skillsPerLanguage.add(skill.languageCode());
            for (String prerequisite : skill.prerequisiteSkillCodes()) {
                LearningSkill prerequisiteSkill = skills.get(prerequisite);
                if (prerequisite.equals(skill.code()) || prerequisiteSkill == null
                        || !prerequisiteSkill.languageCode().equals(skill.languageCode())) {
                    throw new IllegalStateException("Generated skill has invalid prerequisite: " + skill.code());
                }
            }
        }
        if (skillsPerLanguage.size() != languages.size()) {
            throw new IllegalStateException("Every generated language needs at least one skill");
        }
        validateAcyclic(skills);
    }

    private void validateAcyclic(Map<String, LearningSkill> skills) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String code : skills.keySet()) visit(code, skills, visiting, visited);
    }

    private void visit(
            String code,
            Map<String, LearningSkill> skills,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(code)) return;
        if (!visiting.add(code)) throw new IllegalStateException("Generated skills contain a prerequisite cycle");
        for (String prerequisite : skills.get(code).prerequisiteSkillCodes()) {
            visit(prerequisite, skills, visiting, visited);
        }
        visiting.remove(code);
        visited.add(code);
    }
}
