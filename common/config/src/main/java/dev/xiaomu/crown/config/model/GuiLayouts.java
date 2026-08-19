package dev.xiaomu.crown.config.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Crown 全部必需 GUI 布局的不可变集合。 */
public record GuiLayouts(Map<String, GuiLayout> layouts) {
    public static final Set<String> REQUIRED = Set.of(
            "main",
            "shop",
            "warehouse",
            "purchase-confirm",
            "custom-confirm",
            "delete-confirm",
            "admin-shop",
            "admin-warehouse"
    );

    public GuiLayouts {
        Objects.requireNonNull(layouts, "layouts");
        var copy = new LinkedHashMap<String, GuiLayout>();
        layouts.forEach((id, layout) -> {
            Objects.requireNonNull(id, "layout ID");
            Objects.requireNonNull(layout, "layout");
            if (!id.equals(layout.id())) {
                throw new IllegalArgumentException(
                        "GUI map key and layout ID differ");
            }
            copy.put(id, layout);
        });
        if (!copy.keySet().equals(REQUIRED)) {
            throw new IllegalArgumentException(
                    "GUI layout set differs from required files");
        }
        layouts = Collections.unmodifiableMap(copy);
    }

    public GuiLayout require(String id) {
        GuiLayout layout = layouts.get(
                Objects.requireNonNull(id, "id"));
        if (layout == null) {
            throw new IllegalArgumentException(
                    "Unknown Crown GUI: " + id);
        }
        return layout;
    }

    public Optional<GuiLayout> find(String id) {
        return Optional.ofNullable(layouts.get(id));
    }
}