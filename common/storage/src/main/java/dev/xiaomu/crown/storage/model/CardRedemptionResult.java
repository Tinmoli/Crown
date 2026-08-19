package dev.xiaomu.crown.storage.model;

import java.util.Objects;
import java.util.Optional;

/** 称号卡状态更新与仓库发放的同事务结果。 */
public record CardRedemptionResult(
        CardRedemptionStatus status,
        CardRecord cardRecord,
        OwnedTitleRecord grantedTitle
) {
    public CardRedemptionResult {
        status = Objects.requireNonNull(status, "status");
        switch (status) {
            case NOT_FOUND -> {
                if (cardRecord != null || grantedTitle != null) {
                    throw new IllegalArgumentException(
                            "Missing card result cannot contain records");
                }
            }
            case ALREADY_REDEEMED -> {
                if (cardRecord == null || !cardRecord.redeemed()
                        || grantedTitle != null) {
                    throw new IllegalArgumentException(
                            "Invalid already-redeemed card result");
                }
            }
            case REDEEMED -> {
                if (cardRecord == null || !cardRecord.redeemed()
                        || grantedTitle == null) {
                    throw new IllegalArgumentException(
                            "Successful redemption requires records");
                }
            }
        }
    }

    public static CardRedemptionResult notFound() {
        return new CardRedemptionResult(
                CardRedemptionStatus.NOT_FOUND, null, null);
    }

    public static CardRedemptionResult alreadyRedeemed(
            CardRecord card
    ) {
        return new CardRedemptionResult(
                CardRedemptionStatus.ALREADY_REDEEMED,
                Objects.requireNonNull(card, "card"),
                null);
    }

    public static CardRedemptionResult redeemed(
            CardRecord card,
            OwnedTitleRecord title
    ) {
        return new CardRedemptionResult(
                CardRedemptionStatus.REDEEMED,
                Objects.requireNonNull(card, "card"),
                Objects.requireNonNull(title, "title"));
    }

    public Optional<CardRecord> card() {
        return Optional.ofNullable(cardRecord);
    }

    public Optional<OwnedTitleRecord> title() {
        return Optional.ofNullable(grantedTitle);
    }
}