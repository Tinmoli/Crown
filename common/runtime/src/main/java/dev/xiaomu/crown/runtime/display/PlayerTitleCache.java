package dev.xiaomu.crown.runtime.display;

import dev.xiaomu.crown.config.runtime.RuntimeSnapshot;
import dev.xiaomu.crown.domain.player.SelectionType;
import dev.xiaomu.crown.domain.text.CrownTextParser;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import dev.xiaomu.crown.storage.repository.CrownRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Placeholder 和直接显示共用的无阻塞在线玩家缓存。
 *
 * <p>数据库读取只能由调用方在存储执行器中执行；查询方法绝不访问 JDBC。</p>
 */
public final class PlayerTitleCache {
    private final CrownRepository repository;
    private final Supplier<RuntimeSnapshot> snapshots;
    private final Clock clock;
    private final Map<UUID, CachedPlayer> players = new ConcurrentHashMap<>();

    public PlayerTitleCache(
            CrownRepository repository,
            Supplier<RuntimeSnapshot> snapshots
    ) {
        this(repository, snapshots, Clock.systemUTC());
    }

    PlayerTitleCache(
            CrownRepository repository,
            Supplier<RuntimeSnapshot> snapshots,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 必须从 Crown 的存储执行器调用。 */
    public CachedPlayer load(UUID playerId, String playerName) {
        RuntimeSnapshot snapshot = snapshots.get();
        PlayerRecord player = repository.ensurePlayer(
                playerId,
                playerName,
                snapshot.core().defaultTitle().equipForNewPlayer()
                        ? dev.xiaomu.crown.domain.player.TitleSelection.defaultTitle()
                        : dev.xiaomu.crown.domain.player.TitleSelection.none(),
                clock.instant());
        ResolvedTitle title = resolve(player, snapshot, clock.instant());
        CachedPlayer cached = new CachedPlayer(
                player.playerId(), player.lastKnownName(),
                player.titleCoinBalance(), title);
        players.put(playerId, cached);
        return cached;
    }

    public CachedPlayer get(UUID playerId) {
        return players.getOrDefault(
                Objects.requireNonNull(playerId, "playerId"),
                CachedPlayer.empty(playerId));
    }

    public void put(CachedPlayer player) {
        players.put(player.playerId(), Objects.requireNonNull(player, "player"));
    }

    public void invalidate(UUID playerId) {
        players.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public void clear() {
        players.clear();
    }

    private ResolvedTitle resolve(
            PlayerRecord player,
            RuntimeSnapshot snapshot,
            Instant now
    ) {
        return switch (player.selection().type()) {
            case NONE -> ResolvedTitle.none();
            case DEFAULT -> {
                var configured = snapshot.core().defaultTitle();
                if (!configured.enabled()) {
                    yield ResolvedTitle.none();
                }
                var content = configured.content();
                yield new ResolvedTitle(
                        SelectionType.DEFAULT, null, "default",
                        content.prefix(), content.text(), content.suffix(), null);
            }
            case OWNED -> player.selection().ownedEntryId()
                    .flatMap(repository::findOwnedTitle)
                    .filter(entry -> entry.playerId().equals(player.playerId()))
                    .filter(entry -> entry.status() == OwnedTitleStatus.ACTIVE)
                    .filter(entry -> !entry.expiredAt(now))
                    .map(entry -> fromOwned(entry, snapshot))
                    .orElseGet(ResolvedTitle::none);
        };
    }

    private static ResolvedTitle fromOwned(
            OwnedTitleRecord entry,
            RuntimeSnapshot snapshot
    ) {
        CrownTextParser parser = new CrownTextParser(
                snapshot.core().safety().serverTextPolicy());
        return new ResolvedTitle(
                SelectionType.OWNED,
                entry.entryId(),
                entry.definition().map(Object::toString).orElse(""),
                parser.parse(entry.titlePrefix()),
                parser.parse(entry.titleText()),
                parser.parse(entry.titleSuffix()),
                entry.expiresAt());
    }

    public record CachedPlayer(
            UUID playerId,
            String playerName,
            long titleCoinBalance,
            ResolvedTitle title
    ) {
        public CachedPlayer {
            playerId = Objects.requireNonNull(playerId, "playerId");
            playerName = Objects.requireNonNull(playerName, "playerName");
            if (titleCoinBalance < 0) {
                throw new IllegalArgumentException("Negative title coin balance");
            }
            title = Objects.requireNonNull(title, "title");
        }

        static CachedPlayer empty(UUID playerId) {
            return new CachedPlayer(playerId, "", 0, ResolvedTitle.none());
        }
    }
}