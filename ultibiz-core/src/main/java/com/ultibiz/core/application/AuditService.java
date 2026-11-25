package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.AuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail. Every mutating action in the system writes one
 * record here; the table is never updated or deleted by application code.
 */
@Service
public class AuditService {

    private final AuditRepository audit;

    public AuditService(AuditRepository audit) {
        this.audit = audit;
    }

    public void record(AuditEvent event) {
        audit.insert(event);
    }

    public List<AuditEvent> recent(long organizationId, int limit) {
        return audit.recent(organizationId, limit);
    }
}
