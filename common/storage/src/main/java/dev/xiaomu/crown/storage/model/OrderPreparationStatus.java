package dev.xiaomu.crown.storage.model;

/** 原子创建订单和预留库存的结果。 */
public enum OrderPreparationStatus {
    CREATED,
    ORDER_ALREADY_EXISTS,
    OUT_OF_STOCK,
    PLAYER_LIMIT_REACHED
}