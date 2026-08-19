package dev.xiaomu.crown.storage.async;

import dev.xiaomu.crown.config.model.StorageSettings;

import java.time.Duration;
import java.util.Objects;

/** 根据当前存储类型创建与后端容量匹配的有界执行器。 */
public final class AsyncStorageExecutorFactory {
    private static final int SQLITE_QUEUE_CAPACITY = 4_096;
    private static final int MYSQL_QUEUE_PER_WORKER = 512;
    private static final Duration SHUTDOWN_TIMEOUT =
            Duration.ofSeconds(30);

    public AsyncStorageExecutor create(StorageSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (settings.type() == StorageSettings.Type.SQLITE) {
            return AsyncStorageExecutor.sqlite(
                    SQLITE_QUEUE_CAPACITY,
                    SHUTDOWN_TIMEOUT);
        }

        int workers = settings.mysql().pool().maximumSize();
        int queueCapacity = Math.multiplyExact(
                workers, MYSQL_QUEUE_PER_WORKER);
        return AsyncStorageExecutor.mysql(
                workers, queueCapacity, SHUTDOWN_TIMEOUT);
    }
}