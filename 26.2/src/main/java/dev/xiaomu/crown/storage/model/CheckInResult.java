package dev.xiaomu.crown.storage.model;

/** Persisted outcome of a daily check-in attempt. */
public record CheckInResult(Status status, long reward, long balance) {
    public enum Status {
        CLAIMED,
        ALREADY_CLAIMED,
        BALANCE_LIMIT
    }
}
