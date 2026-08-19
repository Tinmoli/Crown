package dev.xiaomu.crown.runtime.purchase;

/** 平台命令与 GUI 可稳定映射到语言键的购买结果。 */
public enum PurchaseStatus {
    GRANTED,
    DISABLED,
    HIDDEN,
    NOT_ON_SALE,
    PERMISSION_DENIED,
    TOO_MANY_PENDING,
    OUT_OF_STOCK,
    PLAYER_LIMIT_REACHED,
    INSUFFICIENT_FUNDS,
    PAYMENT_FAILED,
    PAYMENT_UNCERTAIN,
    ORDER_CONFLICT,
    INVALID_STATE
}