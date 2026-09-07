package com.example.agent.learning.journey;

/** Journey 的生命周期状态。 */
public enum JourneyStatus {
    /** 已创建且仍可学习。 */
    ACTIVE,
    /** 所有 Path 节点都已关闭。 */
    COMPLETED,
    /** 用户主动归档。 */
    ARCHIVED
}
