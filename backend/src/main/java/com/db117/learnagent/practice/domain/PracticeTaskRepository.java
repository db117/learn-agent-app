package com.db117.learnagent.practice.domain;

import java.util.List;
import java.util.Optional;

/** PracticeTask 聚合的持久化端口。 */
public interface PracticeTaskRepository {
    PracticeTask save(PracticeTask task);

    Optional<PracticeTask> findById(long id);

    List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId);
}
