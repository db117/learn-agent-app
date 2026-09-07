package com.example.agent.learning.catalog;

/**
 * 学习语言目录项，对应 {@code learning_language} 表中的一行。
 *
 * @param id 数据库中的稳定主键
 * @param code 语言唯一编码，例如 {@code typescript}
 * @param name 面向学习者显示的名称
 * @param description 语言或课程的简介
 * @param enabled 是否允许创建新的 Journey
 */
public record LearningLanguage(
        String id,
        String code,
        String name,
        String description,
        boolean enabled) {
}
