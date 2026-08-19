package dev.xiaomu.crown.fabric.card;

import com.google.gson.JsonObject;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.fabric.CrownServerContext;
import dev.xiaomu.crown.fabric.permission.CrownPermissions;
import dev.xiaomu.crown.runtime.platform.PermissionSource;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.CardRedemptionResult;
import dev.xiaomu.crown.storage.model.CardRedemptionStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleKind;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 称号卡命令与右键交互共用的异步兑换入口。
 *
 * <p>所有数据库工作在存储执行器完成，物品消耗与消息反馈切回服务器主线程。
 * Repository 保证 token 标记、仓库条目和审计处于同一事务。</p>
 */
public final class CrownCardRedemption {
    private static final ConcurrentHashMap<UUID, String> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private CrownCardRedemption() {
    }

    /**
     * 兑换指定手中的卡片。
     *
     * @return true 表示该物品是 Crown 卡片并已处理；false 表示不是卡片
     */
    public static boolean redeemHeld(
            CrownServerContext context,
            ServerPlayer player,
            InteractionHand hand
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hand, "hand");

        ItemStack held = player.getItemInHand(hand);
        String token = CrownCardItems.token(held).orElse(null);
        if (token == null) {
            return false;
        }

        if (!context.permissions().checkSource(
                PermissionSource.of(player.createCommandSourceStack()),
                CrownPermissions.COMMAND_CARD,
                0)) {
            player.sendSystemMessage(context.messages()
                    .render("command.no-permission"));
            return true;
        }

        UUID playerId = player.getUUID();
        if (IN_FLIGHT.putIfAbsent(playerId, token) != null) {
            player.sendSystemMessage(context.messages()
                    .render("card.redeem.processing"));
            return true;
        }

        String playerName = player.getGameProfile().name();
        context.mainThread().whenComplete(
                context.runtime().storageExecutor().submit(() ->
                        redeemStored(
                                context, token, playerId, playerName)),
                outcome -> {
                    IN_FLIGHT.remove(playerId, token);
                    handleResult(
                            context, player, token, outcome);
                },
                failure -> {
                    IN_FLIGHT.remove(playerId, token);
                    player.sendSystemMessage(context.messages()
                            .render("purchase.failed.storage"));
                });
        return true;
    }

    /** 主手优先，其次副手；供 /crown card redeem 使用。 */
    public static boolean redeemAnyHand(
            CrownServerContext context,
            ServerPlayer player
    ) {
        if (CrownCardItems.token(
                player.getItemInHand(InteractionHand.MAIN_HAND))
                .isPresent()) {
            return redeemHeld(
                    context, player, InteractionHand.MAIN_HAND);
        }
        if (CrownCardItems.token(
                player.getItemInHand(InteractionHand.OFF_HAND))
                .isPresent()) {
            return redeemHeld(
                    context, player, InteractionHand.OFF_HAND);
        }
        player.sendSystemMessage(context.messages()
                .render("card.redeem.no-card"));
        return false;
    }

    private static RedemptionOutcome redeemStored(
            CrownServerContext context,
            String token,
            UUID playerId,
            String playerName
    ) {
        var repository = context.runtime()
                .storageBackend().repository();
        CardRecord card = repository.findCard(token).orElse(null);
        if (card == null) {
            return RedemptionOutcome.notFound();
        }
        if (card.redeemed()) {
            return RedemptionOutcome.alreadyRedeemed();
        }

        TitleDefinition definition = context.runtime().snapshot()
                .catalog().find(card.definitionId().value())
                .orElse(null);
        if (definition == null) {
            return RedemptionOutcome.definitionMissing(
                    card.definitionId().value());
        }

        Instant now = Instant.now();
        repository.ensurePlayer(
                playerId,
                playerName,
                TitleSelection.none(),
                now);

        OwnedTitleRecord owned = cardGrant(
                card, definition, playerId, now);
        AuditRecord audit = redemptionAudit(
                card, owned, playerId, now);
        CardRedemptionResult result =
                repository.redeemCardWithAudit(
                        token,
                        playerId,
                        owned,
                        audit,
                        now);
        if (result.status() == CardRedemptionStatus.REDEEMED) {
            context.runtime().playerTitleCache()
                    .load(playerId, playerName);
            return RedemptionOutcome.redeemed(
                    card.definitionId().value(),
                    owned.entryId());
        }
        if (result.status()
                == CardRedemptionStatus.ALREADY_REDEEMED) {
            return RedemptionOutcome.alreadyRedeemed();
        }
        return RedemptionOutcome.notFound();
    }

    private static OwnedTitleRecord cardGrant(
            CardRecord card,
            TitleDefinition definition,
            UUID playerId,
            Instant now
    ) {
        TitleContent content = definition.content();
        return new OwnedTitleRecord(
                UUID.randomUUID(),
                playerId,
                definition.id(),
                OwnedTitleKind.CARD,
                content.textSource(),
                content.prefixSource(),
                content.suffixSource(),
                "card:redeem",
                now,
                card.duration().expiresAt(now).orElse(null),
                null,
                OwnedTitleStatus.ACTIVE,
                null,
                null);
    }

    private static AuditRecord redemptionAudit(
            CardRecord card,
            OwnedTitleRecord owned,
            UUID playerId,
            Instant now
    ) {
        JsonObject details = new JsonObject();
        details.addProperty(
                "definitionId",
                card.definitionId().value());
        details.addProperty(
                "entryId",
                owned.entryId().toString());
        details.addProperty(
                "durationType",
                card.duration().type().name());
        details.addProperty(
                "durationDays",
                card.duration().days());
        return new AuditRecord(
                0,
                "player:" + playerId,
                "card_redeem",
                playerId,
                owned.entryId().toString(),
                details.toString(),
                now);
    }

    private static void handleResult(
            CrownServerContext context,
            ServerPlayer player,
            String token,
            RedemptionOutcome outcome
    ) {
        switch (outcome.status()) {
            case REDEEMED -> {
                consumeTokenCard(player, token);
                player.sendSystemMessage(context.messages()
                        .render(
                                "card.redeem.success",
                                outcome.definitionId()));
            }
            case NOT_FOUND -> player.sendSystemMessage(
                    context.messages().render(
                            "card.redeem.invalid"));
            case ALREADY_REDEEMED -> player.sendSystemMessage(
                    context.messages().render(
                            "card.redeem.used"));
            case DEFINITION_MISSING -> player.sendSystemMessage(
                    context.messages().render(
                            "card.redeem.definition-missing",
                            outcome.definitionId()));
        }
    }

    /**
     * 玩家可能在异步阶段切换手或移动物品，因此按 token 扫描完整背包。
     * 只有数据库返回 REDEEMED 后才消耗；失败时卡片始终保留。
     */
    private static void consumeTokenCard(
            ServerPlayer player,
            String token
    ) {
        ItemStack main = player.getItemInHand(
                InteractionHand.MAIN_HAND);
        if (CrownCardItems.matches(main, token)) {
            main.shrink(1);
            return;
        }
        ItemStack off = player.getItemInHand(
                InteractionHand.OFF_HAND);
        if (CrownCardItems.matches(off, token)) {
            off.shrink(1);
            return;
        }
        var inventory = player.getInventory();
        for (int slot = 0;
             slot < inventory.getContainerSize();
             slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (CrownCardItems.matches(stack, token)) {
                stack.shrink(1);
                return;
            }
        }
    }

    private enum OutcomeStatus {
        REDEEMED,
        NOT_FOUND,
        ALREADY_REDEEMED,
        DEFINITION_MISSING
    }

    private record RedemptionOutcome(
            OutcomeStatus status,
            String definitionId,
            UUID entryId
    ) {
        private RedemptionOutcome {
            Objects.requireNonNull(status, "status");
        }

        static RedemptionOutcome redeemed(
                String definitionId,
                UUID entryId
        ) {
            return new RedemptionOutcome(
                    OutcomeStatus.REDEEMED,
                    definitionId,
                    entryId);
        }

        static RedemptionOutcome notFound() {
            return new RedemptionOutcome(
                    OutcomeStatus.NOT_FOUND,
                    null,
                    null);
        }

        static RedemptionOutcome alreadyRedeemed() {
            return new RedemptionOutcome(
                    OutcomeStatus.ALREADY_REDEEMED,
                    null,
                    null);
        }

        static RedemptionOutcome definitionMissing(
                String definitionId
        ) {
            return new RedemptionOutcome(
                    OutcomeStatus.DEFINITION_MISSING,
                    definitionId,
                    null);
        }
    }
}