package dev.xiaomu.crown.storage.async;

import dev.xiaomu.crown.storage.StorageException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 把同步 JDBC 操作隔离到有界工作队列。SQLite 使用单线程实例保证写入
 * 串行化；MySQL 可按连接池容量创建有限并发实例。
 */
public final class AsyncStorageExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AsyncStorageExecutor(
            String threadPrefix,
            int threads,
            int queueCapacity,
            Duration shutdownTimeout
    ) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        this.shutdownTimeout = Objects.requireNonNull(
                shutdownTimeout, "shutdownTimeout");
        if (threadPrefix.isBlank()
                || threads < 1 || threads > 100
                || queueCapacity < 1 || queueCapacity > 100_000
                || shutdownTimeout.isNegative()
                || shutdownTimeout.isZero()
                || shutdownTimeout.compareTo(
                Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "Invalid async storage executor settings");
        }

        executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
    }

    public static AsyncStorageExecutor sqlite(
            int queueCapacity,
            Duration shutdownTimeout
    ) {
        return new AsyncStorageExecutor(
                "Crown-SQLite", 1,
                queueCapacity, shutdownTimeout);
    }

    public static AsyncStorageExecutor mysql(
            int workerCount,
            int queueCapacity,
            Duration shutdownTimeout
    ) {
        return new AsyncStorageExecutor(
                "Crown-MySQL", workerCount,
                queueCapacity, shutdownTimeout);
    }

    public <T> CompletableFuture<T> submit(Supplier<? extends T> operation) {
        Objects.requireNonNull(operation, "operation");
        var future = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                try {
                    if (!future.isCancelled()) future.complete(operation.get());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            future.completeExceptionally(new StorageException(
                    closed.get() ? "Crown storage executor is closed"
                            : "Crown storage queue is full", failure));
        }
        return future;
    }
    public CompletableFuture<Void> run(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return submit(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(
                    shutdownTimeout.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(
                        shutdownTimeout.toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    throw new StorageException(
                            "Crown storage executor did not terminate");
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
            throw new StorageException(
                    "Interrupted while closing Crown storage executor",
                    exception);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + '-' + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, throwable) -> {
                // submit 会把操作异常写入对应 CompletableFuture。
            });
            return thread;
        };
    }
}
