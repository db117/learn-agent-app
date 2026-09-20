package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Learning Domain 的聚合根，统一拥有 Journey 内容快照和路径进度。 */
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
    private final List<LearningPathItem> pathItems;

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
            List<LearningPathItem> pathItems) {
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
        this.pathItems = List.copyOf(pathItems == null ? List.of() : pathItems);
        validateContent(this.chapters, this.learnUnits, this.pathItems);
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
                items);
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
            List<LearningPathItem> pathItems) {
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
                pathItems);
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

    public List<LearningPathItem> pathItems() {
        return pathItems;
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

    public LearningJourney withId(long persistedId) {
        return copy(
                DomainChecks.id(persistedId, "id"),
                chapters,
                learnUnits,
                pathItems,
                status,
                completedAt);
    }

    public LearningJourney withPersistedIds(
            long persistedId,
            List<Chapter> persistedChapters,
            List<LearnUnit> persistedLearnUnits,
            List<LearningPathItem> persistedPathItems) {
        return copy(
                DomainChecks.id(persistedId, "id"),
                persistedChapters,
                persistedLearnUnits,
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
                : copy(id, chapters, learnUnits, nextItems, status, completedAt);
    }

    /** 只允许为当前学习项写入进入阶段后生成的教学内容。 */
    public LearningJourney materializeLearnUnitContent(String learnUnitCode, String content) {
        var current = currentItem();
        if (current == null || !current.learnUnitCode().equals(learnUnitCode)) {
            throw new DomainRuleViolation("content must belong to the current LearnUnit");
        }
        var generatedContent = DomainChecks.text(content, "content");
        var nextUnits = new ArrayList<LearnUnit>();
        boolean found = false;
        for (var unit : learnUnits) {
            if (unit.code().equals(learnUnitCode)) {
                nextUnits.add(unit.withContent(generatedContent));
                found = true;
            } else {
                nextUnits.add(unit);
            }
        }
        if (!found) {
            throw new DomainRuleViolation("unknown LearnUnit: " + learnUnitCode);
        }
        return copy(id, chapters, nextUnits, pathItems, status, completedAt);
    }

    /** 关闭当前项后只自动推进 PENDING，不擅自重新打开 SKIPPED。 */
    private LearningJourney advanceAfterClose(List<LearningPathItem> closedItems, Instant at) {
        var nextItems = new ArrayList<>(closedItems);
        var nextPending = nextItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .findFirst();
        if (nextPending.isPresent()) {
            int index = nextItems.indexOf(nextPending.get());
            nextItems.set(index, nextPending.get().start(at));
            return copy(id, chapters, learnUnits, nextItems,
                    LearningJourneyStatus.ACTIVE, null);
        }
        return copy(id, chapters, learnUnits, nextItems,
                LearningJourneyStatus.COMPLETED, at);
    }

    private LearningPathItem item(String learnUnitCode) {
        return pathItems.stream()
                .filter(pathItem -> pathItem.learnUnitCode().equals(learnUnitCode))
                .findFirst()
                .orElseThrow(() -> new DomainRuleViolation("unknown path item: " + learnUnitCode));
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
                nextItems);
    }

    /** 验证 Journey 内内容和路径，阻止半有效聚合落库。 */
    private static void validateContent(
            List<Chapter> chapters,
            List<LearnUnit> learnUnits,
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
            var completedCodes = ordered.stream().map(LearnUnit::code)
                    .collect(java.util.stream.Collectors.toSet());
            var candidates = remaining.stream()
                    .map(byCode::get)
                    .filter(unit -> completedCodes.containsAll(unit.prerequisiteCodes()))
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
