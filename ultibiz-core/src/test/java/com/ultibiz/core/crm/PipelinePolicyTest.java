package com.ultibiz.core.crm;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The pipeline rules. */
class PipelinePolicyTest {

    private static PipelinePolicy.Stage stage(String code, int position, boolean won,
                                              boolean lost) {
        return new PipelinePolicy.Stage(position, code, position,
                new BigDecimal("50"), won, lost);
    }

    @Test
    void thePipelineMovesForwardOnly() {
        var newStage = stage("NEW", 1, false, false);
        var proposal = stage("PROPOSAL", 3, false, false);
        var won = stage("WON", 5, true, false);
        assertDoesNotThrow(() -> PipelinePolicy.checkMove(newStage, proposal,
                new BigDecimal("1000"), null));
        assertThrows(CrmException.class, () -> PipelinePolicy.checkMove(proposal, newStage,
                new BigDecimal("1000"), null), "never backwards");
        assertThrows(CrmException.class, () -> PipelinePolicy.checkMove(newStage, won,
                BigDecimal.ZERO, null), "a won opportunity needs an amount");
        assertDoesNotThrow(() -> PipelinePolicy.checkMove(newStage, won,
                new BigDecimal("1000"), null));
        assertThrows(CrmException.class, () -> PipelinePolicy.checkMove(won, proposal,
                new BigDecimal("1000"), null), "a closed opportunity does not move");
    }

    @Test
    void aLostOpportunityNeedsAReason() {
        var newStage = stage("NEW", 1, false, false);
        var lost = stage("LOST", 6, false, true);
        assertThrows(CrmException.class, () -> PipelinePolicy.checkMove(newStage, lost,
                new BigDecimal("1000"), " "));
        assertDoesNotThrow(() -> PipelinePolicy.checkMove(newStage, lost,
                new BigDecimal("1000"), "price"));
    }

    @Test
    void theWeightedPipeline() {
        assertEquals(new BigDecimal("1750.00"), PipelinePolicy.weightedValue(List.of(
                new PipelinePolicy.Row(new BigDecimal("10000"), new BigDecimal("10")),
                new PipelinePolicy.Row(new BigDecimal("3000"), new BigDecimal("25")))));
    }
}
