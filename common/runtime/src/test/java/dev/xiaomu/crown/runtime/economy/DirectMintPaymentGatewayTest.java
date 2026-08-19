package dev.xiaomu.crown.runtime.economy;

import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.mint.api.AccountId;
import dev.xiaomu.mint.api.Balance;
import dev.xiaomu.mint.api.Capability;
import dev.xiaomu.mint.api.CurrencyDefinition;
import dev.xiaomu.mint.api.CurrencyId;
import dev.xiaomu.mint.api.EconomyContext;
import dev.xiaomu.mint.api.EconomyProvider;
import dev.xiaomu.mint.api.FailureCode;
import dev.xiaomu.mint.api.ProviderInfo;
import dev.xiaomu.mint.api.TransactionRequest;
import dev.xiaomu.mint.api.TransactionResult;
import dev.xiaomu.mint.api.TransferRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectMintPaymentGatewayTest {
    private static final NamespacedId CURRENCY =
            NamespacedId.parse("mint:coin");
    private static final NamespacedId SHOP =
            NamespacedId.parse("crown:shop");

    @Test
    void quotesWithCurrentProviderAndChargesFixedAmountWithNextProvider() {
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicReference<TransferRequest> captured =
                new AtomicReference<>();

        TestProvider quoteProvider = new TestProvider(
                "quote-provider",
                currency(2, RoundingMode.HALF_UP),
                request -> TransactionResult.success(
                        request.transactionId(),
                        request.amount(),
                        null,
                        null));
        TestProvider chargeProvider = new TestProvider(
                "charge-provider",
                currency(3, RoundingMode.DOWN),
                request -> {
                    captured.set(request);
                    return TransactionResult.success(
                            request.transactionId(),
                            request.amount(),
                            null,
                            null);
                });

        DirectMintPaymentGateway gateway =
                new DirectMintPaymentGateway(() ->
                        supplierCalls.getAndIncrement() == 0
                                ? quoteProvider
                                : chargeProvider);

        MintPriceQuote quote = gateway.quote(
                CURRENCY, new BigDecimal("12.345"));
        assertEquals(1_235, quote.amountMinor());
        assertEquals(2, quote.scale());
        assertEquals(RoundingMode.HALF_UP,
                quote.roundingMode());

        UUID transactionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        MintPaymentResult result = gateway.charge(
                transactionId,
                playerId,
                SHOP,
                CURRENCY,
                quote.amountMinor(),
                "crown:title_purchase",
                Map.of(
                        "order_id", UUID.randomUUID().toString(),
                        "definition_id", "vip"),
                Duration.ofSeconds(5))
                .toCompletableFuture()
                .join();

        assertTrue(result.successful());
        assertEquals(1_235, result.amountMinor());
        assertEquals(2, supplierCalls.get());

        TransferRequest request = captured.get();
        assertEquals(transactionId, request.transactionId());
        assertEquals(AccountId.player(playerId), request.source());
        assertEquals(AccountId.of("crown", "shop"),
                request.target());
        assertEquals(CurrencyId.parse("mint:coin"),
                request.currency());
        assertEquals(1_235, request.amount());
        assertEquals("crown:title_purchase", request.reason());
        assertEquals(AccountId.of("crown", "shop"),
                request.actor());
        assertEquals(EconomyContext.GLOBAL, request.context());
    }

    @Test
    void normalizesStructuredMintFailure() {
        TestProvider provider = new TestProvider(
                "failure-provider",
                currency(2, RoundingMode.HALF_EVEN),
                request -> TransactionResult.failure(
                        request.transactionId(),
                        FailureCode.INSUFFICIENT_FUNDS,
                        "not enough",
                        request.amount(),
                        null,
                        null));
        DirectMintPaymentGateway gateway =
                new DirectMintPaymentGateway(() -> provider);

        UUID transactionId = UUID.randomUUID();
        MintPaymentResult result = gateway.charge(
                transactionId,
                UUID.randomUUID(),
                SHOP,
                CURRENCY,
                500,
                "crown:custom_title_purchase",
                Map.of(),
                Duration.ofSeconds(5))
                .toCompletableFuture()
                .join();

        assertFalse(result.successful());
        assertEquals(transactionId, result.transactionId());
        assertEquals(500, result.amountMinor());
        assertEquals("INSUFFICIENT_FUNDS",
                result.failureCode());
        assertEquals("not enough", result.message());
    }

    @Test
    void rejectsProviderWithoutRequiredCapabilities() {
        TestProvider provider = new TestProvider(
                "unsafe-provider",
                currency(2, RoundingMode.HALF_UP),
                request -> TransactionResult.success(
                        request.transactionId(),
                        request.amount(),
                        null,
                        null)) {
            @Override
            public Set<Capability> capabilities() {
                return Set.of(Capability.ATOMIC_TRANSFER);
            }
        };
        DirectMintPaymentGateway gateway =
                new DirectMintPaymentGateway(() -> provider);

        var stage = gateway.charge(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SHOP,
                CURRENCY,
                100,
                "crown:title_purchase",
                Map.of(),
                Duration.ofSeconds(5));

        var failure = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertTrue(failure.getCause()
                instanceof IllegalStateException);
    }

    @Test
    void convertsConfiguredMoneyUsingMintRoundingPolicy() {
        assertEquals(
                1_235,
                MintMoneyCodec.toMinorUnits(
                        new BigDecimal("12.345"),
                        2,
                        RoundingMode.HALF_UP));
        assertEquals(
                1_234,
                MintMoneyCodec.toMinorUnits(
                        new BigDecimal("12.349"),
                        2,
                        RoundingMode.DOWN));
        assertThrows(
                IllegalArgumentException.class,
                () -> MintMoneyCodec.toMinorUnits(
                        new BigDecimal("0.0001"),
                        2,
                        RoundingMode.DOWN));
        assertThrows(
                IllegalArgumentException.class,
                () -> MintMoneyCodec.toMinorUnits(
                        new BigDecimal("999999999999999999999"),
                        9,
                        RoundingMode.UP));
    }

    private static CurrencyDefinition currency(
            int scale,
            RoundingMode roundingMode
    ) {
        return new CurrencyDefinition(
                CurrencyId.parse("mint:coin"),
                scale,
                roundingMode,
                0,
                Long.MAX_VALUE,
                new CurrencyDefinition.Names(
                        "Coin", "coin", "coins"));
    }

    private static class TestProvider
            implements EconomyProvider {
        private final ProviderInfo info;
        private final CurrencyDefinition currency;
        private final Function<TransferRequest, TransactionResult>
                transfer;

        TestProvider(
                String id,
                CurrencyDefinition currency,
                Function<TransferRequest, TransactionResult> transfer
        ) {
            info = new ProviderInfo(
                    id, id, "1.0.0", 1, 100);
            this.currency = currency;
            this.transfer = transfer;
        }

        @Override
        public ProviderInfo info() {
            return info;
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(
                    Capability.ATOMIC_TRANSFER,
                    Capability.IDEMPOTENT_REQUESTS);
        }

        @Override
        public Collection<CurrencyDefinition> currencies() {
            return Set.of(currency);
        }

        @Override
        public CurrencyId defaultCurrency() {
            return currency.id();
        }

        @Override
        public CompletionStage<Balance> balance(
                AccountId account,
                CurrencyId currencyId,
                EconomyContext context
        ) {
            return CompletableFuture.completedFuture(
                    new Balance(
                            account,
                            currencyId,
                            context,
                            0,
                            0));
        }

        @Override
        public CompletionStage<TransactionResult> deposit(
                TransactionRequest request
        ) {
            return unsupported();
        }

        @Override
        public CompletionStage<TransactionResult> withdraw(
                TransactionRequest request
        ) {
            return unsupported();
        }

        @Override
        public CompletionStage<TransactionResult> setBalance(
                TransactionRequest request
        ) {
            return unsupported();
        }

        @Override
        public CompletionStage<TransactionResult> transfer(
                TransferRequest request
        ) {
            return CompletableFuture.completedFuture(
                    transfer.apply(request));
        }

        private static CompletionStage<TransactionResult> unsupported() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException());
        }
    }
}