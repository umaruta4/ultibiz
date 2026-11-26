package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.helpdesk.HelpdeskException;
import com.ultibiz.core.helpdesk.SlaPolicy;
import com.ultibiz.core.helpdesk.TicketPolicy;
import com.ultibiz.core.infrastructure.HelpdeskRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Helpdesk: the ticket intake (portal, e-mail, phone), the SLA tiers of the
 * queue, the assignment, the replies (public for the customer, private for the
 * team), the escalation and the knowledge suggestions from the vault.
 */
@Service
public class HelpdeskService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HelpdeskRepository helpdesk;
    private final KnowledgeVaultService vault;
    private final AuditService audit;

    public HelpdeskService(HelpdeskRepository helpdesk, KnowledgeVaultService vault,
                           AuditService audit) {
        this.helpdesk = helpdesk;
        this.vault = vault;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ intake

    /** A new ticket with the SLA of the queue it lands in. */
    @Transactional
    public long intake(SiteScope scope, String actor, String subject, String description,
                       String category, Long queueId, String requesterName, String requesterEmail,
                       Long requesterPartyId, String source, String priority) {
        TicketPolicy.checkIntake(subject, description);
        HelpdeskRepository.Queue queue = queue(scope, queueId);
        String level = priority == null || priority.isBlank()
                ? TicketPolicy.detectPriority(subject, description) : priority;
        String ticketNo = nextTicketNumber(scope);
        String token = portalToken();
        long id = helpdesk.createTicket(scope.organizationId(), site(scope), ticketNo, subject,
                description, category == null || category.isBlank() ? "GENERAL" : category, level,
                queueId, requesterName, requesterEmail, requesterPartyId,
                source == null || source.isBlank() ? "PORTAL" : source, token,
                queue.responseHours(), queue.resolveHours());
        String channel = source == null || source.isBlank() ? "PORTAL" : source;
        helpdesk.addActivity(scope.organizationId(), id, "NOTE",
                "Ticket created via " + channel, actor, false);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_INTAKE", "hd_ticket",
                String.valueOf(id), "{\"ticketNo\":\"" + ticketNo + "\"}", null));
        return id;
    }

    /** The portal token of a ticket: the read-only link of the customer. */
    public String portalToken(SiteScope scope, long ticketId) {
        return ticket(scope, ticketId).portalToken();
    }

    /** The ticket of the customer portal (the token is the secret). */
    public HelpdeskRepository.Ticket portalTicket(String token) {
        return helpdesk.ticketByToken(token)
                .orElseThrow(() -> new HelpdeskException("This link is not valid any more"));
    }

    /** The public thread of a portal ticket (the organisation comes from the ticket). */
    public List<HelpdeskRepository.Activity> portalActivities(String token) {
        HelpdeskRepository.Ticket ticket = portalTicket(token);
        return helpdesk.activities(ticket.organizationId(), ticket.id(), true);
    }

    /** A reply from the customer portal. */
    @Transactional
    public void portalReply(String token, String body, String author) {
        HelpdeskRepository.Ticket ticket = portalTicket(token);
        TicketPolicy.checkReply(body);
        helpdesk.addActivity(ticket.organizationId(), ticket.id(), "REPLY", body,
                author == null || author.isBlank() ? "customer" : author, true);
        helpdesk.markFirstResponse(ticket.organizationId(), ticket.id());
        audit.record(AuditEvent.of(ticket.organizationId(), actor(author), "HD_TICKET_REPLY",
                "hd_ticket", String.valueOf(ticket.id()), "{\"public\":true}", null));
    }

    // ------------------------------------------------------------------ triage

    /** The triage: the priority, the category and the queue. */
    @Transactional
    public void triage(SiteScope scope, String actor, long ticketId, String priority,
                       String category, Long queueId) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        if (priority != null && !priority.isBlank()) {
            helpdesk.setPriority(scope.organizationId(), ticketId, priority);
        }
        if (category != null && !category.isBlank()) {
            helpdesk.setCategory(scope.organizationId(), ticketId, category);
        }
        if (queueId != null) {
            helpdesk.setQueue(scope.organizationId(), ticketId, queueId);
            HelpdeskRepository.Queue queue = queue(scope, queueId);
            helpdesk.addActivity(scope.organizationId(), ticketId, "NOTE",
                    "Triaged to " + queue.name() + " (" + queue.responseHours() + " h response, "
                    + queue.resolveHours() + " h resolution)", actor, false);
        }
        if ("NEW".equals(ticket.status())) {
            TicketPolicy.checkTransition(ticket.status(), "TRIAGED");
            helpdesk.setStatus(scope.organizationId(), ticketId, "TRIAGED");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_TRIAGE", "hd_ticket",
                String.valueOf(ticketId), null, null));
    }

    /** Assigns the ticket to an employee. */
    @Transactional
    public void assign(SiteScope scope, String actor, long ticketId, long assigneeId) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        if (!"NEW".equals(ticket.status()) && !"TRIAGED".equals(ticket.status())) {
            TicketPolicy.checkTransition(ticket.status(), "ASSIGNED");
        }
        helpdesk.assign(scope.organizationId(), ticketId, assigneeId);
        if ("NEW".equals(ticket.status())) {
            helpdesk.setStatus(scope.organizationId(), ticketId, "TRIAGED");
        }
        helpdesk.setStatus(scope.organizationId(), ticketId, "ASSIGNED");
        helpdesk.addActivity(scope.organizationId(), ticketId, "ASSIGN",
                "Assigned to employee " + assigneeId, actor, false);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_ASSIGN", "hd_ticket",
                String.valueOf(ticketId), "{\"assignee\":" + assigneeId + "}", null));
    }

    /** A reply: public (the customer sees it) or private (a note for the team). */
    @Transactional
    public void reply(SiteScope scope, String actor, long ticketId, String body,
                      boolean isPublic) {
        TicketPolicy.checkReply(body);
        ticket(scope, ticketId);
        helpdesk.addActivity(scope.organizationId(), ticketId, isPublic ? "REPLY" : "NOTE", body,
                actor, isPublic);
        if (isPublic) {
            helpdesk.markFirstResponse(scope.organizationId(), ticketId);
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_REPLY", "hd_ticket",
                String.valueOf(ticketId), "{\"public\":" + isPublic + "}", null));
    }

    /** Resolves the ticket. */
    @Transactional
    public void resolve(SiteScope scope, String actor, long ticketId) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        TicketPolicy.checkTransition(ticket.status(), "RESOLVED");
        helpdesk.setStatus(scope.organizationId(), ticketId, "RESOLVED");
        helpdesk.markResolved(scope.organizationId(), ticketId);
        helpdesk.addActivity(scope.organizationId(), ticketId, "STATUS", "Resolved", actor, true);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_RESOLVE", "hd_ticket",
                String.valueOf(ticketId), null, null));
    }

    /** Closes the ticket. */
    @Transactional
    public void close(SiteScope scope, String actor, long ticketId) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        TicketPolicy.checkTransition(ticket.status(), "CLOSED");
        helpdesk.setStatus(scope.organizationId(), ticketId, "CLOSED");
        helpdesk.markClosed(scope.organizationId(), ticketId);
        helpdesk.addActivity(scope.organizationId(), ticketId, "STATUS", "Closed", actor, true);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_CLOSE", "hd_ticket",
                String.valueOf(ticketId), null, null));
    }

    /** Escalates the ticket (a manager takes it over). */
    @Transactional
    public void escalate(SiteScope scope, String actor, long ticketId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new HelpdeskException("An escalation needs a reason");
        }
        ticket(scope, ticketId);
        helpdesk.markEscalated(scope.organizationId(), ticketId);
        helpdesk.addActivity(scope.organizationId(), ticketId, "ESCALATE", reason, actor, false);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_TICKET_ESCALATE", "hd_ticket",
                String.valueOf(ticketId), "{\"reason\":\"" + reason + "\"}", null));
    }

    // ------------------------------------------------------------------ SLA

    /** The SLA state of a ticket. */
    public record SlaState(boolean responseBreached, boolean resolveBreached, int escalationLevel,
                            LocalDateTime responseDue, LocalDateTime resolveDue) {
    }

    /** The SLA state of one ticket, checked against the given moment. */
    public SlaState slaState(SiteScope scope, long ticketId, LocalDateTime now) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        boolean responseBreached = SlaPolicy.responseBreached(ticket.slaResponseDue(),
                ticket.firstResponseAt(), now);
        boolean resolveBreached = SlaPolicy.resolveBreached(ticket.slaResolveDue(),
                ticket.resolvedAt(), now);
        int level = SlaPolicy.escalationLevel(ticket.slaResolveDue(), now,
                queue(scope, ticket.queueId()).escalateHours());
        return new SlaState(responseBreached, resolveBreached, level, ticket.slaResponseDue(),
                ticket.slaResolveDue());
    }

    /**
     * The SLA monitor: the overdue tickets are escalated and written down.
     */
    @Transactional
    public int runSlaMonitor(SiteScope scope, String actor) {
        int escalated = 0;
        LocalDateTime now = LocalDateTime.now();
        for (HelpdeskRepository.Ticket ticket : helpdesk.overdueTickets(now)) {
            if (ticket.organizationId() != scope.organizationId() || ticket.escalatedAt() != null) {
                continue;
            }
            helpdesk.markEscalated(scope.organizationId(), ticket.id());
            helpdesk.addActivity(scope.organizationId(), ticket.id(), "ESCALATE",
                    "The resolution deadline passed — the ticket is escalated", actor, false);
            escalated++;
        }
        if (escalated > 0) {
            audit.record(AuditEvent.of(scope.organizationId(), actor, "HD_SLA_ESCALATE", "hd_ticket",
                    "0", "{\"escalated\":" + escalated + "}", null));
        }
        return escalated;
    }

    // ------------------------------------------------------------------ knowledge

    /**
     * The knowledge suggestions of a ticket: the vault documents that match the
     * subject and the description of the request.
     */
    @Transactional
    public int suggestKnowledge(SiteScope scope, long ticketId) {
        HelpdeskRepository.Ticket ticket = ticket(scope, ticketId);
        var hits = vault.search(scope, ticket.subject() + " " + ticket.description(), 5);
        int count = 0;
        for (var hit : hits) {
            helpdesk.addSuggestion(scope.organizationId(), ticketId, hit.documentId(), 1.0);
            count++;
        }
        if (count > 0) {
            audit.record(AuditEvent.of(scope.organizationId(), actor("system"),
                    "HD_KB_SUGGEST", "hd_ticket", String.valueOf(ticketId),
                    "{\"suggestions\":" + count + "}", null));
        }
        return count;
    }

    public List<HelpdeskRepository.Suggestion> suggestions(SiteScope scope, long ticketId) {
        return helpdesk.suggestions(scope.organizationId(), ticketId);
    }

    // ------------------------------------------------------------------ reads

    public List<HelpdeskRepository.Queue> queues(SiteScope scope) {
        return helpdesk.queues(scope.organizationId());
    }

    public HelpdeskRepository.Queue queue(SiteScope scope, long queueId) {
        return helpdesk.queue(scope.organizationId(), queueId)
                .orElseThrow(() -> new HelpdeskException("Unknown queue: " + queueId));
    }

    public List<HelpdeskRepository.Ticket> tickets(SiteScope scope, String status, Long queueId) {
        return helpdesk.tickets(scope.organizationId(), status, queueId);
    }

    public HelpdeskRepository.Ticket ticket(SiteScope scope, long ticketId) {
        return helpdesk.ticket(scope.organizationId(), ticketId)
                .orElseThrow(() -> new HelpdeskException("Unknown ticket: " + ticketId));
    }

    public List<HelpdeskRepository.Activity> activities(SiteScope scope, long ticketId,
                                                       boolean publicOnly) {
        return helpdesk.activities(scope.organizationId(), ticketId, publicOnly);
    }

    // ------------------------------------------------------------------ helpers

    /** The next ticket number of the year: TKT-2026-0007. */
    private String nextTicketNumber(SiteScope scope) {
        Long count = helpdesk.tickets(scope.organizationId(), null, null).stream()
                .map(HelpdeskRepository.Ticket::ticketNo)
                .filter(number -> number.startsWith("TKT-" + LocalDateTime.now().getYear()))
                .count();
        return "TKT-" + LocalDateTime.now().getYear() + "-"
                + String.format("%04d", count + 1);
    }

    /** A random portal token (32 hex characters). */
    private static String portalToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(32);
        for (byte value : bytes) {
            token.append(String.format("%02x", value));
        }
        return token.toString();
    }

    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor;
    }

    private static long site(SiteScope scope) {
        return scope.siteIds().stream().findFirst().orElse(0L);
    }
}
