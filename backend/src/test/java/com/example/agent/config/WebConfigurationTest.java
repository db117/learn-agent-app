package com.example.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigurationTest {

    @Test
    void sendsBlockingControllerWorkToTheDedicatedExecutor() throws Exception {
        WebConfiguration configuration = new WebConfiguration();
        try {
            CapturingConfigurer configurer = new CapturingConfigurer();
            configuration.configureBlockingExecution(configurer);

            String threadName = configurer.executor().submit(() -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);
            assertTrue(threadName.startsWith("webflux-blocking-"));
        } finally {
            configuration.closeBlockingExecutor();
        }
    }

    private static final class CapturingConfigurer extends BlockingExecutionConfigurer {

        private AsyncTaskExecutor executor() {
            return getExecutor();
        }
    }
}
