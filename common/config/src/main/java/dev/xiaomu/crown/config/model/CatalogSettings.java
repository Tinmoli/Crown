package dev.xiaomu.crown.config.model;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** titles.yml 中按文件顺序保存的不可变商品目录。 */
public record CatalogSettings(
        Map<DefinitionId, TitleDefinition> definitions
) {
    public CatalogSettings {
        Objects.requireNonNull(definitions, "definitions");
        var copy = new LinkedHashMap<DefinitionId, TitleDefinition>();
        definitions.forEach((id, definition) -> {
            Objects.requireNonNull(id, "definition ID");
            Objects.requireNonNull(definition, "definition");
            if (!id.equals(definition.id())) {
                throw new IllegalArgumentException(
                        "Catalog key and definition ID differ");
            }
            if (copy.put(id, definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate title definition ID: " + id);
            }
        });
        definitions = Collections.unmodifiableMap(copy);
    }

    public Optional<TitleDefinition> find(DefinitionId id) {
        return Optional.ofNullable(definitions.get(
                Objects.requireNonNull(id, "id")));
    }

    public Optional<TitleDefinition> find(String id) {
        try {
            return find(DefinitionId.of(id));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}