package com.ultibiz.core.application;

import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxServiceTest {

    @Test
    void computesTaxFromTheEffectiveRule() {
        TaxRuleRepository repository = mock(TaxRuleRepository.class);
        TaxRule rule = new TaxRule(7L, "PPN_STANDARD", "ID", "VAT 11% standard",
                new BigDecimal("0.110000"), LocalDate.of(2020, 1, 1), null, 10);
        when(repository.resolve(anyLong(), eq("ID"), eq("PPN_STANDARD"), any(LocalDate.class)))
                .thenReturn(Optional.of(rule));

        TaxService service = new TaxService(repository);
        List<TaxLine> lines = service.compute(1L, "ID",
                List.of(new TaxableLine("PPN_STANDARD", new BigDecimal("100000.00"))),
                LocalDate.of(2026, 11, 25), true);

        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("11000.00"), lines.get(0).taxAmount());
        assertEquals(rule, lines.get(0).rule());
    }

    @Test
    void nonTaxableCategoryProducesZeroTax() {
        TaxRuleRepository repository = mock(TaxRuleRepository.class);
        when(repository.resolve(anyLong(), eq("ID"), eq("PPN_EXEMPT"), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        TaxService service = new TaxService(repository);
        List<TaxLine> lines = service.compute(1L, "ID",
                List.of(new TaxableLine("PPN_EXEMPT", new BigDecimal("50000.00"))),
                LocalDate.of(2026, 11, 25), true);

        assertEquals(1, lines.size());
        assertEquals(BigDecimal.ZERO, lines.get(0).taxAmount());
        assertEquals(null, lines.get(0).rule());
    }
}
