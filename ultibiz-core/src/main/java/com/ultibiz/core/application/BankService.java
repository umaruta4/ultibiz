package com.ultibiz.core.application;

import com.ultibiz.core.domain.ApPayment;
import com.ultibiz.core.domain.ArReceipt;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.BankAccount;
import com.ultibiz.core.domain.BankStatementLine;
import com.ultibiz.core.domain.MatchRule;
import com.ultibiz.core.infrastructure.ApRepository;
import com.ultibiz.core.infrastructure.ArRepository;
import com.ultibiz.core.infrastructure.BankRepository;
import com.ultibiz.core.infrastructure.MatchRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bank and cash: statement import (CSV) and reconciliation against the
 * receipts and payments that were posted earlier.
 */
@Service
public class BankService {

    private final BankRepository banks;
    private final MatchRuleRepository matchRules;
    private final ArRepository ar;
    private final ApRepository ap;
    private final AuditService audit;

    public BankService(BankRepository banks, MatchRuleRepository matchRules, ArRepository ar,
                       ApRepository ap, AuditService audit) {
        this.banks = banks;
        this.matchRules = matchRules;
        this.ar = ar;
        this.ap = ap;
        this.audit = audit;
    }

    public List<BankAccount> accounts(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return banks.accounts(scope.organizationId());
    }

    /** Imports a statement from CSV (date, description, reference, amount). */
    public String importStatement(SiteScope scope, String actor, long bankAccountId, String csv) {
        List<String[]> rows = MasterDataCsvService.parse(csv);
        String batch = UUID.randomUUID().toString();
        int imported = 0;
        boolean headerSkipped = false;
        for (String[] row : rows) {
            if (row.length < 2) {
                continue;
            }
            LocalDate date;
            BigDecimal amount;
            try {
                date = LocalDate.parse(row[0].trim());
                amount = new BigDecimal(row[row.length - 1].trim());
            } catch (Exception ex) {
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                continue;
            }
            String description = row[1].trim();
            String reference = row.length > 2 ? row[2].trim() : null;
            if (reference != null && reference.isEmpty()) {
                reference = null;
            }
            banks.insertStatementLine(bankAccountId, batch, date, description, reference, amount, "IDR");
            imported++;
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "BANK_STATEMENT_IMPORT", "bank_account",
                String.valueOf(bankAccountId), "{\"batch\":\"" + batch + "\",\"lines\":" + imported + "}", null));
        return batch;
    }

    /**
     * Reconciles the unreconciled statement lines against the receipts and
     * payments that were posted earlier, by amount and date.
     */
    public ReconcileReport reconcile(SiteScope scope, String actor, long bankAccountId) {
        List<BankStatementLine> lines = banks.unreconciledLines(bankAccountId);
        MatchRule receiptRule = matchRules.find(scope.organizationId(), "RECEIPT_MATCH");
        MatchRule paymentRule = matchRules.find(scope.organizationId(), "PAYMENT_MATCH");
        BigDecimal receiptTolerance = receiptRule == null ? BigDecimal.ZERO : receiptRule.toleranceAmount();
        BigDecimal paymentTolerance = paymentRule == null ? BigDecimal.ZERO : paymentRule.toleranceAmount();

        int matched = 0;
        int unmatched = 0;
        for (BankStatementLine line : lines) {
            boolean isInflow = line.amount().signum() > 0;
            BigDecimal tolerance = isInflow ? receiptTolerance : paymentTolerance;
            BigDecimal min = line.amount().abs().subtract(tolerance);
            BigDecimal max = line.amount().abs().add(tolerance);
            LocalDate from = line.statementDate().minusDays(3);
            LocalDate to = line.statementDate().plusDays(3);

            Long matchedId = null;
            if (isInflow) {
                List<ArReceipt> candidates = ar.receiptsByAmountRange(scope.organizationId(), min, max, from, to);
                if (candidates.size() == 1) {
                    matchedId = candidates.get(0).id();
                }
            } else {
                List<ApPayment> candidates = ap.paymentsByAmountRange(scope.organizationId(), min, max, from, to);
                if (candidates.size() == 1) {
                    matchedId = candidates.get(0).id();
                }
            }

            if (matchedId != null) {
                banks.markReconciled(line.id(), matchedId);
                matched++;
            } else {
                unmatched++;
            }
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "BANK_RECONCILE", "bank_account",
                String.valueOf(bankAccountId), "{\"matched\":" + matched + ",\"unmatched\":" + unmatched + "}",
                null));
        return new ReconcileReport(matched, unmatched);
    }

    public List<BankStatementLine> statementLines(SiteScope scope, long bankAccountId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return banks.statementLines(bankAccountId, null);
    }

    /** Result of a reconciliation run. */
    public record ReconcileReport(int matched, int unmatched) {
    }
}
