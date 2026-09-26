package dev.xiaomu.crown.runtime.lifecycle;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.config.runtime.ConfigurationLoadReport;
import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshotManager;
import dev.xiaomu.crown.runtime.concurrent.PlayerOperationQueue;
import dev.xiaomu.crown.runtime.display.PlayerTitleCache;
import dev.xiaomu.crown.runtime.purchase.UnifiedPurchaseService;
import dev.xiaomu.crown.runtime.wardrobe.TitleWardrobeService;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutor;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutorFactory;
import dev.xiaomu.crown.storage.backend.StorageBackend;
import dev.xiaomu.crown.storage.backend.StorageBackendFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Crown 服务端运行时的组合根。
 *
 * <p>按 config → storage backend → async executor →
 * purchase service 的顺序装配依赖，并在关闭时按相反顺序释放资源。启动
 * 全程 fail-fast：任一阶段失败都会回滚已经创建的资源，避免半初始化状态。</p>
 */
public final class CrownRuntime implements AutoCloseable {
    private final RuntimeSnapshotManager snapshots;
    private final StorageBackendFactory backendFactory;
    private final AsyncStorageExecutorFactory executorFactory;
    private final Path gameDirectory;
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final Object reloadMonitor = new Object();
    private CompletableFuture<ConfigurationLoadReport> reloadInFlight;

    public CrownRuntime(
            Path configRoot,
            Path gameDirectory
    ) {
        this(
                new RuntimeSnapshotManager(
                        Objects.requireNonNull(configRoot, "configRoot"),
                        new CrownConfigurationBootstrap()),
                new StorageBackendFactory(),
                new AsyncStorageExecutorFactory(),
                gameDirectory);
    }

    CrownRuntime(
            RuntimeSnapshotManager snapshots,
            StorageBackendFactory backendFactory,
            AsyncStorageExecutorFactory executorFactory,
            Path gameDirectory
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.backendFactory = Objects.requireNonNull(
                backendFactory, "backendFactory");
        this.executorFactory = Objects.requireNonNull(
                executorFactory, "executorFactory");
        this.gameDirectory = Objects.requireNonNull(
                gameDirectory, "gameDirectory");
    }

    /** 启动运行时并返回配置加载报告，供上层输出同步/迁移日志。 */
    public ConfigurationLoadReport start() throws IOException {
        if (active.get() != null) {
            throw new IllegalStateException(
                    "Crown runtime is already started");
        }
        ConfigurationLoadReport report = snapshots.start();
        Active started = assemble(report.snapshot());
        if (!active.compareAndSet(null, started)) {
            closeQuietly(started);
            throw new IllegalStateException(
                    "Crown runtime is already started");
        }
        return report;
    }

    /**
     * 重载商品、语言和 GUI 配置；数据库连接设置需要重启，
     * 拒绝在有购买操作运行时切换存储。
     */
    public synchronized ConfigurationLoadReport reload() throws IOException {
        require();
        StorageSettings storage = snapshot().storage();
        return snapshots.reload(candidate -> {
            if (!storage.equals(candidate.storage())) {
                throw new IllegalArgumentException("Storage settings require a server restart");
            }
        });
    }

    /**
     * 在提供的后台执行器上执行一次重载；重叠请求共享同一个结果。
     *
     * <p>调用方应在完成回调中自行切回平台主线程更新 GUI 或发送消息。</p>
     */
    public CompletableFuture<ConfigurationLoadReport> reloadAsync(
            Executor executor
    ) {
        Objects.requireNonNull(executor, "executor");
        synchronized (reloadMonitor) {
            CompletableFuture<ConfigurationLoadReport> current = reloadInFlight;
            if (current != null) {
                return current;
            }

            var created = CompletableFuture.supplyAsync(() -> {
                try {
                    return reload();
                } catch (IOException exception) {
                    throw new ReloadException(exception);
                }
            }, executor);
            reloadInFlight = created;
            created.whenComplete((ignored, failure) -> {
                synchronized (reloadMonitor) {
                    if (reloadInFlight == created) {
                        reloadInFlight = null;
                    }
                }
            });
            return created;
        }
    }

    public UnifiedPurchaseService purchaseService() {
        return require().purchaseService;
    }

    public StorageBackend storageBackend() {
        return require().backend;
    }

    public AsyncStorageExecutor storageExecutor() {
        return require().executor;
    }

    public PlayerOperationQueue playerOperations() {
        return require().playerOperations;
    }

    public PlayerTitleCache playerTitleCache() {
        return require().playerTitleCache;
    }

    public TitleWardrobeService wardrobe() {
        return require().wardrobe;
    }

    public RuntimeSnapshot snapshot() {
        return snapshots.requireSnapshot();
    }

    /** 返回 Crown 配置根目录，供受控配置编辑服务定位配置文件。 */
    public Path configRoot() {
        return snapshots.configRoot();
    }

    public boolean started() {
        return active.get() != null;
    }

    @Override
    public void close() {
        Active current = active.getAndSet(null);
        if (current != null) {
            closeQuietly(current);
        }
        snapshots.clear();
    }

    private Active assemble(RuntimeSnapshot snapshot) {
        StorageSettings storage = snapshot.storage();
        StorageBackend backend = null;
        AsyncStorageExecutor executor = null;
        PlayerOperationQueue playerOperations = null;
        boolean assembled = false;
        try {
            backend = backendFactory.openConfigured(
                    gameDirectory, storage);
            executor = executorFactory.create(storage);
            playerOperations = new PlayerOperationQueue();
            UnifiedPurchaseService purchaseService =
                    new UnifiedPurchaseService(
                            backend.repository(),
                            executor,
                            playerOperations);
            PlayerTitleCache playerTitleCache = new PlayerTitleCache(
                    backend.repository(), snapshots::requireSnapshot);
            TitleWardrobeService wardrobe =
                    new TitleWardrobeService(backend.repository());
            Active active = new Active(
                    backend, executor, playerOperations,
                    purchaseService, playerTitleCache, wardrobe);
            assembled = true;
            return active;
        } finally {
            if (!assembled) {
                closeQuietly(playerOperations);
                closeQuietly(executor);
                closeQuietly(backend);
            }
        }
    }

    private Active require() {
        Active current = active.get();
        if (current == null) {
            throw new IllegalStateException(
                    "Crown runtime is not started");
        }
        return current;
    }

    private static void closeQuietly(Active active) {
        if (active == null) {
            return;
        }
        // 关闭顺序与装配相反：先停止接收玩家操作，再耗尽存储队列，最后释放连接。
        closeQuietly(active.playerOperations);
        closeQuietly(active.executor);
        closeQuietly(active.backend);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // 关闭阶段吞掉异常，确保其余资源仍会被释放。
        }
    }

    private static final class ReloadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ReloadException(IOException cause) {
            super(cause);
        }
    }

    private record Active(
            StorageBackend backend,
            AsyncStorageExecutor executor,
            PlayerOperationQueue playerOperations,
            UnifiedPurchaseService purchaseService,
            PlayerTitleCache playerTitleCache,
            TitleWardrobeService wardrobe
    ) {
    }
}
