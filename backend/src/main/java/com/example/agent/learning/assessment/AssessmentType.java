package com.example.agent.learning.assessment;

/** Assessment 的业务用途。 */
public enum AssessmentType {
    /** Journey 初始诊断，按技能拆分结果并生成 Path。 */
    DIAGNOSTIC,
    /** 单个技能的学习评估，可重复 Retry。 */
    SKILL
}
