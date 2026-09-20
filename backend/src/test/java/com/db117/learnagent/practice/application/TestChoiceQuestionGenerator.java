package com.db117.learnagent.practice.application;

import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.practice.domain.ChoiceOption;
import com.db117.learnagent.practice.domain.ChoiceQuestion;
import io.agentscope.core.model.Model;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** 后端 REST 集成测试的模型替身；真实浏览器测试使用 HTTP 模型桩验证模型调用边界。 */
@Mock
@ApplicationScoped
public class TestChoiceQuestionGenerator extends ChoiceQuestionGenerator {
    public TestChoiceQuestionGenerator() {
        super((Model) null);
    }

    @Override
    public ChoiceQuestion generate(LearnUnit unit) {
        return new ChoiceQuestion(
                "模型生成题目：关于「" + unit.title() + "」哪项说法正确？",
                List.of(
                        new ChoiceOption("a", unit.objective()),
                        new ChoiceOption("b", "只修改无关的界面样式。"),
                        new ChoiceOption("c", "跳过当前学习目标。"),
                        new ChoiceOption("d", "删除本单元的练习。")),
                "a");
    }
}
