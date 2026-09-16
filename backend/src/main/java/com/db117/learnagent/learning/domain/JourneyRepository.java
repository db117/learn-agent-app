package com.db117.learnagent.learning.domain;

import java.util.List;
import java.util.Optional;

/** Journey 聚合的持久化端口；当前选择和 LearningJourney 关联都属于 Domain State。 */
public interface JourneyRepository {
    Journey save(Journey journey);

    Optional<Journey> findById(long id);

    List<Journey> findByLearnerId(long learnerId);

    Optional<Journey> findCurrentByLearnerId(long learnerId);

    Journey selectCurrent(long journeyId, long learnerId);

    Journey attachLearningJourney(long journeyId, long learningJourneyId, long learnerId);
}
