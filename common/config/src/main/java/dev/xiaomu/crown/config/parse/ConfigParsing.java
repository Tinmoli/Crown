package dev.xiaomu.crown.config.parse;

import dev.xiaomu.crown.config.io.ConfigValueException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 各类型化配置解析器共用的严格标量转换。 */
final class ConfigParsing {
    private ConfigParsing() {
    }

    static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String path
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        try {
            return Enum.valueOf(
                    type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigValueException(
                    path, "unknown value " + value, exception);
        }
    }

    static BigDecimal decimal(String value, String path) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new ConfigValueException(
                    path, "invalid decimal amount", exception);
        }
    }

    static Instant nullableInstant(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ConfigValueException(
                    path, "expected ISO-8601 time or null");
        }
        try {
            return Instant.from(
                    DateTimeFormatter.ISO_DATE_TIME.parse(text));
        } catch (DateTimeParseException exception) {
            throw new ConfigValueException(
                    path, "invalid ISO-8601 time", exception);
        }
    }

    static Map<String, Object> mapValue(Object value, String path) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new ConfigValueException(path, "expected mapping");
        }
        return dev.xiaomu.crown.config.io.ConfigMaps.castMap(source);
    }

    static String stringValue(Object value, String path) {
        if (!(value instanceof String text)) {
            throw new ConfigValueException(path, "expected string");
        }
        return text;
    }

    static <T> T wrap(String path, Factory<T> factory) {
        try {
            return factory.create();
        } catch (ConfigValueException exception) {
            throw exception;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new ConfigValueException(
                    path, exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    interface Factory<T> {
        T create();
    }
}