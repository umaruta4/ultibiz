package com.ultibiz.core.application;

import com.ultibiz.core.domain.NumberingSequence;
import com.ultibiz.core.infrastructure.NumberingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The document-numbering engine.
 *
 * <p>Each sequence is advanced under a row lock ({@code SELECT ... FOR UPDATE})
 * inside a single transaction, so concurrent callers are serialised by the
 * database: numbers are contiguous and never duplicated. When the sequence is
 * periodic (monthly / fiscal year) the counter restarts at {@code start_at} as
 * soon as the period key changes.
 */
@Service
public class NumberingService {

    private final NumberingRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NumberingService(NumberingRepository repository, TransactionTemplate tx) {
        this(repository, tx, Clock.systemDefaultZone());
    }

    public NumberingService(NumberingRepository repository, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        // READ COMMITTED: a locking read must see the latest committed row,
        // otherwise MariaDB aborts with error 1020 under REPEATABLE READ.
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /**
     * Next number for the caller's scope. A site-scoped sequence wins over the
     * organization-wide one; an organization-wide role uses the shared sequence.
     */
    public String next(String documentType, SiteScope scope) {
        if (scope == null || scope.organizationId() <= 0) {
            throw new NumberingException("No organization scope for document type " + documentType);
        }
        Long siteId = scope.restricted() && scope.siteIds().size() == 1
                ? scope.siteIds().iterator().next()
                : null;
        return next(documentType, scope.organizationId(), siteId);
    }

    public String next(String documentType, long organizationId, Long siteId) {
        String result = tx.execute(status -> {
            NumberingSequence locked = repository.lockCandidate(organizationId, siteId, documentType);
            if (locked == null) {
                throw new NumberingException("No numbering sequence for document type '" + documentType
                        + "' in organization " + organizationId);
            }
            LocalDate today = LocalDate.now(clock);
            String periodKey = periodKey(locked, today);
            boolean newPeriod = periodKey != null && !periodKey.equals(locked.lastPeriodKey());
            long nextNumber = newPeriod ? locked.startAt() : locked.counter() + 1;
            repository.updateCounter(locked.id(), nextNumber, periodKey);
            return format(locked, nextNumber, periodKey);
        });
        if (result == null) {
            throw new NumberingException("Numbering failed for document type '" + documentType + "'");
        }
        return result;
    }

    /** Period bucket of a sequence, or {@code null} for non-periodic ones. */
    public static String periodKey(NumberingSequence sequence, LocalDate date) {
        if (!sequence.periodic()) {
            return null;
        }
        if ("MONTHLY".equalsIgnoreCase(sequence.periodType())) {
            return YearMonth.from(date).toString();
        }
        int startMonth = sequence.fiscalYearStartMonth() <= 0 ? 1 : sequence.fiscalYearStartMonth();
        int fiscalYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return String.valueOf(fiscalYear);
    }

    /** Renders the final document number, e.g. {@code SO-2026-000001}. */
    public static String format(NumberingSequence sequence, long number, String periodKey) {
        StringBuilder sb = new StringBuilder();
        if (sequence.prefix() != null) {
            sb.append(sequence.prefix());
        }
        if (periodKey != null) {
            sb.append(periodKey).append('-');
        }
        sb.append(String.format("%0" + Math.max(1, sequence.padding()) + "d", number));
        if (sequence.suffix() != null) {
            sb.append(sequence.suffix());
        }
        return sb.toString();
    }
}
