package dev.xiaomu.crown.runtime.purchase;

import com.google.gson.JsonObject;
import dev.xiaomu.crown.config.model.CoreSettings;
import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.catalog.PaymentPolicy;
import dev.xiaomu.crown.domain.catalog.PaymentType;
import dev.xiaomu.crown.domain.catalog.SalePolicy;
import dev.xiaomu.crown.domain.catalog.TitleContent;
import dev.xiaomu.crown.domain.catalog.TitleDefinition;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.runtime.concurrent.PlayerOperationQueue;
import dev.xiaomu.crown.storage.async.AsyncStorageExecutor;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.ProductType;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.repository.CrownRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 普通与自定义称号共用的异步购买状态机。
 *
 * <p>所有 JDBC 操作均提交到存储执行器，购买流程只组合
 * CompletionStage，生产代码不进行阻塞等待。</p>
 */
public final class UnifiedPurchaseService {
    private static final String TITLE_REASON =
            "crown:title_purchase";
    private static final String CUSTOM_REASON =
            "crown:custom_title_purchase";

    private final CrownRepository repository;
    private final AsyncStorageExecutor storage;
    private final PlayerOperationQueue playerOperations;
    private final TitleOrderSnapshotCodec snapshotCodec;
    private final Clock clock;

    public UnifiedPurchaseService(
            CrownRepository repository,
            AsyncStorageExecutor storage,
            PlayerOperationQueue playerOperations
    ) {
        this(
                repository,
                storage,
                playerOperations,
                new TitleOrderSnapshotCodec(),
                Clock.systemUTC());
    }

    UnifiedPurchaseService(
            CrownRepository repository,
            AsyncStorageExecutor storage,
            PlayerOperationQueue playerOperations,
            TitleOrderSnapshotCodec snapshotCodec,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
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
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(settings, "settings");
        PaymentPolicy payment = definition.payment();
        TitleOrderSnapshot titleSnapshot = TitleOrderSnapshot.catalog(identifiers.entryId(), definition);
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
        Objects.requireNonNull(customSettings, "customSettings");
        Objects.requireNonNull(validatedContent, "validatedContent");
        Objects.requireNonNull(
                purchaseSettings, "purchaseSettings");
        PaymentPolicy payment = customSettings.payment();

        if (!validatedContent.prefixSource().equals(
                customSettings.prefixSource())
                || !validatedContent.suffixSource().equals(
                customSettings.suffixSource())) {
            throw new IllegalArgumentException(
                    "Custom title does not use server prefix/suffix");
        }

        TitleOrderSnapshot titleSnapshot =
                TitleOrderSnapshot.custom(
                        identifiers.entryId(),
                        validatedContent,
                        customSettings.duration());
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
     * 扫描并恢复一批未完成的称号币订单。
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
        PurchaseOrderRecord order = createOrderRecord(request, clock.instant());

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
        long amountMinor = payment.titleCoinPrice();

        return new PurchaseOrderRecord(
                request.identifiers().orderId(),
                request.playerId(),
                request.productType(),
                request.definitionId(),
                payment.type(),
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
) {
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
                        || order.state() == PurchaseOrderState.PAYMENT_PENDING;
        if (!safeToFail) {
            return completed(PurchaseResult.of(
                    PurchaseStatus.INVALID_STATE,
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

}
