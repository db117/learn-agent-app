package com.db117.learnagent.agent.runtime;

import com.db117.learnagent.agent.application.TutorContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/** 将本次 Turn 的只读 Domain 上下文注入系统提示词，不写入 Agent State。 */
public final class TutorContextMiddleware implements MiddlewareBase {
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext runtimeContext, String systemPrompt) {
        var context = runtimeContext.get(TutorContext.class);
        if (context == null) {
            return Mono.just(systemPrompt);
        }
        return Mono.just(systemPrompt + "\n\n" + context.asSystemContext());
    }
}
