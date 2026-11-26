package com.ultibiz.core.crm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The pipeline rules: an opportunity moves forward through the stages (or is
 * lost), never backwards; a won opportunity needs an amount, a lost one a
 * reason. The weighted pipeline is the open amount times the stage
 * probability.
 */
public final class PipelinePolicy {

    private PipelinePolicy() {
    }

    /** One stage of the pipeline. */
    public record Stage(long id, String code, int position, BigDecimal probability,
                        boolean won, boolean lost) {
    }

    /** The next stage of an opportunity; anything else is refused. */
    public static void checkMove(Stage from, Stage to, BigDecimal amount, String lostReason) {
        if (to.lost()) {
            if (lostReason == null || lostReason.isBlank()) {
                throw new CrmException("A lost opportunity needs a reason");
            }
            return;
        }
        if (from.won() || from.lost()) {
            throw new CrmException("A closed opportunity does not move");
        }
        if (to.position() <= from.position() && !to.won()) {
            throw new CrmException("The pipeline does not move backwards ("
                    + from.code() + " → " + to.code() + ")");
        }
        if (to.won() && (amount == null || amount.signum() <= 0)) {
            throw new CrmException("A won opportunity needs an amount");
        }
    }

    /** The weighted value of the open pipeline. */
    public static BigDecimal weightedValue(List<Row> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (Row row : rows) {
            total = total.add(row.amount().multiply(row.probability())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        return total;
    }

    /** One pipeline row: amount and the probability of its stage. */
    public record Row(BigDecimal amount, BigDecimal probability) {
    }
}
