package com.example.agent.learning.path;

/** Path 节点状态；完成和跳过节点都会保留为历史。 */
public enum LearningPathItemStatus {
    /** 尚未轮到该技能。 */
    PENDING,
    /** 当前唯一可操作的技能。 */
    CURRENT,
    /** 技能已通过。 */
    COMPLETED,
    /** 技能被跳过，不代表掌握。 */
    SKIPPED
}
