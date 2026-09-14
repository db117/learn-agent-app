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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        GenerationRunService.StartResult first = service.start(GenerationOperation.JOURNEY_OUTLINE, "user-target", run -> {
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

        GenerationRunService.StartResult duplicate = service.start(
                GenerationOperation.JOURNEY_OUTLINE, "user-target", ignored -> calls.incrementAndGet());
        assertFalse(duplicate.created());
        assertEquals(first.runId(), duplicate.runId());
        release.countDown();

        List<GenerationEvent> events = service.events(first.runId()).collectList().block(Duration.ofSeconds(1));
        assertEquals(1, calls.get());
        assertTrue(events.size() >= 3);
        assertEquals("JOURNEY_OUTLINE", events.get(0).operation());
        assertEquals(events.get(0).sequence() + 1, events.get(1).sequence());
        List<GenerationEvent> replay = service.events(first.runId(), events.get(0).sequence())
                .collectList().block(Duration.ofSeconds(1));
        assertEquals(events.subList(1, events.size()), replay);
        assertEquals(1, replay.stream().filter(event -> event.status().equals("COMPLETED")).count());
    }

    @Test
    void cancellationAndTimeoutEachProduceOneSafeTerminalEvent() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch handleReady = new CountDownLatch(1);
        AtomicReference<GenerationRunService.Run> cancelHandle = new AtomicReference<>();
        String cancelRunId = service.start(GenerationOperation.JOURNEY_OUTLINE, "cancel-target", run -> {
            cancelHandle.set(run);
            handleReady.countDown();
            try {
                cancelled.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }).runId();
        assertTrue(handleReady.await(1, TimeUnit.SECONDS));
        assertFalse(cancelHandle.get().terminal());
        cancelHandle.get().emit("agent_message", "CALLING_MODEL", "Agent", "authorization: secret-token", null);
        service.cancel(cancelRunId);
        List<GenerationEvent> cancelEvents = service.events(cancelRunId).collectList().block(Duration.ofSeconds(1));
        assertEquals(1, cancelEvents.stream().filter(event -> event.eventType().equals("cancelled")).count());
        assertTrue(cancelEvents.stream().anyMatch(event -> event.content().contains("[REDACTED]")));

        GenerationRunService timeoutService = new GenerationRunService(executor, Duration.ofMillis(50), Duration.ofMillis(10));
        try {
            String timeoutRunId = timeoutService.start(GenerationOperation.JOURNEY_OUTLINE, "timeout-target", ignored -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }).runId();
            List<GenerationEvent> timeoutEvents = timeoutService.events(timeoutRunId).collectList().block(Duration.ofSeconds(1));
            assertEquals(1, timeoutEvents.stream().filter(event -> event.eventType().equals("failed")).count());
            assertTrue(timeoutEvents.get(timeoutEvents.size() - 1).content().contains("180 秒")
                    || timeoutEvents.get(timeoutEvents.size() - 1).content().contains("超时"));
        } finally {
            timeoutService.close();
        }
    }

    @Test
    void lifecycleOwnsPersistenceCancellationRuleAndIdempotence() throws Exception {
        CountDownLatch handleReady = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<GenerationRunService.Run> handle = new AtomicReference<>();
        String runId = service.start(GenerationOperation.CODING_EVALUATION, "persist-target", run -> {
            handle.set(run);
            handleReady.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }).runId();

        assertTrue(handleReady.await(1, TimeUnit.SECONDS));
        handle.get().stage("PERSISTING");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.cancel(runId));
        assertEquals("Coding evaluation is being saved", error.getMessage());
        assertFalse(handle.get().terminal());

        handle.get().stage("CALLING_MODEL");
        service.cancel(runId);
        service.cancel(runId);
        release.countDown();

        List<GenerationEvent> events = service.events(runId).collectList().block(Duration.ofSeconds(1));
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("cancelled")).count());
    }

    @Test
    void doesNotTimeoutWhileWaitingForUserConfirmation() throws Exception {
        GenerationRunService waitingService = new GenerationRunService(
                executor, Duration.ofMillis(50), Duration.ofMillis(10));
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            AtomicReference<GenerationRunService.Run> handle = new AtomicReference<>();
            waitingService.start(GenerationOperation.JOURNEY_OUTLINE, "waiting-target", current -> {
                handle.set(current);
                current.stage("WAITING_CONFIRMATION");
                waiting.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            Thread.sleep(120);
            assertFalse(handle.get().terminal());
        } finally {
            waitingService.close();
        }
    }

    @Test
    void keepsAnActiveModelStreamAliveBeyondTheIdleTimeout() throws Exception {
        GenerationRunService streamService = new GenerationRunService(
                executor, Duration.ofMillis(50), Duration.ofSeconds(1));
        try {
            String runId = streamService.start(GenerationOperation.JOURNEY_OUTLINE, "stream-target", current -> {
                current.stage("CALLING_MODEL");
                for (int index = 0; index < 4; index++) {
                    current.modelText("chunk-" + index);
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                current.complete("完成。", null, "JOURNEY", current.id());
            }).runId();

            List<GenerationEvent> events = streamService.events(runId)
                    .collectList()
                    .block(Duration.ofSeconds(1));

            assertEquals("COMPLETED", events.get(events.size() - 1).status());
            assertEquals(4, events.stream().filter(event -> event.eventType().equals("model_delta")).count());
        } finally {
            streamService.close();
        }
    }
}
