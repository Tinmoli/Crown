package dev.xiaomu.crown.runtime.wardrobe;

import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.repository.CrownRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 称号仓库与佩戴业务（DESIGN.md §11）。
 *
 * <p>所有方法都在存储执行器线程内同步执行，绝不触碰 Minecraft 主线程。
 * 佩戴前校验条目归属、状态与过期；佩戴当前条目返回 ALREADY_EQUIPPED。</p>
 */
public final class TitleWardrobeService {
    private final CrownRepository repository;
    private final Clock clock;

    public TitleWardrobeService(CrownRepository repository) {
        this(repository, Clock.systemUTC());
    }

    TitleWardrobeService(CrownRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 列出玩家有效（未删除）仓库条目。 */
    public List<OwnedTitleRecord> listOwned(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return repository.listOwnedTitles(playerId, false);
    }

    /** 佩戴仓库条目。校验归属、ACTIVE 与未过期后写入选择。 */
    public EquipResult equip(UUID playerId, UUID entryId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entryId, "entryId");
        Instant now = clock.instant();

        OwnedTitleRecord entry = repository.findOwnedTitle(entryId)
                .filter(record -> record.playerId().equals(playerId))
                .orElse(null);
        if (entry == null
                || entry.status() != OwnedTitleStatus.ACTIVE) {
            return EquipResult.NOT_OWNED;
        }
        if (entry.expiredAt(now)) {
            return EquipResult.EXPIRED;
        }

        TitleSelection current = repository.findPlayer(playerId)
                .map(record -> record.selection())
                .orElse(TitleSelection.none());
        if (current.type() == SelectionType.OWNED
                && current.ownedEntryId()
                        .map(entryId::equals).orElse(false)) {
            return EquipResult.ALREADY_EQUIPPED;
        }

        boolean updated = repository.setSelection(
                playerId, TitleSelection.owned(entryId), now);
        return updated ? EquipResult.EQUIPPED : EquipResult.STORAGE_FAILED;
    }

    /** 切换到服务器默认称号。 */
    public EquipResult equipDefault(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean updated = repository.setSelection(
                playerId, TitleSelection.defaultTitle(), clock.instant());
        return updated
                ? EquipResult.EQUIPPED_DEFAULT
                : EquipResult.STORAGE_FAILED;
    }

    /** 切换到不佩戴任何称号（NONE），跨重启持久保存。 */
    public EquipResult unequip(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean updated = repository.setSelection(
                playerId, TitleSelection.none(), clock.instant());
        return updated
                ? EquipResult.UNEQUIPPED
                : EquipResult.STORAGE_FAILED;
    }
}