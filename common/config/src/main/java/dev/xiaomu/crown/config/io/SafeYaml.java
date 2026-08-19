package dev.xiaomu.crown.config.io;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用受限安全模式读取只含映射、列表和标量的 YAML。 */
public final class SafeYaml {
    public static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_DEPTH = 50;
    private static final int MAX_LIST_ELEMENTS = 10_000;

    private final Load loader;
    private final Dump dumper;

    public SafeYaml() {
        LoadSettings loadSettings = LoadSettings.builder()
                .setLabel("Crown YAML")
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setMaxAliasesForCollections(20)
                .setCodePointLimit(MAX_FILE_BYTES)
                .build();
        DumpSettings dumpSettings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(2)
                .setIndicatorIndent(2)
                .setDumpComments(false)
                .build();
        loader = new Load(loadSettings);
        dumper = new Dump(dumpSettings);
    }

    public Map<String, Object> loadMap(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(StandardCharsets.UTF_8).length
                > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("YAML file is too large");
        }

        Object loaded;
        try {
            loaded = loader.loadFromString(source);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Invalid Crown YAML", exception);
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "YAML document root must be a mapping");
        }
        return normalizeMap(map, 0);
    }

    public String dump(Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        String output = dumper.dumpToString(values);
        return output.endsWith("\n") ? output : output + '\n';
    }

    private static Map<String, Object> normalizeMap(
            Map<?, ?> source,
            int depth
    ) {
        requireDepth(depth);
        var normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException(
                        "YAML mapping keys must be non-empty strings");
            }
            Object previous = normalized.put(
                    key, normalizeValue(entry.getValue(), depth + 1));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate YAML key: " + key);
            }
        }
        return normalized;
    }

    private static Object normalizeValue(Object value, int depth) {
        requireDepth(depth);
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, depth);
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_LIST_ELEMENTS) {
                throw new IllegalArgumentException("YAML list is too large");
            }
            var copy = new ArrayList<Object>(list.size());
            for (Object element : list) {
                copy.add(normalizeValue(element, depth + 1));
            }
            return copy;
        }
        if (value == null || value instanceof String
                || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        throw new IllegalArgumentException(
                "Unsupported YAML value type: "
                        + value.getClass().getName());
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "YAML is nested too deeply");
        }
    }
}