package dev.xiaomu.crown.runtime.concurrent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 每玩家无阻塞异步互斥队列。不同玩家可并行，同一玩家的业务严格串行。
 */
public final class PlayerOperationQueue implements AutoCloseable {
    private final Object monitor = new Object();
    private final Map<UUID, CompletableFuture<Void>> tails =
            new HashMap<>();
    private boolean closed;

    public <T> CompletionStage<T> submit(
            UUID playerId,
            Supplier<? extends CompletionStage<T>> operation
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operation, "operation");

        CompletableFuture<Void> predecessor;
        CompletableFuture<Void> ownTail =
                new CompletableFuture<>();
        synchronized (monitor) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Player operation queue is closed"));
            }
            predecessor = tails.put(playerId, ownTail);
        }
        if (predecessor == null) {
            predecessor = CompletableFuture.completedFuture(null);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        predecessor.handle((ignored, failure) -> null)
                .thenCompose(ignored -> invoke(operation))
                .whenComplete((value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(failure);
                    }
                    ownTail.complete(null);
                    synchronized (monitor) {
                        tails.remove(playerId, ownTail);
                    }
                });
        return result;
    }

    public int activePlayers() {
        synchronized (monitor) {
            return tails.size();
        }
    }

    public boolean closed() {
        synchronized (monitor) {
            return closed;
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
        }
    }

    private static <T> CompletionStage<T> invoke(
            Supplier<? extends CompletionStage<T>> operation
    ) {
        try {
            return Objects.requireNonNull(
                    operation.get(), "operation stage");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}