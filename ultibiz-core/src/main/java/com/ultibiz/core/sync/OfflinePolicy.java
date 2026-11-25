package com.ultibiz.core.sync;

import java.util.Set;

/**
 * What may be done while offline. Financial postings, stock transfers and
 * invoice issuance need the ledger, so they are online-only; drafts,
 * timesheets, attendance, stock counts, tickets and notes are offline-capable.
 */
public final class OfflinePolicy {

    public static final Set<String> ONLINE_ONLY = Set.of(
            "accounting_posting", "journal_entry", "stock_transfer", "invoice", "bill",
            "payment", "goods_receipt", "work_order_issue", "work_order_complete");

    public static final Set<String> OFFLINE_CAPABLE = Set.of(
            "draft", "note", "timesheet", "attendance", "stock_count", "ticket");

    private OfflinePolicy() {
    }

    public static boolean isOnlineOnly(String entity) {
        return entity != null && ONLINE_ONLY.contains(entity);
    }

    public static boolean isOfflineCapable(String entity) {
        return entity != null && OFFLINE_CAPABLE.contains(entity);
    }

    /** The operator-readable reason why the operation is refused. */
    public static String rejectionMessage(String entity) {
        return switch (entity == null ? "" : entity) {
            case "accounting_posting", "journal_entry" ->
                    "Accounting postings need an online connection: the ledger is only written online";
            case "stock_transfer" ->
                    "Stock transfers need an online connection: the stock ledger is only moved online";
            case "invoice", "bill", "payment", "goods_receipt" ->
                    "Invoices, bills and stock receipts need an online connection";
            default ->
                    "This operation needs an online connection";
        };
    }
}
