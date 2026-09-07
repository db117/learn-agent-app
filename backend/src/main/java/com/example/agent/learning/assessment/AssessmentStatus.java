package com.example.agent.learning.assessment;

/** Assessment 的生命周期状态。 */
public enum AssessmentStatus {
    /** 已创建题集，尚未开始。 */
    CREATED,
    /** 存在一个未提交的 Attempt。 */
    IN_PROGRESS,
    /** 已至少提交一次并完成。 */
    COMPLETED
}
