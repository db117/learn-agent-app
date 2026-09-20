package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;

/** 在进入当前 LearnUnit 时懒生成并持久化教学内容，避免规划阶段提前生成课程细节。 */
@ApplicationScoped
public class LearnUnitContentService {
    private final LearningJourneyRepository journeys;
    private final LearnUnitContentGenerator generator;

    @Inject
    public LearnUnitContentService(
            LearningJourneyRepository journeys,
            LearnUnitContentGenerator generator) {
        this.journeys = journeys;
        this.generator = generator;
    }

    // ponytail: 单用户全局锁；吞吐量需要提升时再按 journeyId 分锁。
    public synchronized LearningJourney ensureCurrentContent(LearningJourney journey) {
        Objects.requireNonNull(journey, "journey must not be null");
        var current = journey.currentItem();
        if (current == null) {
            return journey;
        }
        var currentUnit = journey.learnUnit(current.learnUnitCode());
        if (!currentUnit.content().isBlank()) {
            return journey;
        }

        var persistedId = Objects.requireNonNull(journey.id(), "persisted learning journey id must not be null");
        var latest = journeys.findById(persistedId)
                .orElseThrow(() -> LearningRequestException.notFound(
                        "LEARNING_JOURNEY_NOT_FOUND", "学习路径不存在"));
        var latestItem = latest.currentItem();
        if (latestItem == null) {
            return latest;
        }
        var latestUnit = latest.learnUnit(latestItem.learnUnitCode());
        if (!latestUnit.content().isBlank()) {
            return latest;
        }
        var content = generator.generate(latestUnit);
        return journeys.save(latest.materializeLearnUnitContent(latestUnit.code(), content));
    }
}
