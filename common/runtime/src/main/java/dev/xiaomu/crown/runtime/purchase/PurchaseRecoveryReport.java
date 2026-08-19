package dev.xiaomu.crown.runtime.purchase;

/** 一批启动恢复任务的结果汇总。 */
public record PurchaseRecoveryReport(
        int attempted,
        int granted,
        int failed,
        int uncertain,
        int manualIntervention
) {
    public PurchaseRecoveryReport {
        if (attempted < 0
                || granted < 0
                || failed < 0
                || uncertain < 0
                || manualIntervention < 0
                || granted + failed + uncertain
                + manualIntervention > attempted) {
            throw new IllegalArgumentException(
                    "Invalid purchase recovery report");
        }
    }

    public static PurchaseRecoveryReport empty() {
        return new PurchaseRecoveryReport(0, 0, 0, 0, 0);
    }

    public PurchaseRecoveryReport append(
            PurchaseResult result
    ) {
        return switch (result.status()) {
            case GRANTED -> new PurchaseRecoveryReport(
                    attempted + 1,
                    granted + 1,
                    failed,
                    uncertain,
                    manualIntervention);
            case PAYMENT_FAILED, INSUFFICIENT_FUNDS ->
                    new PurchaseRecoveryReport(
                            attempted + 1,
                            granted,
                            failed + 1,
                            uncertain,
                            manualIntervention);
            case PAYMENT_UNCERTAIN ->
                    new PurchaseRecoveryReport(
                            attempted + 1,
                            granted,
                            failed,
                            uncertain + 1,
                            manualIntervention);
            default -> new PurchaseRecoveryReport(
                    attempted + 1,
                    granted,
                    failed,
                    uncertain,
                    manualIntervention + 1);
        };
    }

    public PurchaseRecoveryReport appendFailure() {
        return new PurchaseRecoveryReport(
                attempted + 1,
                granted,
                failed,
                uncertain,
                manualIntervention + 1);
    }
}