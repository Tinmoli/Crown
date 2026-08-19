package dev.xiaomu.crown.domain.player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家最多佩戴一个称号的持久化状态。
 *
 * <p>只有 OWNED 状态携带仓库条目 UUID；DEFAULT 和 NONE 明确不携带
 * 条目 ID，从类型层阻止数据库中出现歧义状态。</p>
 */
public record TitleSelection(SelectionType type, UUID entryId) {
    private static final TitleSelection DEFAULT =
            new TitleSelection(SelectionType.DEFAULT, null);
    private static final TitleSelection NONE =
            new TitleSelection(SelectionType.NONE, null);

    public TitleSelection {
        type = Objects.requireNonNull(type, "type");
        if ((type == SelectionType.OWNED) != (entryId != null)) {
            throw new IllegalArgumentException(
                    "Only OWNED selection has an entry ID");
        }
    }

    public static TitleSelection defaultTitle() {
        return DEFAULT;
    }

    public static TitleSelection none() {
        return NONE;
    }

    public static TitleSelection owned(UUID entryId) {
        return new TitleSelection(
                SelectionType.OWNED,
                Objects.requireNonNull(entryId, "entryId"));
    }

    public Optional<UUID> ownedEntryId() {
        return Optional.ofNullable(entryId);
    }
}