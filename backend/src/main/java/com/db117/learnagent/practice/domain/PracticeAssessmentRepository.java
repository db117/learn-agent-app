package com.db117.learnagent.practice.domain;

import java.util.Optional;

/** 不可变 Tutor 评估的持久化端口。 */
public interface PracticeAssessmentRepository {
    PracticeAssessment save(PracticeAssessment assessment);

    Optional<PracticeAssessment> findById(long id);

    Optional<PracticeAssessment> findLatest(long journeyId, long learnUnitId);
}
