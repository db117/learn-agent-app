package com.db117.learnagent.workspace.application;

import com.db117.learnagent.language.LanguagePack;
import com.db117.learnagent.language.LanguagePackCatalog;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.*;
import com.db117.learnagent.project.domain.ProjectRepository;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import com.db117.learnagent.workspace.domain.ProjectWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Optional;

/** 按 Learning Domain 所有权解析 Workspace；只为本地 IDE 集成暴露受归属校验的目录路径。 */
@ApplicationScoped
public final class WorkspaceApplicationService {
    private final LearnerRepository learnerRepository;
    private final JourneyRepository journeyRepository;
    private final LearningJourneyRepository learningJourneyRepository;
    private final ProjectRepository projectRepository;
    private final LanguagePackCatalog languagePackCatalog;
    private final WorkspaceManager workspaceManager;

    @Inject
    public WorkspaceApplicationService(
            LearnerRepository learnerRepository,
            JourneyRepository journeyRepository,
            LearningJourneyRepository learningJourneyRepository,
            ProjectRepository projectRepository,
            LanguagePackCatalog languagePackCatalog,
            WorkspaceManager workspaceManager) {
        this.learnerRepository = learnerRepository;
        this.journeyRepository = journeyRepository;
        this.learningJourneyRepository = learningJourneyRepository;
        this.projectRepository = projectRepository;
        this.languagePackCatalog = languagePackCatalog;
        this.workspaceManager = workspaceManager;
    }

    /** Bootstrap 时只初始化当前 Journey 已关联的 LearningJourney。 */
    public Optional<LearningWorkspace> ensureCurrentLearningWorkspace() {
        Optional<com.db117.learnagent.learning.domain.Learner> learner = learnerRepository.findCurrent();
        if (learner.isEmpty()) {
            return Optional.empty();
        }
        Optional<Journey> journey = journeyRepository.findCurrentByLearnerId(learner.get().id());
        if (journey.isEmpty() || journey.get().learningJourneyId() == null) {
            return Optional.empty();
        }
        return Optional.of(ensureLearningWorkspace(journey.get(), learner.get().id()));
    }

    /** 返回指定用户 Journey 的已初始化 Workspace；规划阶段不允许写文件。 */
    public LearningWorkspace learningWorkspace(long journeyId) {
        long learnerId = currentLearnerId();
        Journey journey = ownedJourney(journeyId, learnerId);
        if (journey.learningJourneyId() == null) {
            throw LearningRequestException.conflict(
                    "WORKSPACE_NOT_READY", "该 Journey 尚未生成 LearningJourney");
        }
        return ensureLearningWorkspace(journey, learnerId);
    }

    /** 返回指定用户 Project 的已初始化 Workspace。 */
    public ProjectWorkspace projectWorkspace(long projectId) {
        long learnerId = currentLearnerId();
        com.db117.learnagent.project.domain.Project project = projectRepository.findById(projectId)
                .filter(value -> ownsJourney(value.journeyId(), learnerId))
                .orElseThrow(() -> LearningRequestException.notFound(
                        "PROJECT_NOT_FOUND", "Project 不存在"));
        try {
            return workspaceManager.ensureProjectWorkspace(project.id());
        } catch (IOException error) {
            throw new WorkspaceInitializationException(error);
        }
    }

    private LearningWorkspace ensureLearningWorkspace(Journey journey, long learnerId) {
        LearningJourney learningJourney = learningJourneyRepository.findById(journey.learningJourneyId())
                .filter(value -> value.learnerId() == learnerId)
                .orElseThrow(() -> new WorkspaceInitializationException(
                        new IllegalStateException("LearningJourney is missing or owned by another learner")));
        LanguagePack languagePack = languagePack(learningJourney);
        try {
            return workspaceManager.ensureLearningWorkspace(journey.id(), languagePack);
        } catch (IOException error) {
            throw new WorkspaceInitializationException(error);
        }
    }

    private LanguagePack languagePack(LearningJourney journey) {
        try {
            return languagePackCatalog.get(journey.languagePackId());
        } catch (IllegalArgumentException error) {
            throw new WorkspaceInitializationException(error);
        }
    }

    private Journey ownedJourney(long journeyId, long learnerId) {
        return journeyRepository.findById(journeyId)
                .filter(value -> value.learnerId() == learnerId)
                .orElseThrow(() -> LearningRequestException.notFound(
                        "JOURNEY_NOT_FOUND", "学习 Journey 不存在"));
    }

    private boolean ownsJourney(long journeyId, long learnerId) {
        return journeyRepository.findById(journeyId)
                .map(value -> value.learnerId() == learnerId)
                .orElse(false);
    }

    private long currentLearnerId() {
        return learnerRepository.findCurrent()
                .map(value -> value.id())
                .orElseThrow(() -> LearningRequestException.conflict(
                        "LEARNER_SETUP_REQUIRED", "请先完成 Learner 设置"));
    }
}
