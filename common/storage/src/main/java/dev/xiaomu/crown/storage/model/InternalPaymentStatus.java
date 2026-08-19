package dev.xiaomu.crown.storage.model;

/** FREE/称号币内部支付的原子提交结果。 */
public enum InternalPaymentStatus {
    COMMITTED,
    ALREADY_COMMITTED,
    INSUFFICIENT_FUNDS,
    INVALID_STATE
}