package com.db117.learnagent.learning.domain;

/**
 * Journey-specific 的掌握结果；掌握状态只由已验证的 Practice 证据驱动。
 *
 * @param mastered 是否已完成当前 LearnUnit
 */
public record Mastery(boolean mastered) {
}
