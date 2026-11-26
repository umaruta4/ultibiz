package com.ultibiz.core.application;

import com.ultibiz.core.crm.CrmException;
import com.ultibiz.core.crm.LeadPolicy;
import com.ultibiz.core.crm.PipelinePolicy;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.CrmRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CRM plugin: leads that qualify and convert, a pipeline of opportunities
 * with weighted values, activities, and the quote hand-off to Sales when an
 * opportunity is ready to be ordered.
 */
@Service
public class CrmService {

    private final CrmRepository crm;
    private final PartyService parties;
    private final SalesOrderService salesOrders;
    private final AuditService audit;

    public CrmService(CrmRepository crm, PartyService parties, SalesOrderService salesOrders,
                      AuditService audit) {
        this.crm = crm;
        this.parties = parties;
        this.salesOrders = salesOrders;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ leads

    @Transactional
    public long createLead(SiteScope scope, String actor, String code, String company,
                           String contactName, String email, String phone, String source,
                           Long territoryId, Long campaignId) {
        if (company == null || company.isBlank()) {
            throw new CrmException("A lead needs a company");
        }
        long id = crm.createLead(scope.organizationId(), code, company.trim(), contactName,
                email, phone, source == null || source.isBlank() ? "MANUAL" : source,
                territoryId, campaignId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_LEAD_CREATE",
                "crm_lead", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    @Transactional
    public void qualifyLead(SiteScope scope, String actor, long leadId) {
        CrmRepository.Lead lead = lead(scope, leadId);
        LeadPolicy.checkQualifiable(lead.company(), lead.email(), lead.phone(), lead.status());
        crm.updateLeadStatus(scope.organizationId(), leadId, "QUALIFIED", null, null);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_LEAD_QUALIFY",
                "crm_lead", String.valueOf(leadId), null, null));
    }

    @Transactional
    public void loseLead(SiteScope scope, String actor, long leadId, String reason) {
        CrmRepository.Lead lead = lead(scope, leadId);
        if ("CONVERTED".equals(lead.status())) {
            throw new CrmException("A converted lead cannot be lost");
        }
        if (reason == null || reason.isBlank()) {
            throw new CrmException("A lost lead needs a reason");
        }
        crm.updateLeadStatus(scope.organizationId(), leadId, "LOST", reason, null);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_LEAD_LOSE",
                "crm_lead", String.valueOf(leadId), "{\"reason\":\"" + reason + "\"}", null));
    }

    /**
     * Converts a qualified lead: a customer party is created in the master
     * data and an opportunity opens at the first pipeline stage.
     */
    @Transactional
    public long convertLead(SiteScope scope, String actor, long leadId, String opportunityName,
                            BigDecimal amount, LocalDate expectedClose) {
        CrmRepository.Lead lead = lead(scope, leadId);
        LeadPolicy.checkConvertible(lead.status());

        // the customer in the master data — the code follows the lead
        var party = parties.create(scope, actor, new PartyDraft(null,
                "CUST-" + lead.code(), lead.company(), "CUSTOMER", null, "ID",
                lead.email(), lead.phone(), BigDecimal.ZERO, 30, "IDR", true));
        crm.updateLeadStatus(scope.organizationId(), leadId, "CONVERTED", null, party.id());

        CrmRepository.Stage first = crm.stages(scope.organizationId()).stream()
                .filter(s -> !s.won() && !s.lost()).findFirst()
                .orElseThrow(() -> new CrmException("The pipeline has no open stage"));
        long opportunityId = crm.createOpportunity(scope.organizationId(), "OPP-" + lead.code(),
                opportunityName == null || opportunityName.isBlank()
                        ? lead.company() + " opportunity" : opportunityName,
                leadId, party.id(), first.id(),
                amount == null ? BigDecimal.ZERO : amount, "IDR", expectedClose, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_LEAD_CONVERT",
                "crm_lead", String.valueOf(leadId),
                "{\"party\":\"" + party.code() + "\",\"opportunityId\":" + opportunityId + "}", null));
        return opportunityId;
    }

    // ------------------------------------------------------------------ opportunities

    @Transactional
    public long createOpportunity(SiteScope scope, String actor, String code, String name,
                                  Long partyId, BigDecimal amount, LocalDate expectedClose) {
        if (name == null || name.isBlank()) {
            throw new CrmException("An opportunity needs a name");
        }
        CrmRepository.Stage first = crm.stages(scope.organizationId()).stream()
                .filter(s -> !s.won() && !s.lost()).findFirst()
                .orElseThrow(() -> new CrmException("The pipeline has no open stage"));
        long id = crm.createOpportunity(scope.organizationId(), code, name.trim(), null, partyId,
                first.id(), amount == null ? BigDecimal.ZERO : amount, "IDR", expectedClose, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_OPP_CREATE",
                "crm_opportunity", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    /** Moves an opportunity along the pipeline (or to LOST with a reason). */
    @Transactional
    public void moveOpportunity(SiteScope scope, String actor, long opportunityId,
                                String stageCode, String lostReason) {
        CrmRepository.Opportunity opportunity = opportunity(scope, opportunityId);
        CrmRepository.Stage from = crm.stageById(opportunity.stageId())
                .orElseThrow(() -> new CrmException("The opportunity has an unknown stage"));
        CrmRepository.Stage to = crm.stage(scope.organizationId(), stageCode)
                .orElseThrow(() -> new CrmException("Unknown stage: " + stageCode));
        PipelinePolicy.checkMove(
                new PipelinePolicy.Stage(from.id(), from.code(), from.position(),
                        from.probability(), from.won(), from.lost()),
                new PipelinePolicy.Stage(to.id(), to.code(), to.position(),
                        to.probability(), to.won(), to.lost()),
                opportunity.amount(), lostReason);
        String status = to.won() ? "WON" : to.lost() ? "LOST" : "OPEN";
        crm.moveOpportunity(scope.organizationId(), opportunityId, to.id(), status, lostReason);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_OPP_MOVE",
                "crm_opportunity", String.valueOf(opportunityId),
                "{\"stage\":\"" + stageCode + "\"}", null));
    }

    /**
     * The quote hand-off: a draft sales order is created for the opportunity's
     * customer with one service line at the opportunity amount. Sales takes it
     * from there (confirm, deliver, invoice) — the opportunity carries the
     * order id.
     */
    @Transactional
    public long handOffToSales(SiteScope scope, String actor, long opportunityId,
                               Long warehouseId) {
        CrmRepository.Opportunity opportunity = opportunity(scope, opportunityId);
        if (opportunity.salesOrderId() != null) {
            throw new CrmException("The opportunity is already handed off (order "
                    + opportunity.salesOrderId() + ")");
        }
        if (opportunity.partyId() == null) {
            throw new CrmException("The opportunity has no customer yet — convert the lead first");
        }
        if (!"OPEN".equals(opportunity.status())) {
            throw new CrmException("Only an open opportunity can be handed off");
        }
        if (opportunity.amount().signum() <= 0) {
            throw new CrmException("The opportunity needs an amount for the quote");
        }
        var order = salesOrders.create(scope, actor, "ID", opportunity.partyId(),
                LocalDate.now(), null, warehouseId, opportunity.currency(), BigDecimal.ONE,
                "Quote for opportunity " + opportunity.code(),
                List.of(new SalesOrderLineDraft(null, opportunity.name(), BigDecimal.ONE,
                        opportunity.amount(), BigDecimal.ZERO, null)));
        crm.handOff(scope.organizationId(), opportunityId, order.id());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_OPP_HANDOFF",
                "crm_opportunity", String.valueOf(opportunityId),
                "{\"salesOrder\":\"" + order.orderNumber() + "\"}", null));
        return order.id();
    }

    // ------------------------------------------------------------------ activities

    @Transactional
    public long logActivity(SiteScope scope, String actor, Long opportunityId, Long leadId,
                            Long contactId, String type, String subject, LocalDate dueDate,
                            String notes) {
        if (subject == null || subject.isBlank()) {
            throw new CrmException("An activity needs a subject");
        }
        long id = crm.createActivity(scope.organizationId(), opportunityId, leadId, contactId,
                type, subject.trim(), dueDate, notes, actor);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_ACTIVITY_LOG",
                "crm_activity", String.valueOf(id), "{\"type\":\"" + type + "\"}", null));
        return id;
    }

    @Transactional
    public void completeActivity(SiteScope scope, String actor, long activityId) {
        crm.completeActivity(scope.organizationId(), activityId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_ACTIVITY_DONE",
                "crm_activity", String.valueOf(activityId), null, null));
    }

    // ------------------------------------------------------------------ pipeline

    /** The weighted value of the open pipeline (amount × stage probability). */
    public BigDecimal weightedPipeline(SiteScope scope) {
        List<PipelinePolicy.Row> rows = crm.pipelineRows(scope.organizationId()).stream()
                .map(row -> new PipelinePolicy.Row((BigDecimal) row.get("amount"),
                        (BigDecimal) row.get("probability")))
                .toList();
        return PipelinePolicy.weightedValue(rows);
    }

    // ------------------------------------------------------------------ reads

    public List<CrmRepository.Stage> stages(SiteScope scope) {
        return crm.stages(scope.organizationId());
    }

    public List<CrmRepository.Territory> territories(SiteScope scope) {
        return crm.territories(scope.organizationId());
    }

    public List<CrmRepository.Campaign> campaigns(SiteScope scope) {
        return crm.campaigns(scope.organizationId());
    }

    public List<CrmRepository.Lead> leads(SiteScope scope, String status) {
        return crm.leads(scope.organizationId(), status);
    }

    public CrmRepository.Lead lead(SiteScope scope, long leadId) {
        return crm.lead(scope.organizationId(), leadId)
                .orElseThrow(() -> new CrmException("Unknown lead: " + leadId));
    }

    public List<CrmRepository.Opportunity> opportunities(SiteScope scope, String status) {
        return crm.opportunities(scope.organizationId(), status);
    }

    public CrmRepository.Opportunity opportunity(SiteScope scope, long opportunityId) {
        return crm.opportunity(scope.organizationId(), opportunityId)
                .orElseThrow(() -> new CrmException("Unknown opportunity: " + opportunityId));
    }

    public List<CrmRepository.Activity> activities(SiteScope scope, boolean openOnly) {
        return crm.activities(scope.organizationId(), openOnly);
    }

    public List<CrmRepository.Contact> contacts(SiteScope scope) {
        return crm.contacts(scope.organizationId());
    }

    @Transactional
    public long createContact(SiteScope scope, String actor, String firstName, String lastName,
                              String email, String phone, String company) {
        long id = crm.createContact(scope.organizationId(), firstName, lastName, email, phone,
                company);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "CRM_CONTACT_CREATE",
                "crm_contact", String.valueOf(id), null, null));
        return id;
    }

    /** The open pipeline grouped per stage (for the board). */
    public Map<String, List<CrmRepository.Opportunity>> pipelineBoard(SiteScope scope) {
        Map<String, List<CrmRepository.Opportunity>> board = new java.util.LinkedHashMap<>();
        for (CrmRepository.Stage stage : crm.stages(scope.organizationId())) {
            board.put(stage.code(), new java.util.ArrayList<>());
        }
        for (CrmRepository.Opportunity opportunity : crm.opportunities(scope.organizationId(),
                "OPEN")) {
            CrmRepository.Stage stage = crm.stageById(opportunity.stageId()).orElse(null);
            if (stage != null) {
                board.computeIfAbsent(stage.code(), k -> new java.util.ArrayList<>())
                        .add(opportunity);
            }
        }
        return board;
    }
}
