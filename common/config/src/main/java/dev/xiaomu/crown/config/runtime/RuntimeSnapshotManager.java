package dev.xiaomu.crown.config.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前配置快照的原子持有者。
 *
 * <p>reload 会先独立构建完整候选快照；失败时引用保持不变，从而避免
 * config/titles/lang/gui 的半重载状态。</p>
 */
public final class RuntimeSnapshotManager {
    private final Path configRoot;
    private final CrownConfigurationBootstrap bootstrap;
    private final AtomicReference<RuntimeSnapshot> current =
            new AtomicReference<>();

    public RuntimeSnapshotManager(
            Path configRoot,
            CrownConfigurationBootstrap bootstrap
    ) {
        this.configRoot = Objects.requireNonNull(
                configRoot, "configRoot");
        this.bootstrap = Objects.requireNonNull(
                bootstrap, "bootstrap");
    }

    public ConfigurationLoadReport start() throws IOException {
        ConfigurationLoadReport report = bootstrap.initialize(configRoot);
        if (!current.compareAndSet(null, report.snapshot())) {
            throw new IllegalStateException(
                    "Crown configuration was already started");
        }
        return report;
    }

    public ConfigurationLoadReport reload() throws IOException {
        if (current.get() == null) {
            throw new IllegalStateException(
                    "Crown configuration is not started");
        }
        ConfigurationLoadReport candidate =
                bootstrap.initialize(configRoot);
        current.set(candidate.snapshot());
        return candidate;
    }

    /** 返回该快照管理器绑定的配置根目录。 */
    public Path configRoot() {
        return configRoot;
    }

    public RuntimeSnapshot requireSnapshot() {
        RuntimeSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException(
                    "Crown configuration is not available");
        }
        return snapshot;
    }

    public Optional<RuntimeSnapshot> snapshot() {
        return Optional.ofNullable(current.get());
    }

    public void clear() {
        current.set(null);
    }
}