package com.ultibiz.core.ai;

import java.util.List;
import java.util.Map;

/**
 * A write action the assistant proposes. It is never executed silently: the
 * diff is shown, a human confirms, and the action then runs through the same
 * application service as the user interface.
 */
public record ProposedAction(
        String toolName,
        String summary,
        Map<String, String> before,
        Map<String, String> after,
        List<String> diffLines,
        String serviceCall) {
}
