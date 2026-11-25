package com.ultibiz.core.application;

import java.util.List;

/**
 * Result of a CSV import run: how many rows were seen, how many were accepted,
 * and every rejected row with the reason.
 */
public record ImportReport(
        int totalRows,
        int acceptedRows,
        int importedRows,
        boolean dryRun,
        List<RowError> errors) {

    public record RowError(int rowNumber, String code, String field, String message) {
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public int failedRows() {
        return errors.size();
    }
}
