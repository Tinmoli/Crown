package dev.xiaomu.crown.domain.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/** Minecraft 资源的命名空间 ID。 */
public record NamespacedId(String namespace, String path)
        implements Comparable<NamespacedId> {
    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern RESOURCE_PATH =
            Pattern.compile("[a-z0-9_./-]{1,192}");
    private static final Pattern SIMPLE_PATH =
            Pattern.compile("[a-z0-9_.-]{1,64}");

    public NamespacedId {
        namespace = Objects.requireNonNull(namespace, "namespace");
        path = Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()
                || !RESOURCE_PATH.matcher(path).matches()
                || path.startsWith("/") || path.endsWith("/")
                || path.contains("//")) {
            throw new IllegalArgumentException(
                    "Invalid namespaced ID: " + namespace + ':' + path);
        }
    }

    public static NamespacedId parse(String serialized) {
        Objects.requireNonNull(serialized, "serialized");
        int separator = serialized.indexOf(':');
        if (separator <= 0
                || separator != serialized.lastIndexOf(':')
                || separator == serialized.length() - 1) {
            throw new IllegalArgumentException(
                    "ID must use namespace:path");
        }
        return new NamespacedId(
                serialized.substring(0, separator),
                serialized.substring(separator + 1));
    }


    public String serialized() {
        return namespace + ':' + path;
    }

    @Override
    public int compareTo(NamespacedId other) {
        return serialized().compareTo(other.serialized());
    }

    @Override
    public String toString() {
        return serialized();
    }
}
