package dev.xiaomu.crown.domain.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Minecraft 无关的解析后文本，由有序样式段组成。 */
public record StyledText(List<StyledSegment> segments) {
    private static final StyledText EMPTY = new StyledText(List.of());

    public StyledText {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Text contains a null segment");
        }
    }

    public static StyledText empty() {
        return EMPTY;
    }

    /**
     * 按顺序拼接文本并合并边界上样式相同的段。
     */
    public static StyledText concatenate(StyledText... values) {
        Objects.requireNonNull(values, "values");
        var result = new ArrayList<StyledSegment>();
        for (StyledText value : values) {
            Objects.requireNonNull(value, "text value");
            for (StyledSegment segment : value.segments) {
                if (!result.isEmpty()) {
                    StyledSegment previous = result.get(result.size() - 1);
                    if (previous.style().equals(segment.style())) {
                        result.set(result.size() - 1, new StyledSegment(
                                previous.text() + segment.text(),
                                previous.style()));
                        continue;
                    }
                }
                result.add(segment);
            }
        }
        return result.isEmpty() ? EMPTY : new StyledText(result);
    }

    public String plainText() {
        var result = new StringBuilder();
        for (StyledSegment segment : segments) {
            result.append(segment.text());
        }
        return result.toString();
    }

    public int visibleCodePointCount() {
        int count = 0;
        for (StyledSegment segment : segments) {
            count = Math.addExact(count, segment.codePointCount());
        }
        return count;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}