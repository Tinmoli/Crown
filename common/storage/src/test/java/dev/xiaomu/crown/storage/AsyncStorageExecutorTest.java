package dev.xiaomu.crown.storage;

import dev.xiaomu.crown.storage.async.AsyncStorageExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncStorageExecutorTest {
    @Test
    void sqliteExecutorSerializesOperationsInSubmissionOrder() {
        List<Integer> order =
                java.util.Collections.synchronizedList(
                        new ArrayList<>());
        try (AsyncStorageExecutor executor =
                     AsyncStorageExecutor.sqlite(
                             16, Duration.ofSeconds(5))) {
            var futures = new ArrayList<
                    java.util.concurrent.CompletableFuture<Integer>>();
            for (int index = 0; index < 10; index++) {
                int value = index;
                futures.add(executor.submit(() -> {
                    order.add(value);
                    return value;
                }));
            }
            for (int index = 0; index < futures.size(); index++) {
                assertEquals(index, futures.get(index).join());
            }
        }
        assertEquals(
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
                order);
    }

    @Test
    void propagatesOperationFailureAndRejectsAfterClose() {
        AsyncStorageExecutor executor =
                AsyncStorageExecutor.sqlite(
                        4, Duration.ofSeconds(5));
        try {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> executor.submit(() -> {
                        throw new IllegalStateException("expected");
                    }).join());
            assertTrue(failure.getCause()
                    instanceof IllegalStateException);
        } finally {
            executor.close();
        }

        CompletionException rejected = assertThrows(
                CompletionException.class,
                () -> executor.submit(() -> 1).join());
        assertTrue(rejected.getCause()
                instanceof StorageException);
    }

    @Test
    void maintenanceWaitsForAcceptedWorkAndRejectsNewWork()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();

        try (AsyncStorageExecutor executor =
                     AsyncStorageExecutor.mysql(
                             2, 16, Duration.ofSeconds(5))) {
            var existing = executor.run(() -> {
                started.countDown();
                await(release);
                assertEquals(0, order.getAndIncrement());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var maintenance = executor.submitMaintenance(() -> {
                assertEquals(1, order.getAndIncrement());
                return "done";
            });
            assertTrue(executor.maintenance());

            CompletionException rejected = assertThrows(
                    CompletionException.class,
                    () -> executor.submit(() -> "late").join());
            assertTrue(rejected.getCause()
                    instanceof StorageException);

            release.countDown();
            existing.join();
            assertEquals("done", maintenance.join());
            assertEquals(2, order.get());
            assertTrue(!executor.maintenance());
            assertEquals("resumed",
                    executor.submit(() -> "resumed").join());
        }
    }

    @Test
    void maintenanceFailureRestoresNormalSubmission() {
        try (AsyncStorageExecutor executor =
                     AsyncStorageExecutor.sqlite(
                             8, Duration.ofSeconds(5))) {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> executor.submitMaintenance(() -> {
                        throw new IllegalStateException("migration failed");
                    }).join());
            assertTrue(failure.getCause()
                    instanceof IllegalStateException);
            assertTrue(!executor.maintenance());
            assertEquals(7, executor.submit(() -> 7).join());
        }
    }

    @Test
    void failsClosedWhenBoundedQueueIsFull()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try (AsyncStorageExecutor executor =
                     AsyncStorageExecutor.sqlite(
                             1, Duration.ofSeconds(5))) {
            var running = executor.run(() -> {
                started.countDown();
                await(release);
                completed.incrementAndGet();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var queued = executor.run(
                    completed::incrementAndGet);
            CompletionException rejected = assertThrows(
                    CompletionException.class,
                    () -> executor.run(
                            completed::incrementAndGet).join());
            assertTrue(rejected.getCause()
                    instanceof StorageException);

            release.countDown();
            running.join();
            queued.join();
            assertEquals(2, completed.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Test worker interrupted", exception);
        }
    }
}