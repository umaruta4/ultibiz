package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.FiscalPeriod;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Fiscal periods. Closing a period is a normal operation; reopening one needs
 * the {@code accounting.period.reopen} permission and is always audited.
 */
@Service
public class FiscalPeriodService {

    public static final String PERMISSION_REOPEN = "accounting.period.reopen";

    private final FiscalPeriodRepository periods;
    private final AuditService audit;
    private final PermissionGate permissions;

    public FiscalPeriodService(FiscalPeriodRepository periods, AuditService audit, PermissionGate permissions) {
        this.periods = periods;
        this.audit = audit;
        this.permissions = permissions;
    }

    public List<FiscalPeriod> list(SiteScope scope, String countryCode) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return periods.list(scope.organizationId(), countryCode);
    }

    public FiscalPeriod byDate(SiteScope scope, String countryCode, java.time.LocalDate date) {
        if (scope.isDenyAll()) {
            return null;
        }
        return periods.findByDate(scope.organizationId(), countryCode, date);
    }

    public void close(SiteScope scope, String actor, long periodId) {
        FiscalPeriod locked = periods.lock(periodId);
        if (locked == null || locked.organizationId() != scope.organizationId()) {
            throw new AccountingException("Fiscal period not found: " + periodId);
        }
        if (!locked.isOpen()) {
            throw new AccountingException("Period " + locked.fiscalYear() + "/" + locked.fiscalMonth()
                    + " is already closed");
        }
        periods.markClosed(periodId, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PERIOD_CLOSE", "fiscal_period",
                String.valueOf(periodId), "{\"period\":\"" + locked.fiscalYear() + "-" + locked.fiscalMonth() + "\"}",
                null));
    }

    public void reopen(SiteScope scope, String actor, long periodId) {
        if (!permissions.allowed(PERMISSION_REOPEN)) {
            throw new AccountingException("Missing permission: " + PERMISSION_REOPEN);
        }
        FiscalPeriod locked = periods.lock(periodId);
        if (locked == null || locked.organizationId() != scope.organizationId()) {
            throw new AccountingException("Fiscal period not found: " + periodId);
        }
        if (locked.status() == com.ultibiz.core.domain.PeriodStatus.OPEN) {
            throw new AccountingException("Period is not closed");
        }
        periods.markReopened(periodId, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PERIOD_REOPEN", "fiscal_period",
                String.valueOf(periodId), "{\"period\":\"" + locked.fiscalYear() + "-" + locked.fiscalMonth() + "\"}",
                null));
    }
}
