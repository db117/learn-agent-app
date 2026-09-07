package com.example.agent.config;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionChunk;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

final class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        register(hints, ChatCompletionChunk.Choice.Delta.class, "putAdditionalProperty", String.class, JsonValue.class);
        register(hints, ChatCompletionChunk.Choice.class, "putAdditionalProperty", String.class, JsonValue.class);
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
