package dev.xiaomu.crown.config.io;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 对嵌套 YAML 映射执行严格类型读取。 */
public final class YamlValues {
    private YamlValues() {
    }

    public static Object require(
            Map<String, Object> root,
            String dottedPath
    ) {
        Object value = find(root, dottedPath);
        if (value == Missing.VALUE) {
            throw new ConfigValueException(
                    dottedPath, "required value is missing");
        }
        return value;
    }

    public static Object findNullable(
            Map<String, Object> root,
            String dottedPath
    ) {
        Object value = find(root, dottedPath);
        return value == Missing.VALUE ? null : value;
    }

    public static String string(
            Map<String, Object> root,
            String path
    ) {
        Object value = require(root, path);
        if (!(value instanceof String text)) {
            throw type(path, "string", value);
        }
        return text;
    }

    public static String nonBlankString(
            Map<String, Object> root,
            String path
    ) {
        String value = string(root, path);
        if (value.isBlank()) {
            throw new ConfigValueException(path, "must not be blank");
        }
        return value;
    }

    public static boolean bool(
            Map<String, Object> root,
            String path
    ) {
        Object value = require(root, path);
        if (!(value instanceof Boolean flag)) {
            throw type(path, "boolean", value);
        }
        return flag;
    }

    public static int integer(
            Map<String, Object> root,
            String path
    ) {
        long value = longValue(root, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConfigValueException(path, "integer is out of range");
        }
        return (int) value;
    }

    public static long longValue(
            Map<String, Object> root,
            String path
    ) {
        Object value = require(root, path);
        if (!(value instanceof Number number)
                || value instanceof Float
                || value instanceof Double) {
            throw type(path, "integer", value);
        }
        try {
            return new BigDecimal(number.toString())
                    .toBigIntegerExact()
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigValueException(
                    path, "integer is out of range", exception);
        }
    }

    public static Map<String, Object> map(
            Map<String, Object> root,
            String path
    ) {
        Object value = require(root, path);
        if (!(value instanceof Map<?, ?> map)) {
            throw type(path, "mapping", value);
        }
        return ConfigMaps.castMap(map);
    }

    public static List<Object> list(
            Map<String, Object> root,
            String path
    ) {
        Object value = require(root, path);
        if (!(value instanceof List<?> list)) {
            throw type(path, "list", value);
        }
        return List.copyOf(list);
    }

    public static List<String> stringList(
            Map<String, Object> root,
            String path
    ) {
        List<Object> values = list(root, path);
        var result = new java.util.ArrayList<String>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof String text)) {
                throw type(path + '[' + index + ']', "string", value);
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    public static Map<String, Object> nestedRoot(
            Map<String, Object> root,
            String path
    ) {
        return new LinkedHashMap<>(map(root, path));
    }

    private static Object find(
            Map<String, Object> root,
            String dottedPath
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(dottedPath, "dottedPath");
        Object current = root;
        for (String part : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)
                    || !map.containsKey(part)) {
                return Missing.VALUE;
            }
            current = map.get(part);
        }
        return current;
    }

    private static ConfigValueException type(
            String path,
            String expected,
            Object actual
    ) {
        String actualType = actual == null
                ? "null" : actual.getClass().getSimpleName();
        return new ConfigValueException(
                path, "expected " + expected + ", found " + actualType);
    }

    private enum Missing {
        VALUE
    }
}