package com.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EchoTool {

    @Tool(name = "echo", description = "Echo text so the tutor can verify a tool call.")
    public Map<String, Object> echo(
            @ToolParam(description = "Text to echo back.") String text) {
        return Map.of("echo", text);
    }
}
