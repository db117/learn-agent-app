package com.example.agent.learning.journey;

/** 学习者在单个技能上的状态。 */
public enum LearnerSkillStatus {
    /** 前置技能尚未完成，暂不可学习。 */
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
