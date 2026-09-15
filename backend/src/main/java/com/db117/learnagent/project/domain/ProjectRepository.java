package com.db117.learnagent.project.domain;

import java.util.Optional;

/** Project 聚合的持久化端口，包含 Journey 一对一查询。 */
public interface ProjectRepository {
    Project save(Project project);

    Optional<Project> findById(long id);

    Optional<Project> findByJourneyId(long journeyId);
}
