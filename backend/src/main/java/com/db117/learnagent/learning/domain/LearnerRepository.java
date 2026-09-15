package com.db117.learnagent.learning.domain;

import java.util.Optional;

/** Learner 聚合的持久化端口，具体数据库实现留在 Infrastructure。 */
public interface LearnerRepository {
    Learner save(Learner learner);

    Optional<Learner> findById(long id);
}
