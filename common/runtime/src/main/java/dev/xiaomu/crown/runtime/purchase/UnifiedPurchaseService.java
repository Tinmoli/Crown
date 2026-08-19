package dev.xiaomu.crown.runtime.purchase;

import com.google.gson.JsonObject;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.NamespacedId;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.catalog.SalePolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.runtime.concurrent.PlayerOperationQueue;
import dev.xiaomu.crown.runtime.economy.MintPaymentGateway;
import dev.xiaomu.crown.runtime.economy.MintPaymentResult;
import dev.xiaomu.crown.runtime.economy.MintPriceQuote;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutor;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.InternalPaymentStatus;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.repository.CrownRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 普通与自定义称号共用的异步购买状态机。
 *
 * <p>所有 JDBC 操作均提交到存储执行器，Mint 阶段只组合
 * CompletionStage，生产代码不进行阻塞等待。</p>
 */
public final class UnifiedPurchaseService {
    private static final String TITLE_REASON =
            "crown:title_purchase";
    private static final String CUSTOM_REASON =
            "crown:custom_title_purchase";
    private static final System.Logger LOGGER = System.getLogger(
            UnifiedPurchaseService.class.getName());

    private final CrownRepository repository;
    private final AsyncStorageExecutor storage;
    private final MintPaymentGateway mint;
    private final PlayerOperationQueue playerOperations;
    private final TitleOrderSnapshotCodec snapshotCodec;
    private final Clock clock;

    public UnifiedPurchaseService(
            CrownRepository repository,
            AsyncStorageExecutor storage,
            MintPaymentGateway mint,
            PlayerOperationQueue playerOperations
    ) {
        this(
                repository,
                storage,
                mint,
                playerOperations,
                new TitleOrderSnapshotCodec(),
                Clock.systemUTC());
    }

    UnifiedPurchaseService(
            CrownRepository repository,
            AsyncStorageExecutor storage,
            MintPaymentGateway mint,
            PlayerOperationQueue playerOperations,
            TitleOrderSnapshotCodec snapshotCodec,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.mint = Objects.requireNonNull(mint, "mint");
        this.playerOperations = Objects.requireNonNull(
                playerOperations, "playerOperations");
        this.snapshotCodec = Objects.requireNonNull(
                snapshotCodec, "snapshotCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<PurchaseResult> purchaseCatalog(
            UUID playerId,
            TitleDefinition definition,
            boolean hasRequiredPermission,
            CoreSettings.Purchase settings,
            PurchaseIdentifiers identifiers
    ) {
        return purchaseCatalog(playerId, definition, definition.payment(),
                hasRequiredPermission, settings, identifiers);
    }

    public CompletionStage<PurchaseResult> purchaseCatalog(
            UUID playerId,
            TitleDefinition definition,
            PaymentPolicy selectedPayment,
            boolean hasRequiredPermission,
            CoreSettings.Purchase settings,
            PurchaseIdentifiers identifiers
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(selectedPayment, "selectedPayment");
        Objects.requireNonNull(settings, "settings");
        if (!definition.paymentOptions().contains(selectedPayment)) {
            throw new IllegalArgumentException(
                    "Selected payment is not configured for this product");
        }
        TitleDefinition selectedDefinition =
                definition.withPayment(selectedPayment);
        PaymentPolicy payment = selectedDefinition.payment();
        identifiers.requirePaymentType(payment.type());

        NamespacedId shopAccount =
                payment.type() == PaymentType.MINT
                        ? settings.mintShopAccount()
                        : null;
        TitleOrderSnapshot titleSnapshot =
                TitleOrderSnapshot.catalog(
                        identifiers.entryId(),
                        selectedDefinition,
                        shopAccount);
        SalePolicy sale = definition.sale();
        PurchaseStatus rejection = catalogRejection(
                definition,
                hasRequiredPermission,
                clock.instant());

        PurchaseRequest request = new PurchaseRequest(
                playerId,
                identifiers,
                ProductType.CATALOG,
                definition.id(),
                payment,
                titleSnapshot,
                sale.globalStock(),
                sale.perPlayerLimit(),
                settings,
                rejection);
        return submit(request);
    }

    public CompletionStage<PurchaseResult> purchaseCustom(
            UUID playerId,
            CoreSettings.CustomTitle customSettings,
            TitleContent validatedContent,
            CoreSettings.Purchase purchaseSettings,
            PurchaseIdentifiers identifiers
    ) {
        return purchaseCustom(playerId, customSettings, customSettings.payment(),
                validatedContent, purchaseSettings, identifiers);
    }

    public CompletionStage<PurchaseResult> purchaseCustom(
            UUID playerId,
            CoreSettings.CustomTitle customSettings,
            PaymentPolicy selectedPayment,
            TitleContent validatedContent,
            CoreSettings.Purchase purchaseSettings,
            PurchaseIdentifiers identifiers
    ) {
        Objects.requireNonNull(customSettings, "customSettings");
        Objects.requireNonNull(validatedContent, "validatedContent");
        Objects.requireNonNull(
                purchaseSettings, "purchaseSettings");
        PaymentPolicy payment = Objects.requireNonNull(selectedPayment,
                "selectedPayment");
        if (!customSettings.paymentOptions().contains(payment)) {
            throw new IllegalArgumentException(
                    "Selected custom payment is not configured");
        }
        identifiers.requirePaymentType(payment.type());

        if (!validatedContent.prefixSource().equals(
                customSettings.prefixSource())
                || !validatedContent.suffixSource().equals(
                customSettings.suffixSource())) {
            throw new IllegalArgumentException(
                    "Custom title does not use server prefix/suffix");
        }

        NamespacedId shopAccount =
                payment.type() == PaymentType.MINT
                        ? purchaseSettings.mintShopAccount()
                        : null;
        TitleOrderSnapshot titleSnapshot =
                TitleOrderSnapshot.custom(
                        identifiers.entryId(),
                        validatedContent,
                        customSettings.duration(),
                        shopAccount);
        PurchaseRequest request = new PurchaseRequest(
                playerId,
                identifiers,
                ProductType.CUSTOM,
                null,
                payment,
                titleSnapshot,
                -1,
                -1,
                purchaseSettings,
                customSettings.enabled()
                        ? null
                        : PurchaseStatus.DISABLED);
        return submit(request);
    }

    /**
     * 扫描并恢复一批未完成订单。损坏且可能已经完成 Mint 扣款的订单
     * 保持非终结状态并计入人工干预，不会冒险释放库存或重新生成事务。
     */
    public CompletionStage<PurchaseRecoveryReport> recover(
            int limit
    ) {
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException(
                    "Recovery limit must be between 1 and 10000");
        }
        return storage.submit(
                        () -> repository.findRecoverableOrders(limit))
                .thenCompose(this::recoverSequentially);
    }

    private CompletionStage<PurchaseResult> submit(
            PurchaseRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return playerOperations.submit(
                request.playerId(),
                () -> storage.submit(() ->
                                repository.findOrder(
                                        request.identifiers().orderId()))
                        .thenCompose(existing -> existing.isPresent()
                                ? resumeRequested(
                                request, existing.orElseThrow())
                                : createAndContinue(request)));
    }

    private CompletionStage<PurchaseResult> createAndContinue(
            PurchaseRequest request
    ) {
        if (request.rejection() != null) {
            return completed(PurchaseResult.of(
                    request.rejection(),
                    request.identifiers().orderId()));
        }

        return storage.submit(() ->
                        repository.countPendingOrders(
                                request.playerId()))
                .thenCompose(pending -> {
                    if (pending >= request.settings()
                            .maximumPendingOrdersPerPlayer()) {
                        return completed(PurchaseResult.of(
                                PurchaseStatus.TOO_MANY_PENDING,
                                request.identifiers().orderId()));
                    }
                    return quoteAndPrepare(request);
                });
    }

    private CompletionStage<PurchaseResult> quoteAndPrepare(
            PurchaseRequest request
    ) {
        final PurchaseOrderRecord order;
        try {
            order = createOrderRecord(request, clock.instant());
        } catch (RuntimeException exception) {
            if (request.payment().type() == PaymentType.MINT) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Crown could not quote Mint currency {0} for order {1}. "
                                + "Check purchase.mint-currency against Mint's "
                                + "registered currency IDs. Reason: {2}",
                        request.payment().mintCurrencyId()
                                .map(NamespacedId::serialized)
                                .orElse("<missing>"),
                        request.identifiers().orderId(), exception.toString());
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Mint quote failure", exception);
            }
            return completed(PurchaseResult.of(
                    PurchaseStatus.PAYMENT_FAILED,
                    request.identifiers().orderId(),
                    "quote_failed"));
        }

        return storage.submit(() -> repository.prepareOrder(
                        order,
                        request.globalStock(),
                        request.perPlayerLimit()))
                .thenCompose(status -> switch (status) {
                    case CREATED -> continueOrder(order, false);
                    case ORDER_ALREADY_EXISTS ->
                            storage.submit(() -> repository.findOrder(
                                            order.orderId()))
                                    .thenCompose(found ->
                                            found.isPresent()
                                                    ? resumeRequested(
                                                    request,
                                                    found.orElseThrow())
                                                    : completed(
                                                    PurchaseResult.of(
                                                            PurchaseStatus
                                                                    .ORDER_CONFLICT,
                                                            order.orderId())));
                    case OUT_OF_STOCK ->
                            completed(PurchaseResult.of(
                                    PurchaseStatus.OUT_OF_STOCK,
                                    order.orderId()));
                    case PLAYER_LIMIT_REACHED ->
                            completed(PurchaseResult.of(
                                    PurchaseStatus
                                            .PLAYER_LIMIT_REACHED,
                                    order.orderId()));
                });
    }

    private PurchaseOrderRecord createOrderRecord(
            PurchaseRequest request,
            Instant now
    ) {
        PaymentPolicy payment = request.payment();
        long amountMinor;
        NamespacedId currency = null;
        if (payment.type() == PaymentType.FREE) {
            amountMinor = 0;
        } else if (payment.type() == PaymentType.TITLE_COIN) {
            amountMinor = payment.titleCoinPrice();
        } else {
            NamespacedId configuredCurrency =
                    payment.mintCurrencyId().orElseThrow();
            MintPriceQuote quote = mint.quote(
                    configuredCurrency,
                    payment.configuredPrice().orElseThrow());
            if (!quote.currency().equals(configuredCurrency)) {
                throw new IllegalStateException(
                        "Mint quote returned another currency");
            }
            currency = quote.currency();
            amountMinor = quote.amountMinor();
        }

        return new PurchaseOrderRecord(
                request.identifiers().orderId(),
                request.identifiers().mintTransactionId(),
                request.playerId(),
                request.productType(),
                request.definitionId(),
                payment.type(),
                currency,
                amountMinor,
                snapshotCodec.encode(request.titleSnapshot()),
                PurchaseOrderState.PREPARED,
                null,
                null,
                request.globalStock() >= 0,
                now,
                now);
    }

    private CompletionStage<PurchaseResult> resumeRequested(
            PurchaseRequest request,
            PurchaseOrderRecord existing
    ) {
        if (!matchesRequest(request, existing)) {
            return completed(PurchaseResult.of(
                    PurchaseStatus.ORDER_CONFLICT,
                    request.identifiers().orderId()));
        }
        return continueOrder(existing, false);
    }

    private boolean matchesRequest(
            PurchaseRequest request,
            PurchaseOrderRecord order
    ) {
        if (!order.orderId().equals(
                request.identifiers().orderId())
                || !order.playerId().equals(request.playerId())
                || order.productType() != request.productType()
                || !Objects.equals(
                order.definitionId(), request.definitionId())
                || order.paymentType() != request.payment().type()
                || !Objects.equals(
                order.mintTransactionId(),
                request.identifiers().mintTransactionId())) {
            return false;
        }

        if (order.paymentType() == PaymentType.MINT
                && !Objects.equals(
                order.currencyId(),
                request.payment().mintCurrency())) {
            return false;
        }
        if (order.paymentType() == PaymentType.FREE
                && order.amountMinor() != 0) {
            return false;
        }
        if (order.paymentType() == PaymentType.TITLE_COIN
                && order.amountMinor()
                != request.payment().titleCoinPrice()) {
            return false;
        }

        try {
            return snapshotCodec.decode(
                    order.titleSnapshotJson()).equals(
                    request.titleSnapshot());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private CompletionStage<PurchaseResult> continueOrder(
            PurchaseOrderRecord order,
            boolean recovery
    ) {
        final TitleOrderSnapshot titleSnapshot;
        try {
            titleSnapshot = snapshotCodec.decode(
                    order.titleSnapshotJson());
            if (!titleSnapshot.matches(order)) {
                throw new IllegalArgumentException(
                        "Snapshot does not match order");
            }
        } catch (IllegalArgumentException exception) {
            return handleInvalidSnapshot(order);
        }

        return switch (order.state()) {
            case PREPARED -> moveToPaymentPending(
                    order, recovery);
            case PAYMENT_PENDING -> processPayment(
                    order, titleSnapshot, recovery);
            case PAYMENT_COMMITTED -> grant(
                    order, titleSnapshot, recovery);
            case GRANTED -> completed(PurchaseResult.granted(
                    order.orderId(),
                    order.grantedEntryId().orElseThrow()));
            case FAILED -> completed(failedResult(order));
            case CANCELLED -> completed(PurchaseResult.of(
                    PurchaseStatus.INVALID_STATE,
                    order.orderId(),
                    "order_cancelled"));
        };
    }

    private CompletionStage<PurchaseResult> moveToPaymentPending(
            PurchaseOrderRecord order,
            boolean recovery
    ) {
        Instant now = clock.instant();
        return storage.submit(() -> repository.transitionOrder(
                        order.orderId(),
                        PurchaseOrderState.PREPARED,
                        PurchaseOrderState.PAYMENT_PENDING,
                        null,
                        now))
                .thenCompose(ignored ->
                        reloadAndContinue(
                                order.orderId(), recovery));
    }

    private CompletionStage<PurchaseResult> processPayment(
            PurchaseOrderRecord order,
            TitleOrderSnapshot titleSnapshot,
            boolean recovery
    ) {
        if (order.paymentType() == PaymentType.MINT) {
            return processMintPayment(
                    order, titleSnapshot, recovery);
        }

        String actor = recovery
                ? "system:recovery"
                : "player:" + order.playerId();
        return storage.submit(() ->
                        repository.commitInternalPayment(
                                order.orderId(),
                                actor,
                                reason(order),
                                clock.instant()))
                .thenCompose(result -> switch (result.status()) {
                    case COMMITTED, ALREADY_COMMITTED ->
                            reloadAndContinue(
                                    order.orderId(), recovery);
                    case INSUFFICIENT_FUNDS ->
                            completed(PurchaseResult.of(
                                    PurchaseStatus
                                            .INSUFFICIENT_FUNDS,
                                    order.orderId(),
                                    "insufficient_title_coins"));
                    case INVALID_STATE ->
                            completed(PurchaseResult.of(
                                    PurchaseStatus.INVALID_STATE,
                                    order.orderId(),
                                    "invalid_internal_payment_state"));
                });
    }

    private CompletionStage<PurchaseResult> processMintPayment(
            PurchaseOrderRecord order,
            TitleOrderSnapshot titleSnapshot,
            boolean recovery
    ) {
        UUID transactionId =
                Objects.requireNonNull(
                        order.mintTransactionId(),
                        "mintTransactionId");
        NamespacedId currency =
                Objects.requireNonNull(
                        order.currencyId(), "currencyId");
        NamespacedId shopAccount =
                Objects.requireNonNull(
                        titleSnapshot.mintShopAccount(),
                        "mintShopAccount");

        final CompletionStage<MintPaymentResult> paymentStage;
        try {
            paymentStage = mint.charge(
                    transactionId,
                    order.playerId(),
                    shopAccount,
                    currency,
                    order.amountMinor(),
                    reason(order),
                    metadata(order, titleSnapshot),
                    orderTimeout(order));
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Crown could not start Mint payment for order {0}, currency {1}. "
                            + "Check that Mint is running, the currency exists, and "
                            + "purchase.mint-shop-account is valid. Reason: {2}",
                    order.orderId(), currency.serialized(), exception.toString());
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Mint payment start failure", exception);
            return uncertain(order, "mint_exception");
        }

        return paymentStage.handle(MintOutcome::new)
                .thenCompose(outcome -> {
                    if (outcome.failure() != null) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Mint payment result was unavailable for Crown order {0}, "
                                        + "currency {1}: {2}",
                                order.orderId(), currency.serialized(),
                                outcome.failure().toString());
                        return uncertain(
                                order, "mint_unknown_result");
                    }
                    MintPaymentResult payment =
                            outcome.result();
                    if (payment.successful()) {
                        return commitMintPayment(
                                order, recovery);
                    }
                    return failMintPayment(order, payment);
                });
    }

    private CompletionStage<PurchaseResult> commitMintPayment(
            PurchaseOrderRecord order,
            boolean recovery
    ) {
        CompletionStage<PurchaseResult> stage =
                storage.submit(() -> repository.transitionOrder(
                                order.orderId(),
                                PurchaseOrderState.PAYMENT_PENDING,
                                PurchaseOrderState.PAYMENT_COMMITTED,
                                null,
                                clock.instant()))
                        .thenCompose(ignored ->
                                reloadAndContinue(
                                        order.orderId(),
                                        recovery));
        return recoverUncertain(
                stage, order, "mint_commit_unknown");
    }

    private CompletionStage<PurchaseResult> failMintPayment(
            PurchaseOrderRecord order,
            MintPaymentResult payment
    ) {
        String failureCode =
                normalizeMintFailure(payment.failureCode());
        CompletionStage<PurchaseResult> stage =
                storage.submit(() -> repository.transitionOrder(
                                order.orderId(),
                                PurchaseOrderState.PAYMENT_PENDING,
                                PurchaseOrderState.FAILED,
                                failureCode,
                                clock.instant()))
                        .thenCompose(changed -> changed
                                ? completed(PurchaseResult.of(
                                PurchaseStatus.PAYMENT_FAILED,
                                order.orderId(),
                                failureCode))
                                : reloadAndContinue(
                                order.orderId(), false));
        return recoverUncertain(
                stage, order, "mint_failure_not_persisted");
    }

    private CompletionStage<PurchaseResult> grant(
            PurchaseOrderRecord order,
            TitleOrderSnapshot titleSnapshot,
            boolean recovery
    ) {
        Instant now = clock.instant();
        OwnedTitleRecord title =
                titleSnapshot.toOwnedTitle(order, now);
        AuditRecord audit = purchaseAudit(
                order, title, recovery, now);
        return storage.submit(() ->
                        repository.grantCommittedOrderWithAudit(
                                order.orderId(),
                                title,
                                audit,
                                now))
                .thenApply(granted ->
                        PurchaseResult.granted(
                                order.orderId(),
                                granted.entryId()));
    }

    private CompletionStage<PurchaseResult> reloadAndContinue(
            UUID orderId,
            boolean recovery
    ) {
        return storage.submit(() ->
                        repository.findOrder(orderId))
                .thenCompose(found -> found.isPresent()
                        ? continueOrder(
                        found.orElseThrow(), recovery)
                        : completed(PurchaseResult.of(
                        PurchaseStatus.INVALID_STATE,
                        orderId,
                        "order_missing")));
    }

    private CompletionStage<PurchaseResult> handleInvalidSnapshot(
            PurchaseOrderRecord order
    ) {
        boolean safeToFail =
                order.state() == PurchaseOrderState.PREPARED
                        || (order.state()
                        == PurchaseOrderState.PAYMENT_PENDING
                        && order.paymentType()
                        != PaymentType.MINT);
        if (!safeToFail) {
            return completed(PurchaseResult.of(
                    order.paymentType() == PaymentType.MINT
                            ? PurchaseStatus.PAYMENT_UNCERTAIN
                            : PurchaseStatus.INVALID_STATE,
                    order.orderId(),
                    "invalid_order_snapshot"));
        }
        return storage.submit(() -> repository.transitionOrder(
                        order.orderId(),
                        order.state(),
                        PurchaseOrderState.FAILED,
                        "invalid_order_snapshot",
                        clock.instant()))
                .thenApply(ignored -> PurchaseResult.of(
                        PurchaseStatus.INVALID_STATE,
                        order.orderId(),
                        "invalid_order_snapshot"));
    }

    private CompletionStage<PurchaseRecoveryReport>
    recoverSequentially(List<PurchaseOrderRecord> orders) {
        CompletionStage<PurchaseRecoveryReport> stage =
                CompletableFuture.completedFuture(
                        PurchaseRecoveryReport.empty());
        for (PurchaseOrderRecord order : orders) {
            stage = stage.thenCompose(report ->
                    playerOperations.submit(
                                    order.playerId(),
                                    () -> reloadAndContinue(
                                            order.orderId(), true))
                            .handle((result, failure) ->
                                    failure == null
                                            ? report.append(result)
                                            : report.appendFailure()));
        }
        return stage;
    }

    private CoreSettings.Purchase purchaseSettings(
            PurchaseOrderRecord order
    ) {
        throw new UnsupportedOperationException(
                "Purchase settings are not persisted separately");
    }

    private java.time.Duration orderTimeout(
            PurchaseOrderRecord order
    ) {
        /*
         * timeout 不是转账请求幂等身份的一部分。恢复时使用当前安全超时，
         * 商城账户、货币、金额、reason 与 transaction UUID 均来自订单。
         */
        return java.time.Duration.ofSeconds(30);
    }

    private static PurchaseStatus catalogRejection(
            TitleDefinition definition,
            boolean hasRequiredPermission,
            Instant now
    ) {
        if (!definition.enabled()) {
            return PurchaseStatus.DISABLED;
        }
        if (!definition.visible()) {
            return PurchaseStatus.HIDDEN;
        }
        if (!definition.sale().onSaleAt(now)) {
            return PurchaseStatus.NOT_ON_SALE;
        }
        if (definition.permission().isPresent()
                && !hasRequiredPermission) {
            return definition.denyIfMissingPermission()
                    ? PurchaseStatus.PERMISSION_DENIED
                    : PurchaseStatus.HIDDEN;
        }
        return null;
    }

    private static String reason(PurchaseOrderRecord order) {
        return order.productType() == ProductType.CUSTOM
                ? CUSTOM_REASON
                : TITLE_REASON;
    }

    private static Map<String, String> metadata(
            PurchaseOrderRecord order,
            TitleOrderSnapshot snapshot
    ) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("crown_order_id",
                order.orderId().toString());
        metadata.put("crown_entry_id",
                snapshot.entryId().toString());
        metadata.put("crown_product_type",
                order.productType().name());
        if (order.definitionId() != null) {
            metadata.put("crown_definition_id",
                    order.definitionId().value());
        }
        return Map.copyOf(metadata);
    }

    private static AuditRecord purchaseAudit(
            PurchaseOrderRecord order,
            OwnedTitleRecord title,
            boolean recovery,
            Instant now
    ) {
        JsonObject details = new JsonObject();
        details.addProperty(
                "paymentType", order.paymentType().name());
        details.addProperty(
                "productType", order.productType().name());
        details.addProperty(
                "amountMinor", order.amountMinor());
        details.addProperty(
                "entryId", title.entryId().toString());
        details.addProperty("recovery", recovery);
        if (order.currencyId() != null) {
            details.addProperty(
                    "currencyId",
                    order.currencyId().serialized());
        }
        if (order.definitionId() != null) {
            details.addProperty(
                    "definitionId",
                    order.definitionId().value());
        }
        return new AuditRecord(
                0,
                recovery
                        ? "system:recovery"
                        : "player:" + order.playerId(),
                order.productType() == ProductType.CUSTOM
                        ? "custom_title_purchase_granted"
                        : "title_purchase_granted",
                order.playerId(),
                order.orderId().toString(),
                details.toString(),
                now);
    }

    private static PurchaseResult failedResult(
            PurchaseOrderRecord order
    ) {
        String code = order.failureCode() == null
                ? "payment_failed"
                : order.failureCode();
        PurchaseStatus status =
                "insufficient_title_coins".equals(code)
                        ? PurchaseStatus.INSUFFICIENT_FUNDS
                        : PurchaseStatus.PAYMENT_FAILED;
        return PurchaseResult.of(
                status, order.orderId(), code);
    }

    private static String normalizeMintFailure(String value) {
        String normalized = value.toLowerCase(
                        java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        String result = "mint_" + normalized;
        return result.length() <= 128
                ? result
                : result.substring(0, 128);
    }

    private static CompletionStage<PurchaseResult> uncertain(
            PurchaseOrderRecord order,
            String code
    ) {
        return completed(PurchaseResult.of(
                PurchaseStatus.PAYMENT_UNCERTAIN,
                order.orderId(),
                code));
    }

    private static CompletionStage<PurchaseResult>
    recoverUncertain(
            CompletionStage<PurchaseResult> stage,
            PurchaseOrderRecord order,
            String code
    ) {
        return stage.handle((result, failure) ->
                failure == null
                        ? result
                        : PurchaseResult.of(
                        PurchaseStatus.PAYMENT_UNCERTAIN,
                        order.orderId(),
                        code));
    }

    private static CompletionStage<PurchaseResult> completed(
            PurchaseResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private record PurchaseRequest(
            UUID playerId,
            PurchaseIdentifiers identifiers,
            ProductType productType,
            DefinitionId definitionId,
            PaymentPolicy payment,
            TitleOrderSnapshot titleSnapshot,
            long globalStock,
            int perPlayerLimit,
            CoreSettings.Purchase settings,
            PurchaseStatus rejection
    ) {
        private PurchaseRequest {
            playerId = Objects.requireNonNull(
                    playerId, "playerId");
            identifiers = Objects.requireNonNull(
                    identifiers, "identifiers");
            productType = Objects.requireNonNull(
                    productType, "productType");
            payment = Objects.requireNonNull(
                    payment, "payment");
            titleSnapshot = Objects.requireNonNull(
                    titleSnapshot, "titleSnapshot");
            settings = Objects.requireNonNull(
                    settings, "settings");
            if (productType == ProductType.CATALOG
                    && definitionId == null) {
                throw new IllegalArgumentException(
                        "Catalog request requires definition ID");
            }
            if (productType == ProductType.CUSTOM
                    && definitionId != null) {
                throw new IllegalArgumentException(
                        "Custom request cannot have definition ID");
            }
        }
    }

    private record MintOutcome(
            MintPaymentResult result,
            Throwable failure
    ) {
        private MintOutcome(
                MintPaymentResult result,
                Throwable failure
        ) {
            this.result = result;
            this.failure = failure;
            if ((result == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Mint outcome must have one result");
            }
        }
    }
}