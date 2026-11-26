package com.ultibiz.core.infrastructure;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The Helpdesk persistence: queues, tickets, activities and knowledge suggestions. */
@Repository
public class HelpdeskRepository {

    private final JdbcTemplate jdbc;

    public HelpdeskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Queue(long id, String code, String name, String email, int responseHours,
                        int resolveHours, int escalateHours, boolean active) {
    }

    public record Ticket(long id, long organizationId, long siteId, String ticketNo, String subject,
                         String description, String category, String priority, String status,
                         Long queueId, Long assigneeId, String requesterName, String requesterEmail,
                         Long requesterPartyId, String source, String portalToken,
                         LocalDateTime slaResponseDue, LocalDateTime slaResolveDue,
                         LocalDateTime firstResponseAt, LocalDateTime resolvedAt,
                         LocalDateTime closedAt, LocalDateTime escalatedAt,
                         LocalDateTime createdAt) {
    }

    public record Activity(long id, long ticketId, String type, String body, String author,
                           boolean isPublic, LocalDateTime createdAt) {
    }

    public record Suggestion(long id, long ticketId, long documentId, double score,
                             boolean accepted) {
    }

    // ------------------------------------------------------------------ queues

    public List<Queue> queues(long organizationId) {
        return jdbc.query("""
                SELECT * FROM hd_queue WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new Queue(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"), rs.getString("email"), rs.getInt("response_hours"),
                        rs.getInt("resolve_hours"), rs.getInt("escalate_hours"),
                        rs.getBoolean("is_active")), organizationId);
    }

    public Optional<Queue> queue(long organizationId, long queueId) {
        List<Queue> rows = jdbc.query("""
                SELECT * FROM hd_queue WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new Queue(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"), rs.getString("email"), rs.getInt("response_hours"),
                        rs.getInt("resolve_hours"), rs.getInt("escalate_hours"),
                        rs.getBoolean("is_active")), organizationId, queueId);
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------ tickets

    /**
     * The SLA deadlines are computed by the database clock — the application
     * server may run in another time zone than the database.
     */
    public long createTicket(long organizationId, long siteId, String ticketNo, String subject,
                             String description, String category, String priority,
                             Long queueId, String requesterName, String requesterEmail,
                             Long requesterPartyId, String source, String portalToken,
                             int responseHours, int resolveHours) {
        jdbc.update("""
                INSERT INTO hd_ticket (organization_id, site_id, ticket_no, subject, description,
                    category, priority, status, queue_id, requester_name, requester_email,
                    requester_party_id, source, portal_token, sla_response_due, sla_resolve_due)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?, ?,
                        DATE_ADD(NOW(), INTERVAL ? HOUR), DATE_ADD(NOW(), INTERVAL ? HOUR))
                """, organizationId, siteId, ticketNo, subject, description, category, priority,
                queueId, requesterName, requesterEmail, requesterPartyId, source, portalToken,
                responseHours, resolveHours);
        return jdbc.queryForObject("""
                SELECT id FROM hd_ticket WHERE organization_id = ? AND ticket_no = ?
                """, Long.class, organizationId, ticketNo);
    }

    private static Timestamp timestamp(LocalDateTime moment) {
        return moment == null ? null : Timestamp.valueOf(moment);
    }

    public Optional<Ticket> ticket(long organizationId, long ticketId) {
        List<Ticket> rows = jdbc.query("""
                SELECT * FROM hd_ticket WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapTicket(rs), organizationId, ticketId);
        return rows.stream().findFirst();
    }

    /** The ticket of the customer portal (the token is the secret). */
    public Optional<Ticket> ticketByToken(String portalToken) {
        List<Ticket> rows = jdbc.query("""
                SELECT * FROM hd_ticket WHERE portal_token = ?
                """, (rs, n) -> mapTicket(rs), portalToken);
        return rows.stream().findFirst();
    }

    public List<Ticket> tickets(long organizationId, String status, Long queueId) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM hd_ticket WHERE organization_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (queueId != null) {
            sql.append(" AND queue_id = ?");
            args.add(queueId);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), (rs, n) -> mapTicket(rs), args.toArray());
    }

    private Ticket mapTicket(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Ticket(id(rs.getObject("id")), id(rs.getObject("organization_id")),
                id(rs.getObject("site_id")), rs.getString("ticket_no"), rs.getString("subject"),
                rs.getString("description"), rs.getString("category"), rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("queue_id") == null ? null : id(rs.getObject("queue_id")),
                rs.getObject("assignee_id") == null ? null : id(rs.getObject("assignee_id")),
                rs.getString("requester_name"), rs.getString("requester_email"),
                rs.getObject("requester_party_id") == null ? null
                        : id(rs.getObject("requester_party_id")),
                rs.getString("source"), rs.getString("portal_token"),
                moment(rs.getTimestamp("sla_response_due")),
                moment(rs.getTimestamp("sla_resolve_due")),
                moment(rs.getTimestamp("first_response_at")),
                moment(rs.getTimestamp("resolved_at")), moment(rs.getTimestamp("closed_at")),
                moment(rs.getTimestamp("escalated_at")),
                moment(rs.getTimestamp("created_at")));
    }

    private static LocalDateTime moment(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public void setStatus(long organizationId, long ticketId, String status) {
        jdbc.update("UPDATE hd_ticket SET status = ? WHERE organization_id = ? AND id = ?",
                status, organizationId, ticketId);
    }

    public void assign(long organizationId, long ticketId, Long assigneeId) {
        jdbc.update("""
                UPDATE hd_ticket SET assignee_id = ? WHERE organization_id = ? AND id = ?
                """, assigneeId, organizationId, ticketId);
    }

    public void setPriority(long organizationId, long ticketId, String priority) {
        jdbc.update("UPDATE hd_ticket SET priority = ? WHERE organization_id = ? AND id = ?",
                priority, organizationId, ticketId);
    }

    public void setCategory(long organizationId, long ticketId, String category) {
        jdbc.update("UPDATE hd_ticket SET category = ? WHERE organization_id = ? AND id = ?",
                category, organizationId, ticketId);
    }

    public void setQueue(long organizationId, long ticketId, long queueId) {
        jdbc.update("UPDATE hd_ticket SET queue_id = ? WHERE organization_id = ? AND id = ?",
                queueId, organizationId, ticketId);
    }

    public void markFirstResponse(long organizationId, long ticketId) {
        jdbc.update("""
                UPDATE hd_ticket SET first_response_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ? AND first_response_at IS NULL
                """, organizationId, ticketId);
    }

    public void markResolved(long organizationId, long ticketId) {
        jdbc.update("""
                UPDATE hd_ticket SET resolved_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ?
                """, organizationId, ticketId);
    }

    public void markClosed(long organizationId, long ticketId) {
        jdbc.update("""
                UPDATE hd_ticket SET closed_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ?
                """, organizationId, ticketId);
    }

    public void markEscalated(long organizationId, long ticketId) {
        jdbc.update("""
                UPDATE hd_ticket SET escalated_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ?
                """, organizationId, ticketId);
    }

    // ------------------------------------------------------------------ activities

    public long addActivity(long organizationId, long ticketId, String type, String body,
                             String author, boolean isPublic) {
        jdbc.update("""
                INSERT INTO hd_ticket_activity (organization_id, ticket_id, activity_type, body,
                    author, is_public)
                VALUES (?, ?, ?, ?, ?, ?)
                """, organizationId, ticketId, type, body, author, isPublic);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Activity> activities(long organizationId, long ticketId, boolean publicOnly) {
        return jdbc.query("""
                SELECT * FROM hd_ticket_activity WHERE organization_id = ? AND ticket_id = ?
                  AND (? = FALSE OR is_public = TRUE)
                ORDER BY created_at, id
                """, (rs, n) -> new Activity(id(rs.getObject("id")), id(rs.getObject("ticket_id")),
                        rs.getString("activity_type"), rs.getString("body"), rs.getString("author"),
                        rs.getBoolean("is_public"), moment(rs.getTimestamp("created_at"))),
                organizationId, ticketId, publicOnly);
    }

    // ------------------------------------------------------------------ suggestions

    public void addSuggestion(long organizationId, long ticketId, long documentId, double score) {
        jdbc.update("""
                INSERT IGNORE INTO hd_kb_suggestion (organization_id, ticket_id, document_id, score)
                VALUES (?, ?, ?, ?)
                """, organizationId, ticketId, documentId, score);
    }

    public List<Suggestion> suggestions(long organizationId, long ticketId) {
        return jdbc.query("""
                SELECT * FROM hd_kb_suggestion WHERE organization_id = ? AND ticket_id = ?
                ORDER BY score DESC
                """, (rs, n) -> new Suggestion(id(rs.getObject("id")), id(rs.getObject("ticket_id")),
                        id(rs.getObject("document_id")), rs.getDouble("score"),
                        rs.getBoolean("is_accepted")), organizationId, ticketId);
    }

    /** The tickets whose SLA is overdue and that are not resolved yet. */
    public List<Ticket> overdueTickets(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM hd_ticket
                WHERE status NOT IN ('RESOLVED', 'CLOSED')
                  AND sla_resolve_due IS NOT NULL AND sla_resolve_due < ?
                """, (rs, n) -> mapTicket(rs), Timestamp.valueOf(now));
    }
}
