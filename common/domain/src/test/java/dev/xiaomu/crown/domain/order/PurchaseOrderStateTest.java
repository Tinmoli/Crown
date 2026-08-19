package dev.xiaomu.crown.domain.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PurchaseOrderStateTest {
    @Test
    void acceptsRecoverableMintPurchasePath() {
        assertTrue(PurchaseOrderState.PREPARED.canTransitionTo(
                PurchaseOrderState.PAYMENT_PENDING));
        assertTrue(PurchaseOrderState.PAYMENT_PENDING.canTransitionTo(
                PurchaseOrderState.PAYMENT_COMMITTED));
        assertTrue(PurchaseOrderState.PAYMENT_COMMITTED.canTransitionTo(
                PurchaseOrderState.GRANTED));
    }

    @Test
    void terminalStatesCannotTransition() {
        assertTrue(PurchaseOrderState.GRANTED.terminal());
        assertFalse(PurchaseOrderState.GRANTED.canTransitionTo(
                PurchaseOrderState.FAILED));
        assertThrows(IllegalStateException.class,
                () -> PurchaseOrderState.GRANTED.requireTransitionTo(
                        PurchaseOrderState.PAYMENT_PENDING));
    }

    @Test
    void committedPaymentCannotBecomeFailedOrCancelled() {
        assertFalse(PurchaseOrderState.PAYMENT_COMMITTED.canTransitionTo(
                PurchaseOrderState.FAILED));
        assertFalse(PurchaseOrderState.PAYMENT_COMMITTED.canTransitionTo(
                PurchaseOrderState.CANCELLED));
    }
}