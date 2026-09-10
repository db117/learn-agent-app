package com.example.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EchoTool {

    @Tool(name = "echo", description = "Echo text so the tutor can verify a tool call.")
    public Map<String, Object> echo(
            @ToolParam(name = "text", description = "Text to echo back.") String text) {
        return Map.of("echo", text);
    }
}
