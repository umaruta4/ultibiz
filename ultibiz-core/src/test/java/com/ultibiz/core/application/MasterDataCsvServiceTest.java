package com.ultibiz.core.application;

import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.ProductRepository;
import com.ultibiz.core.infrastructure.UomRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MasterDataCsvServiceTest {

    private MasterDataCsvService service(ProductRepository products, UomRepository uoms) {
        return new MasterDataCsvService(products, mock(PartyRepository.class), uoms);
    }

    @Test
    void parserHandlesQuotedFieldsAndEmbeddedCommas() {
        List<String[]> rows = MasterDataCsvService.parse("code,name\nA-1,\"Widget, large\"\nA-2,Plain\n");
        assertEquals(3, rows.size());
        assertEquals("Widget, large", rows.get(1)[1]);
        assertEquals("Plain", rows.get(2)[1]);
    }

    @Test
    void parserHandlesEscapedQuotes() {
        List<String[]> rows = MasterDataCsvService.parse("code,name\nA-1,\"He said \"\"hi\"\"\"\n");
        assertEquals("He said \"hi\"", rows.get(1)[1]);
    }

    @Test
    void importReportsRowLevelErrors() {
        ProductRepository products = mock(ProductRepository.class);
        UomRepository uoms = mock(UomRepository.class);
        when(uoms.codes()).thenReturn(List.of("PCS", "BOX"));
        when(products.findByCode(any(), anyString())).thenReturn(Optional.empty());

        String csv = """
                code,name,product_type,uom_code,standard_cost,currency
                ,Missing code,GOODS,PCS,1000,IDR
                BRG-9,Bad type,MATERIAL,PCS,1000,IDR
                BRG-8,Bad uom,GOODS,WEIGHT,1000,IDR
                BRG-7,Bad cost,GOODS,PCS,not-a-number,IDR
                BRG-6,Good product,GOODS,PCS,2500,IDR
                """;
        ImportReport report = service(products, uoms).importProducts(
                new SiteScope(1L, java.util.Set.of(1L), true), csv, true);

        assertTrue(report.hasErrors());
        assertEquals(4, report.failedRows());
        assertEquals(1, report.acceptedRows());
        assertEquals(0, report.importedRows());
        assertTrue(report.dryRun());
    }

    @Test
    void importRejectsDuplicateCodeInsideTheFile() {
        ProductRepository products = mock(ProductRepository.class);
        UomRepository uoms = mock(UomRepository.class);
        when(uoms.codes()).thenReturn(List.of("PCS"));
        when(products.findByCode(any(), anyString())).thenReturn(Optional.empty());

        String csv = """
                code,name,product_type,uom_code,standard_cost,currency
                BRG-1,First,GOODS,PCS,1000,IDR
                BRG-1,Second,GOODS,PCS,2000,IDR
                """;
        ImportReport report = service(products, uoms).importProducts(
                new SiteScope(1L, java.util.Set.of(1L), true), csv, true);

        assertTrue(report.hasErrors());
        assertEquals("Duplicate code in file", report.errors().get(0).message());
    }

    @Test
    void exportContainsHeaderAndRows() {
        ProductRepository products = mock(ProductRepository.class);
        UomRepository uoms = mock(UomRepository.class);
        Product product = new Product(1L, 1L, null, "BRG-1", "Bearing", "GOODS", "PCS", null,
                false, false, true, new BigDecimal("45000.0000"), "IDR", null, null, true, 0L);
        String csv = service(products, uoms).exportProducts(List.of(product));

        assertTrue(csv.startsWith("code,name,product_type,uom_code"));
        assertTrue(csv.contains("BRG-1,Bearing,GOODS,PCS"));
        assertFalse(csv.contains("null"));
    }
}
