package dev.xiaomu.crown.storage.backend;

import dev.xiaomu.crown.storage.StorageException;
import dev.xiaomu.crown.storage.model.StorageSummary;

import java.util.Objects;

/** 防止仅修改 type 后把已有数据静默切换到空后端。 */
public final class StorageSwitchGuard {
    private StorageSwitchGuard() {
    }

    public static void requireSafeActivation(
            StorageSummary previousBackend,
            StorageSummary configuredBackend,
            boolean protectEmptyTarget
    ) {
        Objects.requireNonNull(previousBackend, "previousBackend");
        Objects.requireNonNull(configuredBackend, "configuredBackend");
        if (protectEmptyTarget
                && previousBackend.hasBusinessData()
                && !configuredBackend.hasBusinessData()) {
            throw new StorageException(
                    "Refusing to activate an empty Crown storage target"
                            + " while the previous backend has data;"
                            + " run an explicit storage migration first");
        }
    }
}