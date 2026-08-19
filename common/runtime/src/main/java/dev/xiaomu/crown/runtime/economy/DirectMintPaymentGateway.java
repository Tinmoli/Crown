package dev.xiaomu.crown.runtime.economy;

import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.mint.api.AccountId;
import dev.xiaomu.mint.api.Capability;
import dev.xiaomu.mint.api.CurrencyDefinition;
import dev.xiaomu.mint.api.CurrencyId;
import dev.xiaomu.mint.api.EconomyContext;
import dev.xiaomu.mint.api.EconomyProvider;
import dev.xiaomu.mint.api.FailureCode;
import dev.xiaomu.mint.api.Mint;
import dev.xiaomu.mint.api.TransactionResult;
import dev.xiaomu.mint.api.TransactionStatus;
import dev.xiaomu.mint.api.TransferRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 直接使用 Mint API 的网关。每次操作重新获取 Provider，绝不缓存活动实现。
 */
public final class DirectMintPaymentGateway
        implements MintPaymentGateway {
    private final Supplier<EconomyProvider> providerSupplier;

    public DirectMintPaymentGateway() {
        this(Mint::requireEconomy);
    }

    DirectMintPaymentGateway(
            Supplier<EconomyProvider> providerSupplier
    ) {
        this.providerSupplier = Objects.requireNonNull(
                providerSupplier, "providerSupplier");
    }

    @Override
    public MintPriceQuote quote(
            NamespacedId currency,
            BigDecimal configuredPrice
    ) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(configuredPrice, "configuredPrice");
        EconomyProvider provider = Objects.requireNonNull(
                providerSupplier.get(), "Mint provider");
        CurrencyId mintCurrency = CurrencyId.of(
                currency.namespace(), currency.path());
        CurrencyDefinition definition =
                findCurrency(provider, mintCurrency);
        long amountMinor = MintMoneyCodec.toMinorUnits(
                configuredPrice,
                definition.scale(),
                definition.roundingMode());
        return new MintPriceQuote(
                currency,
                amountMinor,
                definition.scale(),
                definition.roundingMode());
    }

    @Override
    public CompletionStage<MintPaymentResult> charge(
            UUID transactionId,
            UUID playerId,
            NamespacedId shopAccount,
            NamespacedId currency,
            long amountMinor,
            String reason,
            Map<String, String> metadata,
            Duration timeout
    ) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(shopAccount, "shopAccount");
        Objects.requireNonNull(currency, "currency");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Mint payment amount must be positive");
        }
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Mint payment timeout must be positive");
        }

        final EconomyProvider provider;
        final CurrencyId mintCurrency;
        try {
            provider = Objects.requireNonNull(
                    providerSupplier.get(), "Mint provider");
            requireCapabilities(provider);
            mintCurrency = CurrencyId.of(
                    currency.namespace(), currency.path());
            findCurrency(provider, mintCurrency);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        TransferRequest request = new TransferRequest(
                transactionId,
                AccountId.player(playerId),
                AccountId.of(
                        shopAccount.namespace(),
                        shopAccount.path()),
                mintCurrency,
                amountMinor,
                reason,
                AccountId.of(shopAccount.namespace(), shopAccount.path()),
                metadata,
                EconomyContext.GLOBAL,
                null,
                null);

        final CompletionStage<TransactionResult> transfer;
        try {
            transfer = Objects.requireNonNull(
                    provider.transfer(request),
                    "Mint transfer stage");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return transfer.toCompletableFuture()
                .orTimeout(
                        timeout.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(result -> normalize(
                        transactionId, amountMinor, result));
    }

    private static void requireCapabilities(
            EconomyProvider provider
    ) {
        if (!provider.capabilities().contains(
                Capability.ATOMIC_TRANSFER)
                || !provider.capabilities().contains(
                Capability.IDEMPOTENT_REQUESTS)) {
            throw new IllegalStateException(
                    "Active Mint provider lacks atomic/idempotent"
                            + " transfer capabilities");
        }
    }

    private static CurrencyDefinition findCurrency(
            EconomyProvider provider,
            CurrencyId currency
    ) {
        return provider.currencies().stream()
                .filter(definition ->
                        definition.id().equals(currency))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Mint currency is not registered: "
                                + currency.serialized()));
    }

    private static MintPaymentResult normalize(
            UUID expectedTransactionId,
            long expectedAmount,
            TransactionResult result
    ) {
        Objects.requireNonNull(result, "Mint transaction result");
        if (!expectedTransactionId.equals(result.transactionId())) {
            throw new IllegalStateException(
                    "Mint returned a different transaction ID");
        }
        if (result.amount() != expectedAmount) {
            throw new IllegalStateException(
                    "Mint returned a different transaction amount");
        }
        if (result.status() == TransactionStatus.SUCCESS
                && result.successful()) {
            return MintPaymentResult.success(
                    expectedTransactionId, expectedAmount);
        }

        FailureCode code = result.failureCode();
        String normalizedCode =
                code == FailureCode.NONE
                        ? result.status().name()
                        : code.name();
        return MintPaymentResult.failure(
                expectedTransactionId,
                expectedAmount,
                normalizedCode,
                result.message());
    }
}