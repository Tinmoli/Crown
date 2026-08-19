package dev.xiaomu.crown.runtime.display;

import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.text.StyledText;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 可供 Placeholder、GUI 与直接显示安全读取的当前称号快照。 */
public record ResolvedTitle(
        SelectionType state,
        UUID entryId,
        String definitionId,
        StyledText prefix,
        StyledText text,
        StyledText suffix,
        Instant expiresAt
) {
    public ResolvedTitle {
        state = Objects.requireNonNull(state, "state");
        prefix = Objects.requireNonNull(prefix, "prefix");
        text = Objects.requireNonNull(text, "text");
        suffix = Objects.requireNonNull(suffix, "suffix");
    }

    public static ResolvedTitle none() {
        return new ResolvedTitle(
                SelectionType.NONE, null, "",
                StyledText.empty(), StyledText.empty(), StyledText.empty(),
                null);
    }

    public StyledText fullText() {
        return StyledText.concatenate(prefix, text, suffix);
    }

    public String plainText() {
        return fullText().plainText();
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}