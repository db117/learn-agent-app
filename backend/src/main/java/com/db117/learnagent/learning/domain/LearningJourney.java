package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.*;

/** Learning Domain 的聚合根，统一拥有 Journey 内容快照、路径进度和评估历史。 */
public final class LearningJourney {
    private final Long id;
    private final long learnerId;
    private final String languagePackId;
    private final String title;
    private final LearningJourneyStatus status;
    private final Instant createdAt;
    private final Instant completedAt;
    private final List<Chapter> chapters;
    private final List<LearnUnit> learnUnits;
    private final List<Assessment> assessments;
    private final List<LearningPathItem> pathItems;
    private final List<AssessmentAttempt> assessmentAttempts;

    private LearningJourney(
            Long id,
            long learnerId,
            String languagePackId,
            String title,
            LearningJourneyStatus status,
            Instant createdAt,
            Instant completedAt,
            List<Chapter> chapters,
            List<LearnUnit> learnUnits,
            List<Assessment> assessments,
            List<LearningPathItem> pathItems,
            List<AssessmentAttempt> assessmentAttempts) {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        DomainChecks.id(learnerId, "learnerId");
        languagePackId = DomainChecks.text(languagePackId, "languagePackId");
        title = DomainChecks.text(title, "title");
        status = status == null ? throwRule("status must not be null") : status;
        createdAt = DomainChecks.time(createdAt, "createdAt");
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new DomainRuleViolation("completedAt must not precede createdAt");
        }
        this.id = id;
        this.learnerId = learnerId;
        this.languagePackId = languagePackId;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.chapters = List.copyOf(chapters == null ? List.of() : chapters);
        this.learnUnits = List.copyOf(learnUnits == null ? List.of() : learnUnits);
        this.assessments = List.copyOf(assessments == null ? List.of() : assessments);
        this.pathItems = List.copyOf(pathItems == null ? List.of() : pathItems);
        this.assessmentAttempts = List.copyOf(assessmentAttempts == null ? List.of() : assessmentAttempts);
        validateContent(this.chapters, this.learnUnits, this.assessments, this.pathItems);
        var attemptIds = new HashSet<Long>();
        for (AssessmentAttempt attempt : this.assessmentAttempts) {
            if (id == null || attempt.journeyId() != id) {
                throw new DomainRuleViolation("assessment attempt has an invalid journeyId");
            }
            if (!unitCodes(this.learnUnits).contains(attempt.learnUnitCode())) {
                throw new DomainRuleViolation("assessment attempt references an unknown LearnUnit");
            }
            if (attempt.id() != null && !attemptIds.add(attempt.id())) {
                throw new DomainRuleViolation("assessment attempt ids must be unique");
            }
        }
        long currentCount = this.pathItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                .count();
        if (currentCount > 1) {
            throw new DomainRuleViolation("a journey can have only one current item");
        }
        if (status == LearningJourneyStatus.ACTIVE && currentCount != 1) {
            throw new DomainRuleViolation("active journey must have exactly one current item");
        }
        if (status == LearningJourneyStatus.COMPLETED && completedAt == null) {
            throw new DomainRuleViolation("completed journey needs completedAt");
        }
        if (status == LearningJourneyStatus.ACTIVE && completedAt != null) {
            throw new DomainRuleViolation("active journey cannot have completedAt");
        }
        if (status == LearningJourneyStatus.COMPLETED) {
            if (currentCount != 0 || this.pathItems.stream().anyMatch(item ->
                    item.status() != LearningPathItemStatus.COMPLETED
                            && item.status() != LearningPathItemStatus.SKIPPED)) {
                throw new DomainRuleViolation("completed journey must have no pending or current item");
            }
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    /** 创建 Journey 并按前置关系生成稳定路径；第一项直接成为 CURRENT。 */
    public static LearningJourney create(
            long learnerId,
            String languagePackId,
            String title,
            List<Chapter> chapters,
            List<LearnUnit> learnUnits,
            List<Assessment> assessments,
            Instant createdAt) {
        var orderedUnits = orderUnits(learnUnits);
        var items = new ArrayList<LearningPathItem>();
        for (int index = 0; index < orderedUnits.size(); index++) {
            var unit = orderedUnits.get(index);
            items.add(index == 0
                    ? LearningPathItem.current(unit.code(), unit.sequence(), createdAt)
                    : LearningPathItem.pending(unit.code(), unit.sequence(), createdAt));
        }
        return new LearningJourney(
                null,
                learnerId,
                languagePackId,
                title,
                LearningJourneyStatus.ACTIVE,
                createdAt,
                null,
                chapters,
                learnUnits,
                assessments,
                items,
                List.of());
    }

    /** 从 Repository 恢复完整聚合，恢复时仍重新校验全部领域不变量。 */
    public static LearningJourney reconstitute(
            long id,
            long learnerId,
            String languagePackId,
            String title,
            LearningJourneyStatus status,
            Instant createdAt,
            Instant completedAt,
            List<Chapter> chapters,
            List<LearnUnit> learnUnits,
            List<Assessment> assessments,
            List<LearningPathItem> pathItems,
            List<AssessmentAttempt> assessmentAttempts) {
        return new LearningJourney(
                DomainChecks.id(id, "id"),
                learnerId,
                languagePackId,
                title,
                status,
                createdAt,
                completedAt,
                chapters,
                learnUnits,
                assessments,
                pathItems,
                assessmentAttempts);
    }

    public Long id() {
        return id;
    }

    public long learnerId() {
        return learnerId;
    }

    public String languagePackId() {
        return languagePackId;
    }

    public String title() {
        return title;
    }

    public LearningJourneyStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public List<Chapter> chapters() {
        return chapters;
    }

    public List<LearnUnit> learnUnits() {
        return learnUnits;
    }

    public List<Assessment> assessments() {
        return assessments;
    }

    public List<LearningPathItem> pathItems() {
        return pathItems;
    }

    public List<AssessmentAttempt> assessmentAttempts() {
        return assessmentAttempts;
    }

    public LearningPathItem currentItem() {
        return pathItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                .findFirst()
                .orElse(null);
    }

    public LearnUnit learnUnit(String code) {
        return learnUnits.stream()
                .filter(unit -> unit.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new DomainRuleViolation("unknown LearnUnit: " + code));
    }

    public Assessment assessmentFor(String learnUnitCode) {
        return assessments.stream()
                .filter(assessment -> assessment.learnUnitCode().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new DomainRuleViolation("missing Assessment: " + learnUnitCode));
    }

    public LearningJourney withId(long persistedId) {
        return copy(
                DomainChecks.id(persistedId, "id"),
                chapters,
                learnUnits,
                assessments,
                assessmentAttempts,
                pathItems,
                status,
                completedAt);
    }

    public LearningJourney withPersistedIds(
            long persistedId,
            List<Chapter> persistedChapters,
            List<LearnUnit> persistedLearnUnits,
            List<Assessment> persistedAssessments,
            List<LearningPathItem> persistedPathItems,
            List<AssessmentAttempt> persistedAttempts) {
        return copy(
                DomainChecks.id(persistedId, "id"),
                persistedChapters,
                persistedLearnUnits,
                persistedAssessments,
                persistedAttempts,
                persistedPathItems,
                status,
                completedAt);
    }

    /** 显式切换学习项；离开的 CURRENT 会变为可再次恢复的 SKIPPED。 */
    public LearningJourney activate(String learnUnitCode, Instant at) {
        DomainChecks.time(at, "at");
        int targetIndex = indexOfItem(learnUnitCode);
        var target = pathItems.get(targetIndex);
        if (target.status() == LearningPathItemStatus.COMPLETED) {
            throw new DomainRuleViolation("completed item cannot be activated: " + learnUnitCode);
        }
        if (target.status() == LearningPathItemStatus.PENDING) {
            throw new DomainRuleViolation("pending item is locked until the previous item is completed: "
                    + learnUnitCode);
        }

        var nextItems = new ArrayList<>(pathItems);
        for (int index = 0; index < nextItems.size(); index++) {
            if (index != targetIndex && nextItems.get(index).status() == LearningPathItemStatus.CURRENT) {
                nextItems.set(index, nextItems.get(index).skip(at));
            }
        }
        nextItems.set(targetIndex, target.start(at));
        return copy(
                id,
                chapters,
                learnUnits,
                assessments,
                assessmentAttempts,
                nextItems,
                LearningJourneyStatus.ACTIVE,
                null);
    }

    /** 跳过当前项后推进第一个 PENDING；没有 PENDING 时 Journey 完成。 */
    public LearningJourney skipCurrent(Instant at) {
        DomainChecks.time(at, "at");
        var current = currentItem();
        if (current == null) {
            throw new DomainRuleViolation("journey has no current item");
        }
        var nextItems = replaceItem(current.learnUnitCode(), current.skip(at));
        return advanceAfterClose(nextItems, at);
    }

    /** 将已验证的 Practice 证据纳入当前项，完成当前课并推进。 */
    public LearningJourney recordPracticeVerified(String learnUnitCode, Instant at) {
        var item = item(learnUnitCode);
        var nextItem = item.recordPracticeVerified(at);
        var nextItems = replaceItem(learnUnitCode, nextItem);
        return nextItem.status() == LearningPathItemStatus.COMPLETED
                ? advanceAfterClose(nextItems, at)
                : copy(id, chapters, learnUnits, assessments, assessmentAttempts, nextItems, status, completedAt);
    }

    /** 只接收当前项的评估历史；EVALUATED 结果才会影响 score 和完成状态。 */
    public LearningJourney recordAssessmentAttempt(AssessmentAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (attempt.journeyId() != journeyIdForComparison()) {
            throw new DomainRuleViolation("assessment attempt belongs to another journey");
        }
        var item = item(attempt.learnUnitCode());
        if (item.status() != LearningPathItemStatus.CURRENT) {
            throw new DomainRuleViolation("assessment requires a current item: " + attempt.learnUnitCode());
        }
        var nextAttempts = new ArrayList<>(assessmentAttempts);
        var existingIndex = attemptIndex(attempt.id());
        if (existingIndex >= 0) {
            var existing = nextAttempts.get(existingIndex);
            if (existing.status() == AssessmentAttemptStatus.EVALUATED
                    && attempt.status() == AssessmentAttemptStatus.EVALUATED) {
                throw new DomainRuleViolation("assessment attempt has already been evaluated");
            }
            // 评估是同一条提交历史的状态变化，不创建第二条 Attempt。
            nextAttempts.set(existingIndex, attempt);
        } else {
            if (attempt.id() != null) {
                throw new DomainRuleViolation("assessment attempt does not belong to this journey");
            }
            nextAttempts.add(attempt);
        }
        if (attempt.status() != AssessmentAttemptStatus.EVALUATED) {
            return copy(id, chapters, learnUnits, assessments, nextAttempts, pathItems, status, completedAt);
        }
        var assessment = assessmentFor(attempt.learnUnitCode());
        var nextItem = item.recordAssessment(attempt.score(), assessment.passingScore(), attempt.evaluatedAt());
        var nextItems = replaceItem(attempt.learnUnitCode(), nextItem);
        return nextItem.status() == LearningPathItemStatus.COMPLETED
                ? advanceAfterClose(nextItems, attempt.evaluatedAt(), nextAttempts)
                : copy(id, chapters, learnUnits, assessments, nextAttempts, nextItems, status, completedAt);
    }

    private long journeyIdForComparison() {
        if (id == null) {
            throw new DomainRuleViolation("journey must be persisted before recording an attempt");
        }
        return id;
    }

    /** 关闭当前项后只自动推进 PENDING，不擅自重新打开 SKIPPED。 */
    private LearningJourney advanceAfterClose(List<LearningPathItem> closedItems, Instant at) {
        return advanceAfterClose(closedItems, at, assessmentAttempts);
    }

    private LearningJourney advanceAfterClose(
            List<LearningPathItem> closedItems, Instant at, List<AssessmentAttempt> attempts) {
        var nextItems = new ArrayList<>(closedItems);
        var nextPending = nextItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .findFirst();
        if (nextPending.isPresent()) {
            int index = nextItems.indexOf(nextPending.get());
            nextItems.set(index, nextPending.get().start(at));
            return copy(id, chapters, learnUnits, assessments, attempts, nextItems,
                    LearningJourneyStatus.ACTIVE, null);
        }
        return copy(id, chapters, learnUnits, assessments, attempts, nextItems,
                LearningJourneyStatus.COMPLETED, at);
    }

    private LearningPathItem item(String learnUnitCode) {
        return pathItems.stream()
                .filter(pathItem -> pathItem.learnUnitCode().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new DomainRuleViolation("unknown path item: " + learnUnitCode));
    }

    private int attemptIndex(Long attemptId) {
        if (attemptId == null) {
            return -1;
        }
        for (int index = 0; index < assessmentAttempts.size(); index++) {
            if (attemptId.equals(assessmentAttempts.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private int indexOfItem(String learnUnitCode) {
        for (int index = 0; index < pathItems.size(); index++) {
            if (pathItems.get(index).learnUnitCode().equals(learnUnitCode)) {
                return index;
            }
        }
        throw new DomainRuleViolation("unknown path item: " + learnUnitCode);
    }

    private List<LearningPathItem> replaceItem(String code, LearningPathItem replacement) {
        var next = new ArrayList<>(pathItems);
        next.set(indexOfItem(code), replacement);
        return next;
    }

    private LearningJourney copy(
            Long nextId,
            List<Chapter> nextChapters,
            List<LearnUnit> nextLearnUnits,
            List<Assessment> nextAssessments,
            List<AssessmentAttempt> nextAttempts,
            List<LearningPathItem> nextItems,
            LearningJourneyStatus nextStatus,
            Instant nextCompletedAt) {
        return new LearningJourney(
                nextId,
                learnerId,
                languagePackId,
                title,
                nextStatus,
                createdAt,
                nextCompletedAt,
                nextChapters,
                nextLearnUnits,
                nextAssessments,
                nextItems,
                nextAttempts);
    }

    /** 验证 Journey 内内容、路径和可选历史 Assessment 关系，阻止半有效聚合落库。 */
    private static void validateContent(
            List<Chapter> chapters,
            List<LearnUnit> learnUnits,
            List<Assessment> assessments,
            List<LearningPathItem> pathItems) {
        if (chapters.isEmpty() || learnUnits.isEmpty()) {
            throw new DomainRuleViolation("journey content must contain chapters and LearnUnits");
        }
        var chapterCodes = uniqueCodes(chapters.stream().map(Chapter::code).toList(), "chapter code");
        var unitCodes = uniqueCodes(learnUnits.stream().map(LearnUnit::code).toList(), "LearnUnit code");
        for (LearnUnit unit : learnUnits) {
            if (!chapterCodes.contains(unit.chapterCode())) {
                throw new DomainRuleViolation("LearnUnit references an unknown Chapter: " + unit.chapterCode());
            }
            if (!unitCodes.containsAll(unit.prerequisiteCodes())) {
                throw new DomainRuleViolation("LearnUnit has an unknown prerequisite: " + unit.code());
            }
        }
        var assessmentUnits = new HashSet<String>();
        for (Assessment assessment : assessments) {
            if (!unitCodes.contains(assessment.learnUnitCode()) || !assessmentUnits.add(assessment.learnUnitCode())) {
                throw new DomainRuleViolation("Assessment must reference a unique LearnUnit");
            }
        }
        if (pathItems.size() != learnUnits.size()
                || !new HashSet<>(pathItems.stream().map(LearningPathItem::learnUnitCode).toList()).equals(unitCodes)) {
            throw new DomainRuleViolation("path items must contain each LearnUnit exactly once");
        }
    }

    private static Set<String> uniqueCodes(List<String> codes, String label) {
        var unique = new HashSet<>(codes);
        if (unique.size() != codes.size()) {
            throw new DomainRuleViolation(label + " must be unique");
        }
        return unique;
    }

    private static Set<String> unitCodes(List<LearnUnit> units) {
        return units.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
    }

    /** 用确定性拓扑排序生成路径；无前置项优先，sequence/code 解决并列。 */
    private static List<LearnUnit> orderUnits(List<LearnUnit> units) {
        if (units == null || units.isEmpty()) {
            throw new DomainRuleViolation("learnUnits must not be empty");
        }
        var byCode = new HashMap<String, LearnUnit>();
        for (LearnUnit unit : units) {
            if (byCode.put(unit.code(), unit) != null) {
                throw new DomainRuleViolation("LearnUnit code must be unique: " + unit.code());
            }
        }
        for (LearnUnit unit : units) {
            if (!byCode.keySet().containsAll(unit.prerequisiteCodes())) {
                throw new DomainRuleViolation("unknown prerequisite for LearnUnit: " + unit.code());
            }
        }
        var ordered = new ArrayList<LearnUnit>();
        var remaining = new HashSet<>(byCode.keySet());
        var comparator = Comparator.comparingInt(LearnUnit::sequence).thenComparing(LearnUnit::code);
        while (!remaining.isEmpty()) {
            // 每轮只从已满足全部前置条件的候选中取最稳定的一项；无候选即代表存在环。
            var candidates = remaining.stream()
                    .map(byCode::get)
                    .filter(unit -> ordered.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet())
                            .containsAll(unit.prerequisiteCodes()))
                    .sorted(comparator)
                    .toList();
            if (candidates.isEmpty()) {
                throw new DomainRuleViolation("LearnUnit prerequisites contain a cycle");
            }
            var next = candidates.get(0);
            ordered.add(next);
            remaining.remove(next.code());
        }
        return List.copyOf(ordered);
    }
}
