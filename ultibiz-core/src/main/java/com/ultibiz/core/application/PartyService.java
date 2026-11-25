package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.PartyAddress;
import com.ultibiz.core.domain.PartyContact;
import com.ultibiz.core.infrastructure.PartyRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Party master (customers / suppliers) use cases. */
@Service
public class PartyService {

    private static final Set<String> TYPES = Set.of("CUSTOMER", "SUPPLIER", "BOTH");

    private final PartyRepository parties;
    private final AuditService audit;

    public PartyService(PartyRepository parties, AuditService audit) {
        this.parties = parties;
        this.audit = audit;
    }

    public PageResult<Party> search(SiteScope scope, String search, String partyType,
                                    boolean activeOnly, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        long total = parties.count(scope, search, partyType, activeOnly);
        List<Party> items = parties.list(scope, search, partyType, activeOnly, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public List<Party> all(SiteScope scope, String search, String partyType, boolean activeOnly) {
        return parties.list(scope, search, partyType, activeOnly, 10000, 0);
    }

    public Party create(SiteScope scope, String actor, PartyDraft draft) {
        validate(draft);
        if (parties.findByCode(scope, draft.code()).isPresent()) {
            throw new MasterDataException("Party code already exists: " + draft.code());
        }
        long id = parties.insert(scope, draft);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PARTY_CREATE", "party",
                String.valueOf(id), "{\"code\":\"" + draft.code() + "\"}", null));
        return parties.findById(scope, id).orElseThrow(() -> new MasterDataException("Party not found after insert"));
    }

    public boolean update(SiteScope scope, String actor, long id, PartyDraft draft, long expectedVersion) {
        validate(draft);
        boolean updated = parties.update(scope, id, draft, expectedVersion);
        if (!updated) {
            throw new MasterDataException("Party was changed by someone else (row_version mismatch) or does not exist");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PARTY_UPDATE", "party",
                String.valueOf(id), "{\"code\":\"" + draft.code() + "\"}", null));
        return true;
    }

    public boolean delete(SiteScope scope, String actor, long id) {
        boolean deleted = parties.softDelete(scope, id);
        if (!deleted) {
            throw new MasterDataException("Party not found or already deleted");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PARTY_DELETE", "party",
                String.valueOf(id), null, null));
        return true;
    }

    public List<PartyAddress> addresses(long partyId) {
        return parties.addresses(partyId);
    }

    public List<PartyContact> contacts(long partyId) {
        return parties.contacts(partyId);
    }

    private void validate(PartyDraft draft) {
        if (draft == null) {
            throw new MasterDataException("No party data supplied");
        }
        if (draft.code() == null || draft.code().isBlank()) {
            throw new MasterDataException("Party code is required");
        }
        if (draft.name() == null || draft.name().isBlank()) {
            throw new MasterDataException("Party name is required");
        }
        if (draft.partyType() == null || !TYPES.contains(draft.partyType().toUpperCase())) {
            throw new MasterDataException("Unknown party type: " + draft.partyType());
        }
        if (draft.countryCode() == null || draft.countryCode().length() != 2) {
            throw new MasterDataException("Party country must be a 2-letter ISO code");
        }
    }
}
