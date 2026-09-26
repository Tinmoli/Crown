package dev.xiaomu.crown.storage.model;

public record TitleDeletionResult(Status status, long refund) {
    public enum Status { DELETED, NOT_OWNED, BALANCE_LIMIT }
}
