package com.example.agent.config;

import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

final class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(ChatOptions.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(ToolCallingChatOptions.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        register(hints, ThreadPoolExecutor.class, "shutdown");
    }

    private static void register(RuntimeHints hints, Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Native reflection method not found: " + type.getName() + "." + name, e);
        }
    }
}
