package com.example.agent.learning.catalog;

import com.example.agent.learning.persistence.LearningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(CurriculumService.class);

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

    /** 为首次确认流程生成只包含知识点和路径信息的草稿。 */
    public CurriculumGenerator.GeneratedOutline generateOutlineForJourney(
            String journeyId, String requestedLanguage, String learningContext) {
        return generateOutlineForJourney(journeyId, requestedLanguage, learningContext, ignored -> {
        });
    }

    /** 生成大纲并把模型文本增量转发给 Journey draft 的事件流。 */
    public CurriculumGenerator.GeneratedOutline generateOutlineForJourney(
            String journeyId, String requestedLanguage, String learningContext, Consumer<String> onText) {
        requireJourneyId(journeyId);
        requireLanguage(requestedLanguage);
        String requested = requestedLanguage.trim();
        CurriculumGenerator.GeneratedOutline generated;
        try {
            generated = generator.generateOutline(
                    requested, learningContext == null ? "" : learningContext.trim(), onText);
        } catch (RuntimeException error) {
            LOGGER.error("curriculum.outline.failed journeyId={} language={}", journeyId, requested, error);
            throw new IllegalStateException("Unable to generate learning outline", error);
        }
        validateOutline(generated);
        if (generated.languages().size() != 1) {
            throw new IllegalStateException("Generated outline must contain exactly one requested language");
        }
        LearningLanguage language = generated.languages().get(0);
        if (!sameLanguage(requested, language)) {
            throw new IllegalStateException("Generated outline does not match requested language: " + requested);
        }
        return scopeToJourney(journeyId, generated);
    }

    /** 先写入语言元数据，满足 Journey 的语言外键约束。 */
    public void persistLanguages(CurriculumGenerator.GeneratedOutline generated) {
        repository.insertGeneratedLanguages(generated.languages());
    }

    /** 只保存用户确认的大纲，不写入教学正文或题目。 */
    @Transactional
    public void persistJourneyOutline(String journeyId, CurriculumGenerator.GeneratedOutline generated) {
        repository.insertGeneratedCatalogForJourney(
                journeyId, generated.languages(), generated.chapters(), generated.learnUnits());
    }

    /** 第一次进入具体 LearnUnit 时才生成并保存教学正文。 */
    @Transactional
    public LearnUnit ensureLearnUnitContent(String journeyId, String learnUnitCode) {
        LearnUnit outline = repository.listLearnUnitsForJourney(journeyId).stream()
                .filter(unit -> unit.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit not found: " + learnUnitCode));
        if (outline.hasDetailedContent()) return outline;
        String context = repository.findJourney(journeyId)
                .map(journey -> "Journey 目标：" + journey.goal())
                .orElse("") + repository.findProfile(journeyId)
                .map(profile -> "\n学习者背景：" + profile)
                .orElse("");
        CurriculumGenerator.GeneratedLearnUnitContent generated = generator.generateContent(outline, context);
        LearnUnitContentValidator.validate(outline, generated.learnUnit(), generated.independentQuestions());
        repository.persistLearnUnitContent(generated.learnUnit(), generated.independentQuestions());
        return repository.findLearnUnit(outline.code()).orElse(generated.learnUnit());
    }

    private CurriculumGenerator.GeneratedOutline scopeToJourney(
            String journeyId, CurriculumGenerator.GeneratedOutline generated) {
        Map<String, String> scopedChapterCodes = generated.chapters().stream()
                .collect(Collectors.toMap(Chapter::code, chapter -> journeyId + "." + chapter.code()));
        List<Chapter> chapters = generated.chapters().stream()
                .map(chapter -> new Chapter(
                        journeyId + "." + chapter.id(), scopedChapterCodes.get(chapter.code()), chapter.name(),
                        chapter.goal(), chapter.sequence(), chapter.prerequisiteChapterCodes().stream()
                                .map(scopedChapterCodes::get).toList()))
                .toList();
        Map<String, String> scopedCodes = generated.learnUnits().stream()
                .collect(Collectors.toMap(LearnUnit::code, learnUnit -> journeyId + "." + learnUnit.code()));
        List<LearnUnit> learnUnits = generated.learnUnits().stream()
                .map(learnUnit -> new LearnUnit(
                        journeyId + "." + learnUnit.id(), learnUnit.languageCode(), scopedCodes.get(learnUnit.code()),
                        scopedChapterCodes.get(learnUnit.chapterCode()),
                        learnUnit.name(), learnUnit.description(), learnUnit.sequence(),
                        learnUnit.prerequisiteLearnUnitCodes().stream().map(scopedCodes::get).toList(),
                        learnUnit.passScore(), learnUnit.minCodingScore(), learnUnit.enabled(),
                        learnUnit.learningObjectives(), learnUnit.lessonIntro(), learnUnit.keyConcepts(),
                        learnUnit.examples(), learnUnit.diagnosticEligible()))
                .toList();
        return new CurriculumGenerator.GeneratedOutline(generated.languages(), chapters, learnUnits);
    }

    private void validateOutline(CurriculumGenerator.GeneratedOutline generated) {
        if (generated == null || generated.languages().isEmpty()
                || generated.chapters().isEmpty() || generated.learnUnits().isEmpty()) {
            throw new IllegalStateException("Generated outline must contain languages, Chapters and LearnUnits");
        }
        Map<String, LearningLanguage> languages = generated.languages().stream()
                .collect(Collectors.toMap(LearningLanguage::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated language: " + left.code());
                }));
        Map<String, LearnUnit> learnUnits = generated.learnUnits().stream()
                .collect(Collectors.toMap(LearnUnit::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated LearnUnit: " + left.code());
                }));
        for (LearningLanguage language : languages.values()) {
            if (language.code() == null || language.name() == null || language.description() == null
                    || language.code().isBlank() || language.name().isBlank() || language.description().isBlank()) {
                throw new IllegalStateException("Generated outline language is incomplete");
            }
        }
        Map<String, Chapter> chapters = generated.chapters().stream()
                .collect(Collectors.toMap(Chapter::code, Function.identity(), (left, right) -> {
                    throw new IllegalStateException("Duplicate generated Chapter: " + left.code());
                }));
        for (Chapter chapter : chapters.values()) {
            if (chapter.code() == null || chapter.name() == null || chapter.goal() == null
                    || chapter.code().isBlank() || chapter.name().isBlank() || chapter.goal().isBlank()
                    || chapter.sequence() < 1) {
                throw new IllegalStateException("Generated Chapter is incomplete: " + chapter.code());
            }
            Set<String> prerequisites = new HashSet<>();
            for (String prerequisite : chapter.prerequisiteChapterCodes()) {
                if (!prerequisites.add(prerequisite) || prerequisite == null || prerequisite.isBlank()
                        || prerequisite.equals(chapter.code()) || !chapters.containsKey(prerequisite)) {
                    throw new IllegalStateException("Generated Chapter has invalid prerequisite: " + chapter.code());
                }
            }
        }
        validateChapterAcyclic(chapters);
        for (LearnUnit learnUnit : learnUnits.values()) {
            if (learnUnit.code() == null || learnUnit.name() == null || learnUnit.description() == null
                    || learnUnit.chapterCode() == null || learnUnit.code().isBlank()
                    || learnUnit.name().isBlank() || learnUnit.description().isBlank()
                    || !languages.containsKey(learnUnit.languageCode()) || learnUnit.chapterCode().isBlank()
                    || !chapters.containsKey(learnUnit.chapterCode()) || learnUnit.sequence() < 1
                    || learnUnit.learningObjectives().isEmpty() || learnUnit.keyConcepts().isEmpty()
                    || learnUnit.hasDetailedContent()) {
                throw new IllegalStateException("Generated outline LearnUnit is incomplete: " + learnUnit.code());
            }
            if (learnUnit.passScore() < 0 || learnUnit.passScore() > 100
                    || learnUnit.minCodingScore() != null
                    && (learnUnit.minCodingScore() < 0 || learnUnit.minCodingScore() > 100)) {
                throw new IllegalStateException("Generated outline LearnUnit has invalid score rules: " + learnUnit.code());
            }
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                if (!learnUnits.containsKey(prerequisite)) {
                    throw new IllegalStateException("Generated outline has unknown prerequisite: " + prerequisite);
                }
            }
        }
        if (chapters.values().stream().anyMatch(chapter -> learnUnits.values().stream()
                .noneMatch(learnUnit -> learnUnit.chapterCode().equals(chapter.code())))) {
            throw new IllegalStateException("Every generated Chapter needs a LearnUnit");
        }
        validateAcyclic(learnUnits);
    }

    private void validateDetailedContent(LearnUnit outline, LearnUnit generated) {
        if (generated == null || !generated.hasDetailedContent()
                || !generated.id().equals(outline.id()) || !generated.code().equals(outline.code())) {
            throw new IllegalStateException("Generated LearnUnit content does not match the outline");
        }
    }

    private void requireJourneyId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("journeyId must not be blank");
    }

    private void requireLanguage(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("language must not be blank");
    }

    private boolean sameLanguage(String requested, LearningLanguage generated) {
        return generated.code().trim().equalsIgnoreCase(requested.trim())
                || generated.name().trim().equalsIgnoreCase(requested.trim());
    }

    /** 使用深度优先遍历检查 Chapter 前置关系是否存在环路。 */
    private void validateChapterAcyclic(Map<String, Chapter> chapters) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String code : chapters.keySet()) visitChapter(code, chapters, visiting, visited);
    }

    private void visitChapter(
            String code,
            Map<String, Chapter> chapters,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(code)) return;
        if (!visiting.add(code)) throw new IllegalStateException("Generated Chapters contain a prerequisite cycle");
        for (String prerequisite : chapters.get(code).prerequisiteChapterCodes()) {
            visitChapter(prerequisite, chapters, visiting, visited);
        }
        visiting.remove(code);
        visited.add(code);
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
