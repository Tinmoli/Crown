package dev.xiaomu.crown.domain.order;

import java.util.EnumSet;
import java.util.Set;

/** 可崩溃恢复的购买订单状态。 */
public enum PurchaseOrderState {
    PREPARED,
    PAYMENT_PENDING,
    PAYMENT_COMMITTED,
    GRANTED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == GRANTED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(PurchaseOrderState target) {
        if (target == null || target == this) {
            return false;
        }
        return allowedTargets().contains(target);
    }

    public void requireTransitionTo(PurchaseOrderState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal purchase order transition: "
                            + this + " -> " + target);
        }
    }

    private Set<PurchaseOrderState> allowedTargets() {
        return switch (this) {
            case PREPARED -> EnumSet.of(
                    PAYMENT_PENDING, CANCELLED, FAILED);
            case PAYMENT_PENDING -> EnumSet.of(
                    PAYMENT_COMMITTED, FAILED);
            case PAYMENT_COMMITTED -> EnumSet.of(GRANTED);
            case GRANTED, FAILED, CANCELLED ->
                    EnumSet.noneOf(PurchaseOrderState.class);
        };
    }
}