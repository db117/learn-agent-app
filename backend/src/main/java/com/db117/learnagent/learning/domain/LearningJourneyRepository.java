package com.db117.learnagent.learning.domain;

import java.util.Optional;

/** LearningJourney 聚合端口；状态切换和历史必须以聚合为单位保存。 */
public interface LearningJourneyRepository {
    LearningJourney save(LearningJourney journey);

    /** 删除尚未挂接到 Journey 的新建路径，用于确认规划的失败补偿。 */
    void delete(long id);

    Optional<LearningJourney> findById(long id);

    Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId);
}
