package dev.xiaomu.crown.storage.repository;

import dev.xiaomu.crown.domain.catalog.DefinitionId;
import dev.xiaomu.crown.domain.order.PurchaseOrderState;
import dev.xiaomu.crown.domain.player.TitleSelection;
import dev.xiaomu.crown.storage.model.AuditRecord;
import dev.xiaomu.crown.storage.model.CardRecord;
import dev.xiaomu.crown.storage.model.CardRedemptionResult;
import dev.xiaomu.crown.storage.model.CoinAdjustmentResult;
import dev.xiaomu.crown.storage.model.InternalPaymentResult;
import dev.xiaomu.crown.storage.model.OrderPreparationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleDurationStatus;
import dev.xiaomu.crown.storage.model.OwnedTitleRecord;
import dev.xiaomu.crown.storage.model.PlayerRecord;
import dev.xiaomu.crown.storage.model.PurchaseOrderRecord;
import dev.xiaomu.crown.storage.model.SaleCounterRecord;
import dev.xiaomu.crown.storage.model.StorageSummary;
import dev.xiaomu.crown.storage.model.TitleCoinLedgerRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQLite/MySQL 共用的同步 Repository 契约。 */
public interface CrownRepository extends AutoCloseable {
    int initializeSchema();

    PlayerRecord ensurePlayer(
            UUID playerId,
            String currentName,
            TitleSelection initialSelection,
            Instant now
    );

    Optional<PlayerRecord> findPlayer(UUID playerId);

    boolean setSelection(
            UUID playerId,
            TitleSelection selection,
            Instant now
    );

    List<OwnedTitleRecord> listOwnedTitles(
            UUID playerId,
            boolean includeDeleted
    );

    Optional<OwnedTitleRecord> findOwnedTitle(UUID entryId);

    boolean insertOwnedTitle(OwnedTitleRecord title);

    /**
     * 原子创建仓库条目并写入审计。条目 ID 已存在时返回 false 且不写审计。
     */
    boolean insertOwnedTitleWithAudit(
            OwnedTitleRecord title,
            AuditRecord audit
    );

    /**
     * 原子修改有效仓库条目的到期时间并写入审计。
     *
     * @param expiresAt 新到期时间；null 表示永久
     */
    OwnedTitleDurationStatus updateOwnedTitleDurationWithAudit(
            UUID playerId,
            UUID entryId,
            Instant expiresAt,
            AuditRecord audit,
            Instant now
    );

    boolean softDeleteOwnedTitle(
            UUID playerId,
            UUID entryId,
            String actor,
            Instant now
    );

    CoinAdjustmentResult adjustTitleCoins(
            UUID playerId,
            long delta,
            long maximumBalance,
            String actor,
            String reason,
            UUID orderId,
            Instant now
    );

    List<TitleCoinLedgerRecord> titleCoinLedger(
            UUID playerId,
            int limit
    );

    /**
     * 在单事务中检查单玩家限购、预留有限库存并创建 PREPARED 订单。
     * -1 表示对应限制关闭。
     */
    OrderPreparationStatus prepareOrder(
            PurchaseOrderRecord order,
            long globalStock,
            int perPlayerLimit
    );

    /**
     * 低层幂等订单插入，不执行库存与限购判断。仅供恢复、迁移和无商品
     * 库存语义的内部流程使用；商城购买应使用 {@link #prepareOrder}。
     */
    boolean createOrder(PurchaseOrderRecord order);

    Optional<PurchaseOrderRecord> findOrder(UUID orderId);

    /**
     * 将 PAYMENT_PENDING 的 FREE/称号币订单原子提交为
     * PAYMENT_COMMITTED。称号币余额、不可变流水和订单状态在同一事务；
     * 余额不足时同事务标记 FAILED 并释放库存预留。
     */
    InternalPaymentResult commitInternalPayment(
            UUID orderId,
            String actor,
            String reason,
            Instant now
    );

    List<PurchaseOrderRecord> findRecoverableOrders(int limit);

    /**
     * 统计玩家尚未终结的订单，用于限制重复确认造成的待处理订单堆积。
     */
    long countPendingOrders(UUID playerId);

    long countPlayerPurchases(
            UUID playerId,
            DefinitionId definitionId
    );

    Optional<SaleCounterRecord> findSaleCounter(
            DefinitionId definitionId
    );

    /**
     * CAS 迁移订单状态。转入 FAILED/CANCELLED 时在同一事务释放库存
     * 预留，过期或重复工作线程不会重复释放。
     */
    boolean transitionOrder(
            UUID orderId,
            PurchaseOrderState expected,
            PurchaseOrderState target,
            String failureCode,
            Instant now
    );

    /**
     * 在单个数据库事务中创建仓库条目、把预留库存转为实际售出，并把
     * PAYMENT_COMMITTED 订单标记为 GRANTED。重复恢复同一订单时返回
     * 已存在的最终记录，不重复发放。
     */
    OwnedTitleRecord grantCommittedOrder(
            UUID orderId,
            OwnedTitleRecord title,
            Instant now
    );

    /**
     * 与 {@link #grantCommittedOrder(UUID, OwnedTitleRecord, Instant)}
     * 相同，但首次完成发放时还会在同一事务写入审计。幂等恢复已经
     * GRANTED 的订单时不会重复写审计。
     */
    OwnedTitleRecord grantCommittedOrderWithAudit(
            UUID orderId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    );

    boolean createCard(CardRecord card);

    /**
     * 原子创建一批称号卡并写入一条审计。任一 token 冲突时整个批次回滚。
     */
    List<CardRecord> createCardsWithAudit(
            List<CardRecord> cards,
            AuditRecord audit
    );

    Optional<CardRecord> findCard(String cardToken);

    /**
     * 条件标记卡片已兑换并创建仓库条目；两个操作在同一事务中完成。
     */
    CardRedemptionResult redeemCard(
            String cardToken,
            UUID playerId,
            OwnedTitleRecord title,
            Instant now
    );

    /**
     * 与 {@link #redeemCard(String, UUID, OwnedTitleRecord, Instant)}
     * 相同，但兑换成功时在同一事务写入审计。卡不存在或已兑换时不写审计。
     */
    CardRedemptionResult redeemCardWithAudit(
            String cardToken,
            UUID playerId,
            OwnedTitleRecord title,
            AuditRecord audit,
            Instant now
    );

    AuditRecord appendAudit(AuditRecord audit);

    List<AuditRecord> findAuditByTarget(
            String targetId,
            int limit
    );

    StorageSummary summarize();

    @Override
    void close();
}