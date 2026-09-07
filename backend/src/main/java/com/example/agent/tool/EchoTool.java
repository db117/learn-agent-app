package com.example.agent.tool;

import com.google.adk.tools.Annotations;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EchoTool {

    @Annotations.Schema(name = "echo", description = "Echo text so the tutor can verify a tool call.")
    public Map<String, Object> echo(
            @Annotations.Schema(name = "text", description = "Text to echo back.") String text) {
        return Map.of("echo", text);
    }
}
