package com.example.agent.learning.lesson;

import com.example.agent.learning.catalog.LearningSkill;

import java.util.List;

/**
 * 从 {@link LearningSkill} 投影出的只读 Lesson 展示模型。
 *
 * @param skillCode 对应技能编码
 * @param title Lesson 标题
 * @param learningObjectives 学习目标
 * @param introContent 开场说明
 * @param keyConcepts 关键概念
 * @param examples 示例内容
 */
public record LearningLesson(
        String skillCode,
        String title,
        List<String> learningObjectives,
        String introContent,
        List<String> keyConcepts,
        List<String> examples) {

    public static LearningLesson from(LearningSkill skill) {
        return new LearningLesson(
                skill.code(),
                skill.name(),
                skill.learningObjectives(),
                skill.lessonIntro(),
                skill.keyConcepts(),
                skill.examples());
    }
}
