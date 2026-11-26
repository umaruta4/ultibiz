package com.ultibiz.core.dashboard;

import java.math.BigDecimal;

/**
 * The threshold rules: a metric breaches a threshold when it is above (or
 * below) the value, and the severity of the threshold decides how loud the
 * alert is.
 */
public final class ThresholdPolicy {

    private ThresholdPolicy() {
    }

    /** One threshold of the catalogue. */
    public record Threshold(String metricKey, String label, String comparison, BigDecimal value,
                            String severity) {
    }

    /** Does the metric breach the threshold? */
    public static boolean breached(Threshold threshold, BigDecimal metric) {
        if (threshold == null || metric == null) {
            return false;
        }
        BigDecimal value = threshold.value();
        return switch (threshold.comparison()) {
            case "GT" -> metric.compareTo(value) > 0;
            case "GTE" -> metric.compareTo(value) >= 0;
            case "LT" -> metric.compareTo(value) < 0;
            case "LTE" -> metric.compareTo(value) <= 0;
            default -> throw new DashboardException(
                    "Unknown comparison: " + threshold.comparison());
        };
    }

    /** The severity of an alert: INFO < WARNING < CRITICAL. */
    public static int severityRank(String severity) {
        return switch (severity == null ? "INFO" : severity) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }
}
