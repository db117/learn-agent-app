package com.example.agent.learning.path;

import com.example.agent.learning.catalog.LearnUnit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 LearnUnit 前置关系和课程顺序生成稳定学习路径的规划器。
 *
 * <p>规划器只计算路径，不写数据库；调用方负责持久化并根据当前节点执行状态迁移。
 */
@Component
public class DeterministicLearningPathPlanner {

    /**
     * 根据排序后的 LearnUnit 生成路径，并在保留历史进度的同时选择首个 CURRENT 节点。
     *
     * <p>已完成或已跳过的节点保留原状态，其余节点重置为 PENDING；最后把第一个待处理节点设为 CURRENT。</p>
     *
     * @param journeyId Journey 标识
     * @param learnUnits 当前 Journey 的 LearnUnit
     * @param existingItems 已持久化的路径节点
     * @return 新的确定性路径
     */
    public List<LearningPathItem> plan(
            String journeyId,
            List<LearnUnit> learnUnits,
            List<LearningPathItem> existingItems) {
        List<LearnUnit> ordered = order(learnUnits);
        Map<String, LearningPathItem> existingByCode = new HashMap<>();
        existingItems.forEach(item -> existingByCode.put(item.learnUnitCode(), item));
        List<LearningPathItem> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            LearnUnit learnUnit = ordered.get(index);
            LearningPathItem previous = existingByCode.get(learnUnit.code());
            LearningPathItemStatus status = previous != null
                    && (previous.status() == LearningPathItemStatus.COMPLETED
                    || previous.status() == LearningPathItemStatus.SKIPPED)
                    ? previous.status() : LearningPathItemStatus.PENDING;
            result.add(new LearningPathItem(
                    previous == null ? UUID.randomUUID().toString() : previous.id(), journeyId, learnUnit.code(), index + 1,
                    status,
                    previous == null ? 0 : previous.masteryScore(),
                    previous == null ? 0 : previous.bestAssessmentScore(),
                    previous == null ? 0 : previous.attemptCount(),
                    previous == null ? null : previous.passReason(),
                    previous == null ? null : previous.startedAt(),
                    previous == null ? null : previous.passedAt(),
                    previous == null ? null : previous.skippedAt()));
        }
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).status() == LearningPathItemStatus.PENDING) {
                LearningPathItem item = result.get(index);
                result.set(index, new LearningPathItem(
                        item.id(), item.journeyId(), item.learnUnitCode(), item.sequence(), LearningPathItemStatus.CURRENT,
                        item.masteryScore(), item.bestAssessmentScore(), item.attemptCount(), item.passReason(),
                        item.startedAt(), item.passedAt(), item.skippedAt()));
                break;
            }
        }
        return result;
    }

    /**
     * 按前置关系执行稳定的拓扑排序。
     *
     * <p>每轮从无未完成前置的节点中选择 sequence 最小、code 最小的节点；若输入包含环路，
     * 将剩余节点按同样的稳定顺序追加，最终由上层课程校验拒绝该非法目录。</p>
     *
     * @param learnUnits 待排序的 LearnUnit
     * @return 排序后的 LearnUnit
     */
    public List<LearnUnit> order(List<LearnUnit> learnUnits) {
        Map<String, LearnUnit> byCode = new HashMap<>();
        for (LearnUnit learnUnit : learnUnits) byCode.put(learnUnit.code(), learnUnit);
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (LearnUnit learnUnit : learnUnits) {
            int count = 0;
            for (String prerequisite : learnUnit.prerequisiteLearnUnitCodes()) {
                if (byCode.containsKey(prerequisite)) {
                    count++;
                    outgoing.computeIfAbsent(prerequisite, ignored -> new ArrayList<>()).add(learnUnit.code());
                }
            }
            incoming.put(learnUnit.code(), count);
        }
        List<LearnUnit> ready = learnUnits.stream()
                .filter(learnUnit -> incoming.get(learnUnit.code()) == 0)
                .sorted(Comparator.comparingInt(LearnUnit::sequence).thenComparing(LearnUnit::code))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<LearnUnit> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            LearnUnit learnUnit = ready.remove(0);
            ordered.add(learnUnit);
            for (String next : outgoing.getOrDefault(learnUnit.code(), List.of())) {
                int count = incoming.merge(next, -1, Integer::sum);
                if (count == 0) {
                    ready.add(byCode.get(next));
                    ready.sort(Comparator.comparingInt(LearnUnit::sequence).thenComparing(LearnUnit::code));
                }
            }
        }
        if (ordered.size() != learnUnits.size()) {
            Set<String> included = ordered.stream().map(LearnUnit::code).collect(java.util.stream.Collectors.toSet());
            learnUnits.stream().filter(learnUnit -> !included.contains(learnUnit.code()))
                    .sorted(Comparator.comparingInt(LearnUnit::sequence).thenComparing(LearnUnit::code))
                    .forEach(ordered::add);
        }
        return ordered;
    }
}
