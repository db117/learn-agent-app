package com.example.agent.learning.path;

/** Path 节点状态；完成和跳过节点都会保留为历史。 */
public enum LearningPathItemStatus {
    /** 尚未轮到该 LearnUnit。 */
    PENDING,
    /** 当前唯一可操作的 LearnUnit。 */
    CURRENT,
    /** LearnUnit 已通过。 */
    COMPLETED,
    /** LearnUnit 被跳过，不代表掌握。 */
    SKIPPED
}
