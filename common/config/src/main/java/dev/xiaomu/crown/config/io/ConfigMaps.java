package dev.xiaomu.crown.config.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML 映射的受控复制和不可变化工具。 */
public final class ConfigMaps {
    private ConfigMaps() {
    }

    public static Map<String, Object> castMap(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Configuration mapping key must be a string");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    public static Map<String, Object> deepCopyMap(
            Map<String, Object> source
    ) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(key, deepCopy(value)));
        return result;
    }

    public static Map<String, Object> immutableDeepMap(
            Map<String, Object> source
    ) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) ->
                result.put(key, immutableDeepCopy(value)));
        return Collections.unmodifiableMap(result);
    }

    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyMap(castMap(map));
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>(list.size());
            for (Object child : list) {
                result.add(deepCopy(child));
            }
            return result;
        }
        return value;
    }

    private static Object immutableDeepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableDeepMap(castMap(map));
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(ConfigMaps::immutableDeepCopy)
                    .toList();
        }
        return value;
    }
}