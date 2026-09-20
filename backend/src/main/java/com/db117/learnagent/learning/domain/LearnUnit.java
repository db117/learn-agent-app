package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Journey 私有的不可变学习内容快照，不能把进度字段放回内容对象。
 *
 * @param id SQLite 自增主键；生成前为 {@code null}
 * @param code Journey 内稳定且唯一的 LearnUnit 编码
 * @param title LearnUnit 的展示标题
 * @param objective 学习者完成本单元后应达到的目标
 * @param content 大模型为当前 Journey 生成的内容快照
 * @param sequence 用于没有前置关系时的确定性排序
 * @param chapterCode 当前 Journey 内所属 Chapter 的编码
 * @param prerequisiteCodes 当前 Journey 内必须先满足的 LearnUnit 编码集合
 */
public record LearnUnit(
        Long id,
        String code,
        String title,
        String objective,
        String content,
        int sequence,
        String chapterCode,
        Set<String> prerequisiteCodes) {

    public LearnUnit {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        code = DomainChecks.text(code, "code");
        title = DomainChecks.text(title, "title");
        objective = DomainChecks.text(objective, "objective");
        content = DomainChecks.text(content, "content");
        chapterCode = DomainChecks.text(chapterCode, "chapterCode");
        if (sequence < 0) {
            throw new DomainRuleViolation("sequence must not be negative");
        }
        var prerequisites = new LinkedHashSet<String>();
        if (prerequisiteCodes != null) {
            for (String prerequisiteCode : prerequisiteCodes) {
                prerequisites.add(DomainChecks.text(prerequisiteCode, "prerequisiteCode"));
            }
        }
        if (prerequisites.contains(code)) {
            throw new DomainRuleViolation("LearnUnit cannot depend on itself: " + code);
        }
        prerequisiteCodes = Set.copyOf(prerequisites);
    }

    /** 从 Step 6 的内容快照提取 Practice 说明；旧快照没有该段时回退到学习目标。 */
    public String practiceInstruction() {
        var heading = "## Practice";
        var headingIndex = content.indexOf(heading);
        if (headingIndex < 0) {
            return objective;
        }
        var instruction = content.substring(headingIndex + heading.length()).strip();
        return instruction.isBlank() ? objective : instruction;
    }

    public static LearnUnit create(
            String code,
            String title,
            String objective,
            String content,
            int sequence,
            String chapterCode,
            Set<String> prerequisiteCodes) {
        return new LearnUnit(null, code, title, objective, content, sequence, chapterCode, prerequisiteCodes);
    }

    public LearnUnit withId(long persistedId) {
        return new LearnUnit(
                DomainChecks.id(persistedId, "id"),
                code,
                title,
                objective,
                content,
                sequence,
                chapterCode,
                prerequisiteCodes);
    }
}
