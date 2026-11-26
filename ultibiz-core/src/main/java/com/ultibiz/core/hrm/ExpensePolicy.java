package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.util.List;

/**
 * The expense rules: every line is positive and the claim total is the sum of
 * its lines — the stored total can never drift from the lines.
 */
public final class ExpensePolicy {

    private ExpensePolicy() {
    }

    /** One expense line. */
    public record Line(BigDecimal amount, String category) {
    }

    /** The total of the lines; refuses an empty claim and non-positive amounts. */
    public static BigDecimal total(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new HrmException("An expense claim needs at least one line");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new HrmException("An expense line must have a positive amount");
            }
            if (line.category() == null || line.category().isBlank()) {
                throw new HrmException("An expense line needs a category");
            }
            total = total.add(line.amount());
        }
        return total;
    }
}
