package dev.xiaomu.crown.domain.catalog;

import java.util.Objects;
import java.util.regex.Pattern;

/** titles.yml 中稳定、可用于权限后缀和命令参数的商品 ID。 */
public record DefinitionId(String value)
        implements Comparable<DefinitionId> {
    private static final Pattern VALID =
            Pattern.compile("[a-z0-9_.-]{1,64}");

    public DefinitionId {
        value = Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid title definition ID: " + value);
        }
    }

    public static DefinitionId of(String value) {
        return new DefinitionId(value);
    }

    @Override
    public int compareTo(DefinitionId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}