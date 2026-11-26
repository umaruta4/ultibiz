package com.ultibiz.core.ai;

/** One finding of an anomaly rule. */
public record AiInsight(
        String ruleCode,
        String severity,
        String title,
        String evidence,
        double confidence,
        String suggestedAction,
        String entity,
        String entityId) {
}
