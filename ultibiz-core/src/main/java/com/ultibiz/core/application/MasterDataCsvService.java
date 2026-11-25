package com.ultibiz.core.application;

import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.ProductRepository;
import com.ultibiz.core.infrastructure.UomRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * CSV import/export for the product and party masters.
 *
 * <p>Import runs in two phases: validate every row, then persist the rows that
 * passed. With {@code dryRun} the validation report is produced but nothing is
 * written — the report is identical either way.
 */
@Service
public class MasterDataCsvService {

    private static final Set<String> PRODUCT_TYPES = Set.of("GOODS", "SERVICE", "KIT");
    private static final Set<String> PARTY_TYPES = Set.of("CUSTOMER", "SUPPLIER", "BOTH");

    private final ProductRepository products;
    private final PartyRepository parties;
    private final UomRepository uoms;

    public MasterDataCsvService(ProductRepository products, PartyRepository parties, UomRepository uoms) {
        this.products = products;
        this.parties = parties;
        this.uoms = uoms;
    }

    public String exportProducts(List<Product> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("code,name,product_type,uom_code,barcode,standard_cost,currency,track_inventory,batch_tracked,serial_tracked,description\n");
        for (Product p : rows) {
            sb.append(csv(p.code())).append(',')
                    .append(csv(p.name())).append(',')
                    .append(csv(p.productType())).append(',')
                    .append(csv(p.uomCode())).append(',')
                    .append(csv(p.barcode())).append(',')
                    .append(p.standardCost() == null ? "" : p.standardCost().toPlainString()).append(',')
                    .append(csv(p.currency())).append(',')
                    .append(p.trackInventory()).append(',')
                    .append(p.batchTracked()).append(',')
                    .append(p.serialTracked()).append(',')
                    .append(csv(p.description())).append('\n');
        }
        return sb.toString();
    }

    public String exportParties(List<Party> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("code,name,party_type,tax_id,country_code,email,phone,credit_limit,payment_term_days,currency\n");
        for (Party p : rows) {
            sb.append(csv(p.code())).append(',')
                    .append(csv(p.name())).append(',')
                    .append(csv(p.partyType())).append(',')
                    .append(csv(p.taxId())).append(',')
                    .append(csv(p.countryCode())).append(',')
                    .append(csv(p.email())).append(',')
                    .append(csv(p.phone())).append(',')
                    .append(p.creditLimit() == null ? "" : p.creditLimit().toPlainString()).append(',')
                    .append(p.paymentTermDays()).append(',')
                    .append(csv(p.currency())).append('\n');
        }
        return sb.toString();
    }

    /** Renders the validation errors as a downloadable CSV. */
    public String errorCsv(ImportReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("row_number,code,field,message\n");
        for (ImportReport.RowError e : report.errors()) {
            sb.append(e.rowNumber()).append(',')
                    .append(csv(e.code())).append(',')
                    .append(csv(e.field())).append(',')
                    .append(csv(e.message())).append('\n');
        }
        return sb.toString();
    }

    public ImportReport importProducts(SiteScope scope, String csv, boolean dryRun) {
        List<String[]> rows = parse(csv);
        List<ImportReport.RowError> errors = new ArrayList<>();
        int accepted = 0;
        int imported = 0;
        List<ProductDraft> drafts = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();

        if (rows.isEmpty()) {
            errors.add(new ImportReport.RowError(0, "", "file", "CSV is empty or has no header row"));
            return new ImportReport(0, 0, 0, dryRun, errors);
        }
        String[] header = rows.get(0);
        Map<String, Integer> index = headerIndex(header);
        int rowNumber = 1;
        for (int i = 1; i < rows.size(); i++) {
            rowNumber++;
            String[] row = rows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            String code = value(index, row, "code");
            String name = value(index, row, "name");
            String type = orDefault(value(index, row, "product_type"), "GOODS").toUpperCase();
            String uom = value(index, row, "uom_code");
            String cost = value(index, row, "standard_cost");
            String currency = orDefault(value(index, row, "currency"), "IDR").toUpperCase();

            boolean valid = true;
            valid &= require(errors, rowNumber, code, "code", code != null && !code.isBlank(), "Code is required");
            valid &= require(errors, rowNumber, code, "name", name != null && !name.isBlank(), "Name is required");
            valid &= require(errors, rowNumber, code, "product_type", PRODUCT_TYPES.contains(type),
                    "Unknown product type '" + type + "'");
            valid &= require(errors, rowNumber, code, "uom_code", uom != null && uoms.codes().contains(uom),
                    "Unknown UOM '" + uom + "'");
            valid &= require(errors, rowNumber, code, "currency", currency.length() == 3,
                    "Currency must be a 3-letter ISO code");
            BigDecimal parsedCost = null;
            if (cost != null && !cost.isBlank()) {
                try {
                    parsedCost = new BigDecimal(cost.trim());
                } catch (NumberFormatException ex) {
                    valid = false;
                    errors.add(new ImportReport.RowError(rowNumber, code, "standard_cost",
                            "Not a number: '" + cost + "'"));
                }
            }
            if (code != null && !code.isBlank() && !seenCodes.add(code)) {
                valid = false;
                errors.add(new ImportReport.RowError(rowNumber, code, "code", "Duplicate code in file"));
            }
            if (code != null && !code.isBlank() && products.findByCode(scope, code).isPresent()) {
                valid = false;
                errors.add(new ImportReport.RowError(rowNumber, code, "code", "Code already exists"));
            }
            if (!valid) {
                continue;
            }
            accepted++;
            drafts.add(new ProductDraft(null, code, name, type, uom, value(index, row, "barcode"),
                    bool(value(index, row, "batch_tracked")), bool(value(index, row, "serial_tracked")),
                    boolOrDefault(value(index, row, "track_inventory"), true),
                    parsedCost == null ? BigDecimal.ZERO : parsedCost, currency, null,
                    value(index, row, "description"), true));
        }

        if (!dryRun) {
            for (ProductDraft draft : drafts) {
                products.insert(scope, draft);
                imported++;
            }
        }
        return new ImportReport(Math.max(0, rowNumber - 1), accepted, imported, dryRun, errors);
    }

    public ImportReport importParties(SiteScope scope, String csv, boolean dryRun) {
        List<String[]> rows = parse(csv);
        List<ImportReport.RowError> errors = new ArrayList<>();
        List<PartyDraft> drafts = new ArrayList<>();
        int accepted = 0;
        int imported = 0;
        Set<String> seenCodes = new LinkedHashSet<>();

        if (rows.isEmpty()) {
            errors.add(new ImportReport.RowError(0, "", "file", "CSV is empty or has no header row"));
            return new ImportReport(0, 0, 0, dryRun, errors);
        }
        Map<String, Integer> index = headerIndex(rows.get(0));
        int rowNumber = 1;
        for (int i = 1; i < rows.size(); i++) {
            rowNumber++;
            String[] row = rows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            String code = value(index, row, "code");
            String name = value(index, row, "name");
            String type = orDefault(value(index, row, "party_type"), "BOTH").toUpperCase();
            String country = orDefault(value(index, row, "country_code"), "ID").toUpperCase();
            String currency = orDefault(value(index, row, "currency"), "IDR").toUpperCase();
            String terms = value(index, row, "payment_term_days");
            String limit = value(index, row, "credit_limit");

            boolean valid = true;
            valid &= require(errors, rowNumber, code, "code", code != null && !code.isBlank(), "Code is required");
            valid &= require(errors, rowNumber, code, "name", name != null && !name.isBlank(), "Name is required");
            valid &= require(errors, rowNumber, code, "party_type", PARTY_TYPES.contains(type),
                    "Unknown party type '" + type + "'");
            valid &= require(errors, rowNumber, code, "country_code", country.length() == 2,
                    "Country must be a 2-letter ISO code");
            valid &= require(errors, rowNumber, code, "currency", currency.length() == 3,
                    "Currency must be a 3-letter ISO code");
            int paymentTerms = 30;
            if (terms != null && !terms.isBlank()) {
                try {
                    paymentTerms = Integer.parseInt(terms.trim());
                } catch (NumberFormatException ex) {
                    valid = false;
                    errors.add(new ImportReport.RowError(rowNumber, code, "payment_term_days",
                            "Not an integer: '" + terms + "'"));
                }
            }
            BigDecimal creditLimit = BigDecimal.ZERO;
            if (limit != null && !limit.isBlank()) {
                try {
                    creditLimit = new BigDecimal(limit.trim());
                } catch (NumberFormatException ex) {
                    valid = false;
                    errors.add(new ImportReport.RowError(rowNumber, code, "credit_limit",
                            "Not a number: '" + limit + "'"));
                }
            }
            if (code != null && !code.isBlank() && !seenCodes.add(code)) {
                valid = false;
                errors.add(new ImportReport.RowError(rowNumber, code, "code", "Duplicate code in file"));
            }
            if (code != null && !code.isBlank() && parties.findByCode(scope, code).isPresent()) {
                valid = false;
                errors.add(new ImportReport.RowError(rowNumber, code, "code", "Code already exists"));
            }
            if (!valid) {
                continue;
            }
            accepted++;
            drafts.add(new PartyDraft(null, code, name, type, value(index, row, "tax_id"), country,
                    value(index, row, "email"), value(index, row, "phone"), creditLimit,
                    paymentTerms, currency, true));
        }

        if (!dryRun) {
            for (PartyDraft draft : drafts) {
                parties.insert(scope, draft);
                imported++;
            }
        }
        return new ImportReport(Math.max(0, rowNumber - 1), accepted, imported, dryRun, errors);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean require(List<ImportReport.RowError> errors, int row, String code,
                                   String field, boolean condition, String message) {
        if (!condition) {
            errors.add(new ImportReport.RowError(row, code == null ? "" : code, field, message));
            return false;
        }
        return true;
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim().toLowerCase(), i);
        }
        return index;
    }

    private static String value(Map<String, Integer> index, String[] row, String column) {
        Integer i = index.get(column);
        if (i == null || i >= row.length) {
            return null;
        }
        String v = row[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean bool(String value) {
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim()));
    }

    private static boolean boolOrDefault(String value, boolean fallback) {
        return value == null || value.isBlank() ? fallback : bool(value);
    }

    private static boolean isBlankRow(String[] row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Minimal RFC-4180-ish parser: quoted fields, escaped quotes, CRLF tolerated. */
    static List<String[]> parse(String csv) {
        List<String[]> rows = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return rows;
        }
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        String[] lines = csv.split("\r?\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            field.append('"');
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
            current.add(field.toString());
            field.setLength(0);
            if (inQuotes) {
                // quoted field continued on the next line
                continue;
            }
            rows.add(current.toArray(new String[0]));
            current = new ArrayList<>();
        }
        if (!current.isEmpty()) {
            rows.add(current.toArray(new String[0]));
        }
        // a file ending with a newline must not produce an empty trailing row
        while (!rows.isEmpty()) {
            String[] last = rows.get(rows.size() - 1);
            boolean blank = true;
            for (String cell : last) {
                if (cell != null && !cell.isBlank()) {
                    blank = false;
                    break;
                }
            }
            if (!blank) {
                break;
            }
            rows.remove(rows.size() - 1);
        }
        return rows;
    }
}
