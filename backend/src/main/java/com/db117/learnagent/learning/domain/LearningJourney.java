package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.*;

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
        List<LearnUnit> orderedUnits = orderUnits(learnUnits);
        ArrayList<LearningPathItem> items = new ArrayList<LearningPathItem>();
        for (int index = 0; index < orderedUnits.size(); index++) {
            LearnUnit unit = orderedUnits.get(index);
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
        LearningPathItem target = pathItems.get(targetIndex);
        if (target.status() == LearningPathItemStatus.COMPLETED) {
            throw new DomainRuleViolation("completed item cannot be activated: " + learnUnitCode);
        }
        if (target.status() == LearningPathItemStatus.PENDING) {
            throw new DomainRuleViolation("pending item is locked until the previous item is completed: "
                    + learnUnitCode);
        }

        ArrayList<LearningPathItem> nextItems = new ArrayList<>(pathItems);
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
        LearningPathItem current = currentItem();
        if (current == null) {
            throw new DomainRuleViolation("journey has no current item");
        }
        List<LearningPathItem> nextItems = replaceItem(current.learnUnitCode(), current.skip(at));
        return advanceAfterClose(nextItems, at);
    }

    /** 将已验证的 Practice 证据纳入当前项，完成当前课并推进。 */
    public LearningJourney recordPracticeVerified(String learnUnitCode, Instant at) {
        LearningPathItem item = item(learnUnitCode);
        LearningPathItem nextItem = item.recordPracticeVerified(at);
        List<LearningPathItem> nextItems = replaceItem(learnUnitCode, nextItem);
        return nextItem.status() == LearningPathItemStatus.COMPLETED
                ? advanceAfterClose(nextItems, at)
                : copy(id, chapters, learnUnits, nextItems, status, completedAt);
    }

    /** 仅把学习者确认的 READY 评估作为完成依据；已有客观 PracticeEvidence 保持原样。 */
    public LearningJourney acceptAssessment(
            String learnUnitCode,
            long assessmentId,
            boolean objectivePracticeVerified,
            Instant at) {
        DomainChecks.time(at, "at");
        LearningPathItem item = item(learnUnitCode);
        if (item.status() == LearningPathItemStatus.COMPLETED && Objects.equals(item.assessmentId(), assessmentId)) {
            return this;
        }
        LearningPathItem completedItem = item.acceptAssessment(assessmentId, objectivePracticeVerified, at);
        List<LearningPathItem> nextItems = replaceItem(learnUnitCode, completedItem);
        return advanceAfterClose(nextItems, at);
    }

    /** 应用已由学习者确认的未来路线；完成项保留原内容与身份，移除项留作 SKIPPED 历史。 */
    public LearningJourney replan(
            List<Chapter> proposedChapters,
            List<LearnUnit> proposedUnits,
            Instant at) {
        DomainChecks.time(at, "at");
        List<Chapter> requestedChapters = List.copyOf(proposedChapters == null ? List.of() : proposedChapters);
        List<LearnUnit> requestedUnits = proposedUnits == null || proposedUnits.isEmpty()
                ? List.of()
                : orderUnits(proposedUnits);
        HashMap<String, LearnUnit> currentUnits = new HashMap<String, LearnUnit>();
        HashMap<String, LearningPathItem> currentItems = new HashMap<String, LearningPathItem>();
        for (LearnUnit unit : learnUnits) {
            currentUnits.put(unit.code(), unit);
        }
        for (LearningPathItem item : pathItems) {
            currentItems.put(item.learnUnitCode(), item);
        }
        Set<String> completedCodes = pathItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .map(LearningPathItem::learnUnitCode)
                .collect(java.util.stream.Collectors.toSet());
        HashSet<String> requestedCodes = new HashSet<String>();
        for (LearnUnit unit : requestedUnits) {
            if (completedCodes.contains(unit.code())) {
                throw new DomainRuleViolation("route proposal cannot change a completed LearnUnit: " + unit.code());
            }
            if (!requestedCodes.add(unit.code())) {
                throw new DomainRuleViolation("route proposal LearnUnit codes must be unique: " + unit.code());
            }
        }

        HashMap<String, Chapter> currentChapters = new HashMap<String, Chapter>();
        HashMap<String, Chapter> requestedChapterByCode = new HashMap<String, Chapter>();
        for (Chapter chapter : chapters) {
            currentChapters.put(chapter.code(), chapter);
        }
        for (Chapter chapter : requestedChapters) {
            if (requestedChapterByCode.put(chapter.code(), chapter) != null) {
                throw new DomainRuleViolation("route proposal Chapter codes must be unique: " + chapter.code());
            }
        }
        HashSet<String> availableChapterCodes = new HashSet<String>(currentChapters.keySet());
        availableChapterCodes.addAll(requestedChapterByCode.keySet());

        ArrayList<LearningPathItem> completedItems = pathItems.stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .sorted(Comparator.comparingInt(LearningPathItem::sequence))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ArrayList<LearnUnit> nextUnits = new ArrayList<LearnUnit>();
        ArrayList<LearningPathItem> nextItems = new ArrayList<LearningPathItem>();
        int sequence = 0;
        String previousCode = null;
        for (LearningPathItem completedItem : completedItems) {
            LearnUnit unit = currentUnits.get(completedItem.learnUnitCode());
            nextUnits.add(unit.withRoute(unit.title(), unit.objective(), sequence,
                    unit.chapterCode(), unit.prerequisiteCodes()));
            nextItems.add(completedItem.withSequence(sequence));
            previousCode = unit.code();
            sequence++;
        }

        int proposedIndex = 0;
        for (LearnUnit proposedUnit : requestedUnits) {
            if (!availableChapterCodes.contains(proposedUnit.chapterCode())) {
                throw new DomainRuleViolation("route proposal references an unknown Chapter: "
                        + proposedUnit.chapterCode());
            }
            Set<String> prerequisites = previousCode == null ? Set.<String>of() : Set.of(previousCode);
            LearnUnit currentUnit = currentUnits.get(proposedUnit.code());
            LearnUnit routedUnit = currentUnit == null
                    ? LearnUnit.create(proposedUnit.code(), proposedUnit.title(), proposedUnit.objective(), "",
                    sequence, proposedUnit.chapterCode(), prerequisites)
                    : currentUnit.withRoute(proposedUnit.title(), proposedUnit.objective(), sequence,
                    proposedUnit.chapterCode(), prerequisites);
            LearningPathItem currentItem = currentItems.get(proposedUnit.code());
            LearningPathItemStatus nextStatus = proposedIndex == 0
                    ? LearningPathItemStatus.CURRENT
                    : LearningPathItemStatus.PENDING;
            LearningPathItem routedItem = currentItem == null
                    ? (nextStatus == LearningPathItemStatus.CURRENT
                    ? LearningPathItem.current(proposedUnit.code(), sequence, at)
                    : LearningPathItem.pending(proposedUnit.code(), sequence, at))
                    : currentItem.replan(sequence, nextStatus, at);
            nextUnits.add(routedUnit);
            nextItems.add(routedItem);
            previousCode = proposedUnit.code();
            proposedIndex++;
            sequence++;
        }

        List<LearningPathItem> removedItems = pathItems.stream()
                .filter(item -> item.status() != LearningPathItemStatus.COMPLETED)
                .filter(item -> !requestedCodes.contains(item.learnUnitCode()))
                .sorted(Comparator.comparingInt(LearningPathItem::sequence))
                .toList();
        for (LearningPathItem removedItem : removedItems) {
            LearnUnit unit = currentUnits.get(removedItem.learnUnitCode());
            nextUnits.add(unit.withRoute(unit.title(), unit.objective(), sequence,
                    unit.chapterCode(), unit.prerequisiteCodes()));
            nextItems.add(removedItem.replan(sequence, LearningPathItemStatus.SKIPPED, at));
            sequence++;
        }

        HashSet<String> completedChapterCodes = new HashSet<String>();
        for (LearningPathItem completedItem : completedItems) {
            LearnUnit unit = currentUnits.get(completedItem.learnUnitCode());
            Chapter chapter = currentChapters.get(unit.chapterCode());
            completedChapterCodes.add(chapter.code());
        }
        ArrayList<Chapter> nextChapters = new ArrayList<Chapter>();
        HashSet<String> orderedChapterCodes = new HashSet<String>();
        for (LearnUnit unit : nextUnits) {
            if (!orderedChapterCodes.add(unit.chapterCode())) {
                continue;
            }
            Chapter historical = currentChapters.get(unit.chapterCode());
            Chapter requested = requestedChapterByCode.get(unit.chapterCode());
            String chapterTitle = completedChapterCodes.contains(unit.chapterCode())
                    ? historical.title()
                    : requested == null ? historical.title() : requested.title();
            nextChapters.add(new Chapter(
                    historical == null ? null : historical.id(),
                    unit.chapterCode(),
                    chapterTitle,
                    nextChapters.size()));
        }

        LearningJourneyStatus nextStatus = requestedUnits.isEmpty()
                ? LearningJourneyStatus.COMPLETED
                : LearningJourneyStatus.ACTIVE;
        return copy(id, nextChapters, nextUnits, nextItems, nextStatus,
                requestedUnits.isEmpty() ? at : null);
    }

    /** 只允许为当前学习项写入进入阶段后生成的教学内容。 */
    public LearningJourney materializeLearnUnitContent(String learnUnitCode, String content) {
        LearningPathItem current = currentItem();
        if (current == null || !current.learnUnitCode().equals(learnUnitCode)) {
            throw new DomainRuleViolation("content must belong to the current LearnUnit");
        }
        String generatedContent = DomainChecks.text(content, "content");
        ArrayList<LearnUnit> nextUnits = new ArrayList<LearnUnit>();
        boolean found = false;
        for (LearnUnit unit : learnUnits) {
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
        ArrayList<LearningPathItem> nextItems = new ArrayList<>(closedItems);
        java.util.Optional<LearningPathItem> nextPending = nextItems.stream()
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
        ArrayList<LearningPathItem> next = new ArrayList<>(pathItems);
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
        Set<String> chapterCodes = uniqueCodes(chapters.stream().map(Chapter::code).toList(), "chapter code");
        Set<String> unitCodes = uniqueCodes(learnUnits.stream().map(LearnUnit::code).toList(), "LearnUnit code");
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
        HashSet<String> unique = new HashSet<>(codes);
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
        HashMap<String, LearnUnit> byCode = new HashMap<String, LearnUnit>();
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
        ArrayList<LearnUnit> ordered = new ArrayList<LearnUnit>();
        HashSet<String> remaining = new HashSet<>(byCode.keySet());
        Comparator<LearnUnit> comparator = Comparator.comparingInt(LearnUnit::sequence).thenComparing(LearnUnit::code);
        while (!remaining.isEmpty()) {
            // 每轮只从已满足全部前置条件的候选中取最稳定的一项；无候选即代表存在环。
            Set<String> completedCodes = ordered.stream().map(LearnUnit::code)
                    .collect(java.util.stream.Collectors.toSet());
            List<LearnUnit> candidates = remaining.stream()
                    .map(byCode::get)
                    .filter(unit -> completedCodes.containsAll(unit.prerequisiteCodes()))
                    .sorted(comparator)
                    .toList();
            if (candidates.isEmpty()) {
                throw new DomainRuleViolation("LearnUnit prerequisites contain a cycle");
            }
            LearnUnit next = candidates.get(0);
            ordered.add(next);
            remaining.remove(next.code());
        }
        return List.copyOf(ordered);
    }
}
