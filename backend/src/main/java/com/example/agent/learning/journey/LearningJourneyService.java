package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.persistence.LearningRepository;
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

    public LearningJourneyService(LearningRepository repository, CurriculumService curriculum) {
        this.repository = repository;
        this.curriculum = curriculum;
    }

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
        CurriculumGenerator.GeneratedCurriculum generated = curriculum.generateForJourney(
                id, languageCode, learningContext);
        LearningLanguage language = generated.languages().get(0);
        // 语言行是外键依赖，先写入元数据；技能在 Journey 建立后再绑定。
        curriculum.persistLanguages(generated);
        Instant now = Instant.now();
        LearningJourney journey = new LearningJourney(
                id, userId, language.code(), goal.trim(), JourneyStatus.ACTIVE, now, now, null);
        repository.insertJourney(journey);
        repository.saveProfile(new LearnerProfile(
                id, primaryLanguage.trim(), experienceYears,
                selfDescription == null ? "" : selfDescription.trim(), learningGoal.trim()));
        curriculum.persistJourneyCurriculum(id, generated);
        return journey;
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
        repository.updateJourney(id, JourneyStatus.ARCHIVED, journey.currentLearningSkillId(), now);
        return get(id);
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
