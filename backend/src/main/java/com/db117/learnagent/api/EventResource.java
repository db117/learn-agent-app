package com.db117.learnagent.api;

import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.time.Instant;

@Path("/events")
public class EventResource {
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<RuntimeEvent> events() {
        return Multi.createBy().concatenating().streams(
                Multi.createFrom().item(RuntimeEvent.ready()),
                Multi.createFrom().ticks().every(Duration.ofSeconds(15))
                        .onItem().transform(ignored -> RuntimeEvent.heartbeat()));
    }

    /**
     * Runtime SSE 的安全事件摘要，不承载提示词、模型回答或敏感配置。
     *
     * @param type 稳定的事件类型
     * @param message 面向 UI 的短消息
     * @param timestamp 事件发生时间的 ISO-8601 文本
     */
    public record RuntimeEvent(String type, String message, String timestamp) {
        static RuntimeEvent ready() {
            return new RuntimeEvent("runtime.ready", "Runtime skeleton ready", Instant.now().toString());
        }

        static RuntimeEvent heartbeat() {
            return new RuntimeEvent("runtime.heartbeat", "Backend is alive", Instant.now().toString());
        }
    }
}
