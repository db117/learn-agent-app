package com.example.agent.config;

import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;

final class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
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
