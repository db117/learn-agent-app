package com.db117.learnagent.learning.domain;

import java.util.Optional;

/** LearningJourney 聚合端口；状态切换和历史必须以聚合为单位保存。 */
public interface LearningJourneyRepository {
    LearningJourney save(LearningJourney journey);

    Optional<LearningJourney> findById(long id);

    Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId);
}
