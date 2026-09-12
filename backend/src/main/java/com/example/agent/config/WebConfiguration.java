package com.example.agent.config;

import jakarta.annotation.PreDestroy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebFluxConfigurer {

    private final SimpleAsyncTaskExecutor blockingExecutor = new SimpleAsyncTaskExecutor("webflux-blocking-");

    public WebConfiguration() {
        blockingExecutor.setVirtualThreads(true);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void configureBlockingExecution(BlockingExecutionConfigurer configurer) {
        configurer.setExecutor(blockingExecutor);
    }

    @PreDestroy
    void closeBlockingExecutor() {
        blockingExecutor.close();
    }
}
