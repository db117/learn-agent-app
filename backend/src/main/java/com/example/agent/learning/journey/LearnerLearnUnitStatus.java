package com.example.agent.learning.journey;

/** 学习者在单个 LearnUnit 上的状态。 */
public enum LearnerLearnUnitStatus {
    /** 前置 LearnUnit 尚未完成，暂不可学习。 */
    LOCKED,
    /** 尚未开始学习。 */
    READY,
    /** 正在学习或上一次评估未通过。 */
    LEARNING,
    /** 正在进行评估。 */
    ASSESSING,
    /** 已通过评估。 */
    PASSED,
    /** 用户跳过；不等同于通过。 */
    SKIPPED
}
