package dev.xiaomu.crown.config.edit;

import dev.xiaomu.crown.config.io.AtomicFiles;
import dev.xiaomu.crown.config.io.SafeYaml;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.config.parse.TitleCatalogParser;
import dev.xiaomu.crown.config.sync.TemplateComments;
import dev.xiaomu.crown.domain.catalog.DefinitionId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对 titles.yml 执行经过完整目录校验的原子编辑。
 *
 * <p>同一文件的编辑会串行执行。候选配置必须先通过正式
 * {@link TitleCatalogParser} 验证才会落盘；若后续内部重载失败，则恢复
 * 原文件并再次加载旧配置，防止磁盘和运行时状态分裂。</p>
 */
public final class TitleCatalogEditor {
    private static final ConcurrentHashMap<Path, Object> LOCKS =
            new ConcurrentHashMap<>();

    private final SafeYaml yaml;
    private final TitleCatalogParser parser;

    public TitleCatalogEditor() {
        this(new SafeYaml(), new TitleCatalogParser());
    }

    TitleCatalogEditor(SafeYaml yaml, TitleCatalogParser parser) {
        this.yaml = Objects.requireNonNull(yaml, "yaml");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public void create(
            Path titlesFile,
            CoreSettings.Safety safety,
            String id,
            ReloadAction reload
    ) throws IOException {
        String validId = validateId(id);
        edit(titlesFile, safety, root -> {
            Map<String, Object> titles = titles(root);
            if (titles.containsKey(validId)) {
                throw new IllegalArgumentException(
                        "Title already exists: " + validId);
            }
            titles.put(validId, draft(validId));
        }, reload);
    }

    public void delete(
            Path titlesFile,
            CoreSettings.Safety safety,
            String id,
            ReloadAction reload
    ) throws IOException {
        String validId = validateId(id);
        edit(titlesFile, safety, root -> {
            if (titles(root).remove(validId) == null) {
                throw new IllegalArgumentException(
                        "Unknown title: " + validId);
            }
        }, reload);
    }

    public void set(
            Path titlesFile,
            CoreSettings.Safety safety,
            String id,
            String field,
            Object value,
            ReloadAction reload
    ) throws IOException {
        var single = new LinkedHashMap<String, Object>();
        single.put(Objects.requireNonNull(field, "field"), value);
        setAll(
                titlesFile,
                safety,
                id,
                single,
                reload);
    }

    /**
     * 在同一个候选目录中原子修改多个字段。用于支付方式、有效期等必须
     * 联动更新的字段，避免产生无法通过正式解析器的中间状态。
     */
    public void setAll(
            Path titlesFile,
            CoreSettings.Safety safety,
            String id,
            Map<String, Object> fields,
            ReloadAction reload
    ) throws IOException {
        String validId = validateId(id);
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one title field is required");
        }
        var copied = new LinkedHashMap<String, Object>();
        fields.forEach((field, value) -> copied.put(
                Objects.requireNonNull(field, "field"), value));
        edit(titlesFile, safety, root -> {
            Map<String, Object> definition =
                    definition(root, validId);
            copied.forEach((field, value) ->
                    putPath(definition, field, value));
        }, reload);
    }

    public void edit(
            Path titlesFile,
            CoreSettings.Safety safety,
            Mutation mutation,
            ReloadAction reload
    ) throws IOException {
        Objects.requireNonNull(titlesFile, "titlesFile");
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(reload, "reload");

        Path normalized = titlesFile.toAbsolutePath().normalize();
        Object lock = LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            editLocked(normalized, safety, mutation, reload);
        }
    }

    private void editLocked(
            Path file,
            CoreSettings.Safety safety,
            Mutation mutation,
            ReloadAction reload
    ) throws IOException {
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> root = yaml.loadMap(original);
        String normalizedBefore = yaml.dump(root);
        mutation.apply(root);

        // 使用生产环境同一个解析器验证全部商品，而非只验证被修改字段。
        parser.parse(root, safety);
        String normalizedAfter = yaml.dump(root);
        if (normalizedAfter.equals(normalizedBefore)) {
            throw new IllegalArgumentException(
                    "The requested title change has no effect");
        }

        // 使用当前文件作为注释模板，规范化字段的同时恢复现有标准注释。
        String candidate = TemplateComments.apply(
                original, normalizedAfter);
        AtomicFiles.writeUtf8Atomically(file, candidate);
        try {
            reload.run();
        } catch (Exception failure) {
            IOException rollbackFailure = null;
            try {
                AtomicFiles.writeUtf8Atomically(file, original);
                reload.run();
            } catch (Exception rollback) {
                rollbackFailure = asIOException(
                        "Failed to restore title catalog", rollback);
            }
            IOException result = asIOException(
                    "Failed to reload edited title catalog", failure);
            if (rollbackFailure != null) {
                result.addSuppressed(rollbackFailure);
            }
            throw result;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> titles(Map<String, Object> root) {
        Object configured = root.get("titles");
        if (!(configured instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "titles.yml root must contain a titles mapping");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definition(
            Map<String, Object> root,
            String id
    ) {
        Object configured = titles(root).get(id);
        if (!(configured instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Unknown title: " + id);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static void putPath(
            Map<String, Object> root,
            String path,
            Object value
    ) {
        if (!path.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*")) {
            throw new IllegalArgumentException(
                    "Invalid title field path: " + path);
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object child = current.get(parts[index]);
            if (!(child instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "Unknown title field: " + path);
            }
            current = (Map<String, Object>) map;
        }
        String leaf = parts[parts.length - 1];
        if (!current.containsKey(leaf)) {
            throw new IllegalArgumentException(
                    "Unknown title field: " + path);
        }
        current.put(leaf, value);
    }

    private static Map<String, Object> draft(String id) {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("enabled", false);
        definition.put("visible", false);
        definition.put("category", "normal");
        definition.put("text", id);
        definition.put("prefix", "");
        definition.put("suffix", "");
        definition.put("icon", "minecraft:name_tag");
        definition.put("description", new ArrayList<>());

        var duration = new LinkedHashMap<String, Object>();
        duration.put("days", 0);
        definition.put("duration", duration);
        var requirement = new LinkedHashMap<String, Object>();
        requirement.put("permission", "");
        definition.put("requirement", requirement);
        var sale = new LinkedHashMap<String, Object>();
        sale.put("starts-at", null);
        sale.put("ends-at", null);
        sale.put("global-stock", -1L);
        sale.put("per-player-limit", -1);
        definition.put("sale", sale);

        var paymentOptions = new LinkedHashMap<String, Object>();
        paymentOptions.put("mint", Map.of("price", "1000.00"));
        paymentOptions.put("title-coin", Map.of("price", "50"));
        definition.put("payment-options", paymentOptions);
        return definition;
    }

    private static String validateId(String id) {
        Objects.requireNonNull(id, "id");
        DefinitionId.of(id);
        return id;
    }

    private static IOException asIOException(
            String message,
            Exception failure
    ) {
        return failure instanceof IOException io
                ? new IOException(message, io)
                : new IOException(message, failure);
    }

    @FunctionalInterface
    public interface Mutation {
        void apply(Map<String, Object> root);
    }

    @FunctionalInterface
    public interface ReloadAction {
        void run() throws Exception;
    }
}