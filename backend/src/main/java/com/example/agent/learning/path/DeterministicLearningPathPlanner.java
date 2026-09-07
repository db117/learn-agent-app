package com.example.agent.learning.path;

import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.journey.LearnerSkill;
import com.example.agent.learning.journey.LearnerSkillStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基于技能前置关系和课程顺序生成稳定学习路径的规划器。
 *
 * <p>规划器只计算路径，不写数据库；调用方负责持久化并根据当前节点执行状态迁移。
 */
@Component
public class DeterministicLearningPathPlanner {

    public List<LearningPathItem> plan(
            String journeyId,
            List<LearningSkill> skills,
            Map<String, LearnerSkill> learnerSkills) {
        List<LearningSkill> ordered = order(skills);
        List<LearningPathItem> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            LearningSkill skill = ordered.get(index);
            LearnerSkill learnerSkill = learnerSkills.get(skill.code());
            LearningPathItemStatus status = learnerSkill != null && learnerSkill.status() == LearnerSkillStatus.PASSED
                    ? LearningPathItemStatus.COMPLETED
                    : learnerSkill != null && learnerSkill.status() == LearnerSkillStatus.SKIPPED
                    ? LearningPathItemStatus.SKIPPED
                    : LearningPathItemStatus.PENDING;
            result.add(new LearningPathItem(UUID.randomUUID().toString(), journeyId, skill.code(), index + 1, status));
        }
        for (int index = 0; index < result.size(); index++) {
            if (result.get(index).status() == LearningPathItemStatus.PENDING) {
                LearningPathItem item = result.get(index);
                result.set(index, new LearningPathItem(item.id(), item.journeyId(), item.skillCode(), item.sequence(), LearningPathItemStatus.CURRENT));
                break;
            }
        }
        return result;
    }

    public List<LearningSkill> order(List<LearningSkill> skills) {
        Map<String, LearningSkill> byCode = new HashMap<>();
        for (LearningSkill skill : skills) byCode.put(skill.code(), skill);
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (LearningSkill skill : skills) {
            int count = 0;
            for (String prerequisite : skill.prerequisiteSkillCodes()) {
                if (byCode.containsKey(prerequisite)) {
                    count++;
                    outgoing.computeIfAbsent(prerequisite, ignored -> new ArrayList<>()).add(skill.code());
                }
            }
            incoming.put(skill.code(), count);
        }
        List<LearningSkill> ready = skills.stream()
                .filter(skill -> incoming.get(skill.code()) == 0)
                .sorted(Comparator.comparingInt(LearningSkill::sequence).thenComparing(LearningSkill::code))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<LearningSkill> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            LearningSkill skill = ready.remove(0);
            ordered.add(skill);
            for (String next : outgoing.getOrDefault(skill.code(), List.of())) {
                int count = incoming.merge(next, -1, Integer::sum);
                if (count == 0) {
                    ready.add(byCode.get(next));
                    ready.sort(Comparator.comparingInt(LearningSkill::sequence).thenComparing(LearningSkill::code));
                }
            }
        }
        if (ordered.size() != skills.size()) {
            Set<String> included = ordered.stream().map(LearningSkill::code).collect(java.util.stream.Collectors.toSet());
            skills.stream().filter(skill -> !included.contains(skill.code()))
                    .sorted(Comparator.comparingInt(LearningSkill::sequence).thenComparing(LearningSkill::code))
                    .forEach(ordered::add);
        }
        return ordered;
    }
}
