package com.example.agent.learning.generation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRunServiceTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private GenerationRunService service = new GenerationRunService(executor, Duration.ofSeconds(2), Duration.ofMillis(25));

    @AfterEach
    void close() {
        service.close();
        executor.shutdownNow();
    }

    @Test
    void deduplicatesActiveTargetAndReplaysOnlyEventsAfterLastSequence() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        GenerationRunService.StartResult first = service.start("JOURNEY_OUTLINE", "user-target", run -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                run.emit("validation", "VALIDATING", "Agent", "结构校验通过。", null);
                run.complete("完成。", null, "JOURNEY", run.id());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        GenerationRunService.StartResult duplicate = service.start("JOURNEY_OUTLINE", "user-target", ignored -> calls.incrementAndGet());
        assertFalse(duplicate.created());
        assertSame(first.run(), duplicate.run());
        release.countDown();

        List<GenerationEvent> events = service.events(first.run().id()).collectList().block(Duration.ofSeconds(1));
        assertEquals(1, calls.get());
        assertTrue(events.size() >= 3);
        assertEquals(events.get(0).sequence() + 1, events.get(1).sequence());
        List<GenerationEvent> replay = service.events(first.run().id(), events.get(0).sequence())
                .collectList().block(Duration.ofSeconds(1));
        assertEquals(events.subList(1, events.size()), replay);
        assertEquals(1, replay.stream().filter(event -> event.status().equals("COMPLETED")).count());
    }

    @Test
    void cancellationAndTimeoutEachProduceOneSafeTerminalEvent() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        GenerationRunService.Run cancelRun = service.start("JOURNEY_OUTLINE", "cancel-target", run -> {
            try {
                cancelled.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }).run();
        assertFalse(cancelRun.terminal());
        cancelRun.cancel("authorization: secret-token");
        List<GenerationEvent> cancelEvents = service.events(cancelRun.id()).collectList().block(Duration.ofSeconds(1));
        assertEquals(1, cancelEvents.stream().filter(event -> event.eventType().equals("cancelled")).count());
        assertTrue(cancelEvents.get(cancelEvents.size() - 1).content().contains("[REDACTED]"));

        GenerationRunService timeoutService = new GenerationRunService(executor, Duration.ofMillis(50), Duration.ofMillis(10));
        try {
            GenerationRunService.Run timeoutRun = timeoutService.start("JOURNEY_OUTLINE", "timeout-target", ignored -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }).run();
            List<GenerationEvent> timeoutEvents = timeoutService.events(timeoutRun.id()).collectList().block(Duration.ofSeconds(1));
            assertEquals(1, timeoutEvents.stream().filter(event -> event.eventType().equals("failed")).count());
            assertTrue(timeoutEvents.get(timeoutEvents.size() - 1).content().contains("180 秒")
                    || timeoutEvents.get(timeoutEvents.size() - 1).content().contains("超时"));
        } finally {
            timeoutService.close();
        }
    }
}
