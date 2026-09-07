package com.example.agent.learning.path;

/**
 * Journey Path 中的一个技能节点。
 *
 * @param id Path 节点主键
 * @param journeyId 所属 Journey
 * @param skillCode 节点对应的技能编码
 * @param sequence 当前 Path 中的展示顺序
 * @param status 节点状态；历史节点仍保留在 Path 中
 */
public record LearningPathItem(
        String id,
        String journeyId,
        String skillCode,
        int sequence,
        LearningPathItemStatus status) {
}
