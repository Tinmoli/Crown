package dev.xiaomu.crown.domain.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogModelTest {
    @Test
    void validatesIdsAndPaymentExclusivity() {
        assertEquals("event_winner",
                DefinitionId.of("event_winner").value());
        assertThrows(IllegalArgumentException.class,
                () -> DefinitionId.of("Event Winner"));

        PaymentPolicy mint = PaymentPolicy.mint(
                NamespacedId.parse("mint:coin"),
                new BigDecimal("12.50"));
        assertEquals(PaymentType.MINT, mint.type());
        assertEquals("mint:coin",
                mint.mintCurrencyId().orElseThrow().serialized());

        PaymentPolicy coin = PaymentPolicy.titleCoin(50);
        assertEquals(50, coin.titleCoinPrice());
        assertThrows(IllegalStateException.class,
                mint::titleCoinPrice);
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentPolicy(
                        PaymentType.TITLE_COIN,
                        null,
                        new BigDecimal("1.5")));
    }

    @Test
    void durationCalculatesAbsoluteExpiry() {
        Instant granted = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(DurationPolicy.permanent()
                .expiresAt(granted).isEmpty());
        assertEquals(Instant.parse("2026-01-31T00:00:00Z"),
                DurationPolicy.limited(30)
                        .expiresAt(granted).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new DurationPolicy(
                        DurationType.PERMANENT, 1));
    }

    @Test
    void saleWindowUsesInclusiveBoundaries() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-02-01T00:00:00Z");
        SalePolicy sale = new SalePolicy(start, end, 100, 1);

        assertTrue(sale.onSaleAt(start));
        assertTrue(sale.onSaleAt(end));
        assertFalse(sale.onSaleAt(start.minusMillis(1)));
        assertFalse(sale.onSaleAt(end.plusMillis(1)));
        assertTrue(sale.limitedStock());
        assertThrows(IllegalArgumentException.class,
                () -> new SalePolicy(end, start, -1, -1));
    }
}