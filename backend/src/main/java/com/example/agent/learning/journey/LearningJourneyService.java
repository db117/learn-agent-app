package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learning Journey 生命周期服务。
 *
 * <p>创建 Journey 时根据用户目标生成并保存专属课程和学习者画像；题目、评估和进度由各自领域服务负责。
 */
@Service
public class LearningJourneyService {

    private final LearningRepository repository;
    private final CurriculumService curriculum;
    private final ProgressService progress;

    public LearningJourneyService(
            LearningRepository repository, CurriculumService curriculum, ProgressService progress) {
        this.repository = repository;
        this.curriculum = curriculum;
        this.progress = progress;
    }

    /**
     * 创建 Journey 及其专属学习资料。
     *
     * <p>先校验用户输入并生成大纲，再按外键依赖依次保存语言、Journey、学习者画像和课程大纲，
     * 最后生成初始学习路径。</p>
     */
    @Transactional
    public LearningJourney create(
            String userId,
            String languageCode,
            String goal,
            String primaryLanguage,
            Integer experienceYears,
            String selfDescription,
            String learningGoal) {
        requireText(languageCode, "languageCode");
        requireText(goal, "goal");
        requireText(primaryLanguage, "primaryLanguage");
        requireText(learningGoal, "learningGoal");
        if (experienceYears != null && (experienceYears < 0 || experienceYears > 100)) {
            throw new IllegalArgumentException("experienceYears must be between 0 and 100");
        }
        String id = UUID.randomUUID().toString();
        String learningContext = """
                Journey 目标：%s
                具体学习目标：%s
                学习者主要语言：%s
                相关经验年数：%s
                当前水平补充：%s
                """.formatted(goal.trim(), learningGoal.trim(), primaryLanguage.trim(),
                experienceYears == null ? "未知" : experienceYears, selfDescription == null ? "" : selfDescription.trim());
        CurriculumGenerator.GeneratedOutline generated = curriculum.generateOutlineForJourney(
                id, languageCode, learningContext);
        LearningLanguage language = generated.languages().get(0);
        // 语言行是外键依赖，先写入元数据；LearnUnit 在 Journey 建立后再绑定。
        curriculum.persistLanguages(generated);
        Instant now = Instant.now();
        LearningJourney journey = new LearningJourney(
                id, userId, language.code(), goal.trim(), JourneyStatus.ACTIVE, now, now);
        repository.insertJourney(journey);
        repository.saveProfile(new LearnerProfile(
                id, primaryLanguage.trim(), experienceYears,
                selfDescription == null ? "" : selfDescription.trim(), learningGoal.trim()));
        curriculum.persistJourneyOutline(id, generated);
        progress.generatePath(id);
        return get(id);
    }

    /** 用户确认大纲后才把 Journey、画像、LearnUnit 大纲和 Path 一次性写入 SQLite。 */
    @Transactional
    public LearningJourney confirmOutline(
            String userId, String journeyId, JourneyDraftInput input,
            CurriculumGenerator.GeneratedOutline generated) {
        validateInput(input);
        if (generated == null || generated.languages().size() != 1) {
            throw new IllegalArgumentException("a single generated language is required");
        }
        LearningLanguage language = generated.languages().get(0);
        Instant now = Instant.now();
        curriculum.persistLanguages(generated);
        repository.insertJourney(new LearningJourney(
                journeyId, userId, language.code(), input.goal().trim(), JourneyStatus.ACTIVE, now, now));
        repository.saveProfile(new LearnerProfile(
                journeyId, input.primaryLanguage().trim(), input.experienceYears(),
                input.selfDescription() == null ? "" : input.selfDescription().trim(), input.learningGoal().trim()));
        curriculum.persistJourneyOutline(journeyId, generated);
        progress.generatePath(journeyId);
        return get(journeyId);
    }

    /** 给大纲和按需内容生成器使用的稳定用户上下文。 */
    public String learningContext(JourneyDraftInput input) {
        validateInput(input);
        return """
                Journey 目标：%s
                具体学习目标：%s
                学习者主要语言：%s
                相关经验年数：%s
                当前水平补充：%s
                """.formatted(input.goal().trim(), input.learningGoal().trim(), input.primaryLanguage().trim(),
                input.experienceYears() == null ? "未知" : input.experienceYears(),
                input.selfDescription() == null ? "" : input.selfDescription().trim());
    }

    public LearningJourney get(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
    }

    public List<LearningJourney> list(String userId) {
        return repository.listJourneys(userId);
    }

    public LearningJourney archive(String id) {
        LearningJourney journey = get(id);
        Instant now = Instant.now();
        repository.updateJourney(id, JourneyStatus.ARCHIVED, now);
        return get(id);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private void validateInput(JourneyDraftInput input) {
        if (input == null) throw new IllegalArgumentException("draft input is required");
        requireText(input.languageCode(), "languageCode");
        requireText(input.goal(), "goal");
        requireText(input.primaryLanguage(), "primaryLanguage");
        requireText(input.learningGoal(), "learningGoal");
        if (input.experienceYears() != null && (input.experienceYears() < 0 || input.experienceYears() > 100)) {
            throw new IllegalArgumentException("experienceYears must be between 0 and 100");
        }
    }
}
