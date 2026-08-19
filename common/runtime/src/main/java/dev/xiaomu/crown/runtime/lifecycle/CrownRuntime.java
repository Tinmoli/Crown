package dev.xiaomu.crown.runtime.lifecycle;

import dev.xiaomu.crown.config.model.StorageSettings;
import dev.xiaomu.crown.config.runtime.ConfigurationLoadReport;
import dev.xiaomu.crown.config.runtime.CrownConfigurationBootstrap;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import dev.xiaomu.crown.config.runtime.RuntimeSnapshotManager;
import dev.xiaomu.crown.runtime.concurrent.PlayerOperationQueue;
import dev.xiaomu.crown.runtime.economy.MintPaymentGateway;
import dev.xiaomu.crown.runtime.display.PlayerTitleCache;
import dev.xiaomu.crown.runtime.purchase.UnifiedPurchaseService;
import dev.xiaomu.crown.runtime.wardrobe.TitleWardrobeService;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutor;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutorFactory;
import dev.xiaomu.crown.storage.backend.StorageBackend;
import dev.xiaomu.crown.storage.backend.StorageBackendFactory;
import dev.xiaomu.crown.storage.migration.JdbcStorageMigrator;
import dev.xiaomu.crown.storage.migration.StorageMigrationReport;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.snapshot.SqliteSnapshotManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Crown 服务端运行时的组合根。
 *
 * <p>按 config → storage backend → async executor → mint gateway →
 * purchase service 的顺序装配依赖，并在关闭时按相反顺序释放资源。启动
 * 全程 fail-fast：任一阶段失败都会回滚已经创建的资源，避免半初始化状态。</p>
 */
public final class CrownRuntime implements AutoCloseable {
    private final RuntimeSnapshotManager snapshots;
    private final StorageBackendFactory backendFactory;
    private final AsyncStorageExecutorFactory executorFactory;
    private final MintPaymentGateway mint;
    private final Path gameDirectory;
    private final JdbcStorageMigrator storageMigrator =
            new JdbcStorageMigrator();
    private final SqliteSnapshotManager snapshotManager =
            new SqliteSnapshotManager();
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final Object reloadMonitor = new Object();
    private CompletableFuture<ConfigurationLoadReport> reloadInFlight;

    public CrownRuntime(
            Path configRoot,
            Path gameDirectory,
            MintPaymentGateway mint
    ) {
        this(
                new RuntimeSnapshotManager(
                        Objects.requireNonNull(configRoot, "configRoot"),
                        new CrownConfigurationBootstrap()),
                new StorageBackendFactory(),
                new AsyncStorageExecutorFactory(),
                mint,
                gameDirectory);
    }

    CrownRuntime(
            RuntimeSnapshotManager snapshots,
            StorageBackendFactory backendFactory,
            AsyncStorageExecutorFactory executorFactory,
            MintPaymentGateway mint,
            Path gameDirectory
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.backendFactory = Objects.requireNonNull(
                backendFactory, "backendFactory");
        this.executorFactory = Objects.requireNonNull(
                executorFactory, "executorFactory");
        this.mint = Objects.requireNonNull(mint, "mint");
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
     * 重载配置。存储类型未变化时保留后端与执行器；类型变化时构建新后端、
     * 校验成功后再原子替换，并在旧引用上执行优雅关闭。
     */
    public synchronized ConfigurationLoadReport reload() throws IOException {
        Active current = require();
        if (current.executor.maintenance()) {
            throw new IOException(
                    "Crown storage maintenance is running");
        }
        ConfigurationLoadReport report = snapshots.reload();
        StorageSettings.Type nextType =
                report.snapshot().storage().type();
        if (nextType == current.backend.type()) {
            return report;
        }

        Active next = assemble(report.snapshot());
        if (!active.compareAndSet(current, next)) {
            closeQuietly(next);
            throw new IllegalStateException(
                    "Crown runtime changed during reload");
        }
        closeQuietly(current);
        return report;
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

    /** 当前后端执行兼容 Schema 初始化/升级，全程位于独占维护模式。 */
    public CompletableFuture<Integer> migrateSchema(String actor) {
        Objects.requireNonNull(actor, "actor");
        Active current = require();
        StorageSettings settings = snapshot().storage();
        return current.executor.submitMaintenance(() -> {
            Instant startedAt = Instant.now();
            Path backup = snapshotSqliteIfConfigured(
                    current.backend, settings, startedAt);
            int version = current.backend.repository().initializeSchema();
            String details = "{\"backend\":\""
                    + current.backend.type().name().toLowerCase(
                            java.util.Locale.ROOT)
                    + "\",\"schemaVersion\":" + version
                    + ",\"snapshot\":\""
                    + json(backup == null ? "" : backup.toString())
                    + "\"}";
            current.backend.repository().appendAudit(new AuditRecord(
                    0, actor, "admin_storage_migrate_schema",
                    null, "schema", details, Instant.now()));
            return version;
        });
    }

    /**
     * 将当前后端完整复制到 storage.yml 中指定类型的空目标。成功后不会自动
     * 切换；管理员仍需修改 type 并重载或重启。
     */
    public CompletableFuture<StorageMigrationReport> migrateStorage(
            StorageSettings.Type targetType,
            String actor
    ) {
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(actor, "actor");
        Active current = require();
        if (current.backend.type() == targetType) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Target storage is already active"));
        }
        StorageSettings settings = snapshot().storage();
        return current.executor.submitMaintenance(() -> {
            Instant startedAt = Instant.now();
            Path backup = snapshotSqliteIfConfigured(
                    current.backend, settings, startedAt);
            try (StorageBackend target = backendFactory.open(
                    gameDirectory, settings, targetType)) {
                StorageMigrationReport report = storageMigrator.migrate(
                        current.backend,
                        target,
                        settings.migration().verification(),
                        startedAt);
                String details = "{\"source\":\""
                        + report.sourceType().name().toLowerCase(
                                java.util.Locale.ROOT)
                        + "\",\"target\":\""
                        + report.targetType().name().toLowerCase(
                                java.util.Locale.ROOT)
                        + "\",\"copiedRows\":"
                        + report.totalCopiedRows()
                        + ",\"durationMillis\":"
                        + report.duration().toMillis()
                        + ",\"snapshot\":\""
                        + json(backup == null ? "" : backup.toString())
                        + "\"}";
                current.backend.repository().appendAudit(new AuditRecord(
                        0, actor, "admin_storage_migrate_backend",
                        null,
                        report.sourceType().name().toLowerCase(
                                java.util.Locale.ROOT)
                                + "-to-"
                                + report.targetType().name().toLowerCase(
                                        java.util.Locale.ROOT),
                        details,
                        Instant.now()));
                return report;
            }
        });
    }

    public boolean storageMaintenance() {
        return require().executor.maintenance();
    }

    private Path snapshotSqliteIfConfigured(
            StorageBackend backend,
            StorageSettings settings,
            Instant now
    ) {
        if (backend.type() != StorageSettings.Type.SQLITE
                || !settings.sqlite().snapshotBeforeMigration()) {
            return null;
        }
        Path database = backend.sqliteDatabase().orElseThrow();
        Path parent = database.getParent();
        if (parent == null) {
            throw new IllegalStateException(
                    "SQLite database has no parent directory");
        }
        return snapshotManager.create(
                database,
                backend.connections(),
                parent.resolve("snapshots"),
                settings.sqlite().maximumSnapshots(),
                now);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
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
                            mint,
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