package com.example.agent.learning.catalog;

import java.util.List;

/**
 * 课程中的一个可评估 LearnUnit。
 *
 * <p>该 record 同时承载目录信息、前置关系、通过规则和课程展示内容，
 * 由 Repository 从 SQLite 的课程表恢复。</p>
 *
 * @param id 数据库中的稳定主键
 * @param languageCode 所属学习语言编码
 * @param code LearnUnit 唯一编码，也是 Path 和评估引用的业务键
 * @param chapterCode 所属 Chapter 编码
 * @param name LearnUnit 名称
 * @param description LearnUnit 简介
 * @param sequence 课程默认顺序；前置关系排序时用于稳定打破并列
 * @param prerequisiteLearnUnitCodes 必须先完成的 LearnUnit 编码
 * @param passScore LearnUnit 评估总分通过线
 * @param minCodingScore 存在 Coding 题时的最低 Coding 分数，可为空
 * @param enabled 是否仍在课程中开放
 * @param learningObjectives 学习目标
 * @param lessonIntro Lesson 的开场内容
 * @param keyConcepts 关键概念
 * @param examples 示例内容
 * @param diagnosticEligible 是否纳入初始诊断
 * @param ability 本单元唯一可验证的能力
 * @param estimatedMinutes 预计学习时长
 * @param guidedPracticePrompt 引导练习题面
 * @param guidedPracticeHints 引导练习提示
 * @param independentCheckPrompt 独立检查说明
 */
public record LearnUnit(
        String id,
        String languageCode,
        String code,
        String chapterCode,
        String name,
        String description,
        int sequence,
        List<String> prerequisiteLearnUnitCodes,
        int passScore,
        Integer minCodingScore,
        boolean enabled,
        List<String> learningObjectives,
        String lessonIntro,
        List<String> keyConcepts,
        List<String> examples,
        boolean diagnosticEligible,
        String ability,
        int estimatedMinutes,
        String guidedPracticePrompt,
        List<String> guidedPracticeHints,
        String independentCheckPrompt) {

    public LearnUnit {
        prerequisiteLearnUnitCodes = List.copyOf(prerequisiteLearnUnitCodes == null ? List.of() : prerequisiteLearnUnitCodes);
        learningObjectives = List.copyOf(learningObjectives == null ? List.of() : learningObjectives);
        keyConcepts = List.copyOf(keyConcepts == null ? List.of() : keyConcepts);
        examples = List.copyOf(examples == null ? List.of() : examples);
        guidedPracticeHints = List.copyOf(guidedPracticeHints == null ? List.of() : guidedPracticeHints);
    }

    /** 大纲构造器；正文和阶段内容在首次进入 LearnUnit 时才填充。 */
    public LearnUnit(
            String id,
            String languageCode,
            String code,
            String chapterCode,
            String name,
            String description,
            int sequence,
            List<String> prerequisiteLearnUnitCodes,
            int passScore,
            Integer minCodingScore,
            boolean enabled,
            List<String> learningObjectives,
            String lessonIntro,
            List<String> keyConcepts,
            List<String> examples,
            boolean diagnosticEligible) {
        this(id, languageCode, code, chapterCode, name, description, sequence, prerequisiteLearnUnitCodes,
                passScore, minCodingScore, enabled, learningObjectives, lessonIntro, keyConcepts, examples,
                diagnosticEligible, "", 0, "", List.of(), "");
    }

    /** 大纲行在生成教学正文前允许为空；题目也不会在此阶段生成。 */
    public boolean hasDetailedContent() {
        return lessonIntro != null && !lessonIntro.isBlank() && !examples.isEmpty()
                && ability != null && !ability.isBlank() && estimatedMinutes > 0
                && guidedPracticePrompt != null && !guidedPracticePrompt.isBlank()
                && independentCheckPrompt != null && !independentCheckPrompt.isBlank();
    }

    /** 保留稳定的目录身份，只替换模型按需生成的教学正文。 */
    public LearnUnit withDetailedContent(
            List<String> objectives, String intro, List<String> concepts, List<String> generatedExamples) {
        return new LearnUnit(
                id, languageCode, code, chapterCode, name, description, sequence, prerequisiteLearnUnitCodes,
                passScore, minCodingScore, enabled, objectives, intro, concepts, generatedExamples,
                diagnosticEligible, ability, estimatedMinutes, guidedPracticePrompt, guidedPracticeHints,
                independentCheckPrompt);
    }

    /** 只替换首次进入时生成的教学内容，不改变目录身份和评分规则。 */
    public LearnUnit withStructuredContent(
            String generatedAbility,
            int generatedEstimatedMinutes,
            String intro,
            List<String> generatedExamples,
            String guidedPrompt,
            List<String> generatedHints,
            String independentPrompt) {
        return new LearnUnit(
                id, languageCode, code, chapterCode, name, description, sequence, prerequisiteLearnUnitCodes,
                passScore, minCodingScore, enabled, learningObjectives, intro, keyConcepts, generatedExamples,
                diagnosticEligible, generatedAbility, generatedEstimatedMinutes, guidedPrompt, generatedHints,
                independentPrompt);
    }
}
