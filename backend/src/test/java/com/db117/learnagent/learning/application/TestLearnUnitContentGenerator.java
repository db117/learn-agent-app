package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.LearnUnit;
import io.agentscope.core.model.Model;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/** 后端集成测试的课程内容模型替身；验证内容只在进入当前 LearnUnit 时生成。 */
@Mock
@ApplicationScoped
public class TestLearnUnitContentGenerator extends LearnUnitContentGenerator {
    public TestLearnUnitContentGenerator() {
        super((Model) null);
    }

    @Override
    public String generate(LearnUnit unit) {
        return """
                ## Concept
                模型生成的 Concept：%s
                
                ## Example
                export const answer: number = 42;
                
                ## Practice
                模型生成的 Practice：完成「%s」对应的练习。
                """.formatted(unit.objective(), unit.title()).strip();
    }
}
