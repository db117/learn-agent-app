package com.example.agent.learning.assessment;

/** Assessment 的业务用途。 */
public enum AssessmentType {
    /** Journey 初始诊断，按 LearnUnit 拆分结果并生成 Path。 */
    DIAGNOSTIC,
    /** 单个 LearnUnit 的学习评估，可重复 Retry。 */
    LEARN_UNIT
}
