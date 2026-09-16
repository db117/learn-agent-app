package com.db117.learnagent.learning.domain;

import java.util.Optional;

/** Learner 聚合的持久化端口，具体数据库实现留在 Infrastructure。 */
public interface LearnerRepository {
    Learner save(Learner learner);

    Optional<Learner> findById(long id);

    /** 本地单用户应用的唯一 Learner；首次打开前可能不存在。 */
    Optional<Learner> findCurrent();
}
