package dev.xiaomu.crown.runtime.economy;

import dev.xiaomu.crown.domain.catalog.NamespacedId;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 可替换、可测试的 Mint 报价和异步扣款边界。 */
public interface MintPaymentGateway {
    /**
     * 使用当前 Provider 的货币定义把配置价格固定为订单最小单位金额。
     */
    MintPriceQuote quote(
            NamespacedId currency,
            BigDecimal configuredPrice
    );

    /**
     * 使用当前 Provider 按已经持久化到订单的固定金额执行幂等转账。
     */
    CompletionStage<MintPaymentResult> charge(
            UUID transactionId,
            UUID playerId,
            NamespacedId shopAccount,
            NamespacedId currency,
            long amountMinor,
            String reason,
            Map<String, String> metadata,
            Duration timeout
    );
}