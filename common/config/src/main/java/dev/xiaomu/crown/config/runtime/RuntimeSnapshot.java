package dev.xiaomu.crown.config.runtime;

import dev.xiaomu.crown.config.lang.LanguageCatalog;
import dev.xiaomu.crown.config.model.CatalogSettings;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.model.GuiLayouts;
import dev.xiaomu.crown.config.model.StorageSettings;

import java.time.Instant;
import java.util.Objects;

/** 一次完整验证后可原子发布的不可变 Crown 配置快照。 */
public record RuntimeSnapshot(
        CoreSettings core,
        CatalogSettings catalog,
        StorageSettings storage,
        GuiLayouts gui,
        LanguageCatalog languages,
        Instant builtAt
) {
    public RuntimeSnapshot {
        core = Objects.requireNonNull(core, "core");
        catalog = Objects.requireNonNull(catalog, "catalog");
        storage = Objects.requireNonNull(storage, "storage");
        gui = Objects.requireNonNull(gui, "gui");
        languages = Objects.requireNonNull(languages, "languages");
        builtAt = Objects.requireNonNull(builtAt, "builtAt");
    }
}