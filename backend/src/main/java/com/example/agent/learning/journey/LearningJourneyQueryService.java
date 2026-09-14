package com.example.agent.learning.journey;

import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 组装 Journey 详情和 LearnUnit 详情的只读查询服务。 */
@Service
public class LearningJourneyQueryService {

    private final LearningRepository repository;
    private final ProgressService progress;

    public LearningJourneyQueryService(LearningRepository repository, ProgressService progress) {
        this.repository = repository;
        this.progress = progress;
    }

    /** 查询 Journey 页面需要的跨表事实，并保留 Learning Engine 的进度判定。 */
    public LearningJourneySnapshot journey(String journeyId) {
        LearningJourney journey = repository.findJourney(journeyId)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + journeyId));
        List<LearningPathItem> path = repository.listPath(journeyId);
        List<LearnUnit> learnUnits = repository.listLearnUnitsForJourney(journeyId);
        List<LearningJourneySnapshot.ChapterSnapshot> chapters = repository.listChaptersForJourney(journeyId).stream()
                .map(chapter -> chapterSnapshot(journeyId, chapter, learnUnits, path))
                .toList();
        return new LearningJourneySnapshot(
                journey,
                repository.findProfile(journeyId).orElse(null),
                chapters,
                path,
                repository.findDiagnosticAssessment(journeyId).map(Assessment::id).orElse(null));
    }

    /** 查询指定 Journey 中的 LearnUnit，避免跨 Journey 读取同编码课程。 */
    public LearnUnitSnapshot learnUnit(String journeyId, String learnUnitCode) {
        repository.findJourney(journeyId)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + journeyId));
        LearnUnit learnUnit = repository.listLearnUnitsForJourney(journeyId).stream()
                .filter(value -> value.code().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit not found: " + learnUnitCode));
        return new LearnUnitSnapshot(
                journeyId,
                learnUnit,
                repository.findPathItem(journeyId, learnUnitCode).orElse(null),
                repository.listAttemptsForLearnUnit(journeyId, learnUnitCode),
                repository.listQuestionAttemptsForLearnUnit(journeyId, learnUnitCode));
    }

    private LearningJourneySnapshot.ChapterSnapshot chapterSnapshot(
            String journeyId,
            Chapter chapter,
            List<LearnUnit> learnUnits,
            List<LearningPathItem> path) {
        List<LearnUnit> chapterUnits = learnUnits.stream()
                .filter(unit -> unit.chapterCode().equals(chapter.code()))
                .toList();
        Set<String> chapterUnitCodes = chapterUnits.stream()
                .map(LearnUnit::code)
                .collect(Collectors.toSet());
        List<LearningPathItem> chapterPath = path.stream()
                .filter(item -> chapterUnitCodes.contains(item.learnUnitCode()))
                .toList();
        int completedCount = (int) chapterPath.stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .count();
        int skippedCount = (int) chapterPath.stream()
                .filter(item -> item.status() == LearningPathItemStatus.SKIPPED)
                .count();
        int unresolvedCount = (int) chapterPath.stream()
                .filter(item -> item.status() != LearningPathItemStatus.COMPLETED || item.needsReview())
                .count();
        return new LearningJourneySnapshot.ChapterSnapshot(
                chapter,
                chapterUnits,
                chapterPath,
                completedCount,
                skippedCount,
                unresolvedCount,
                progress.isChapterSynthesisEligible(journeyId, chapter.code()),
                progress.hasPassedChapterSynthesis(journeyId, chapter.code()),
                repository.findLatestChapterSynthesisAssessment(journeyId, chapter.code())
                        .map(Assessment::id)
                        .orElse(null));
    }
}
