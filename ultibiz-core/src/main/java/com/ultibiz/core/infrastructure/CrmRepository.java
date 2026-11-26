package com.ultibiz.core.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The CRM persistence: territories, stages, campaigns, contacts, leads, opportunities, activities. */
@Repository
public class CrmRepository {

    private final JdbcTemplate jdbc;

    public CrmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Stage(long id, String code, String name, int position, BigDecimal probability,
                        boolean won, boolean lost) {
    }

    public record Territory(long id, String code, String name) {
    }

    public record Campaign(long id, String code, String name, String channel, BigDecimal budget,
                           String status) {
    }

    public record Contact(long id, String firstName, String lastName, String email, String phone,
                          String company, Long partyId) {
        public String displayName() {
            return firstName + " " + lastName;
        }
    }

    public record Lead(long id, String code, String company, String contactName, String email,
                       String phone, String source, Long territoryId, Long campaignId,
                       String status, Long partyId, String lostReason) {
    }

    public record Opportunity(long id, String code, String name, Long leadId, Long partyId,
                              long stageId, BigDecimal amount, String currency,
                              LocalDate expectedClose, String owner, String status,
                              String lostReason, Long salesOrderId) {
    }

    public record Activity(long id, Long opportunityId, Long leadId, Long contactId,
                           String type, String subject, LocalDate dueDate, boolean done,
                           String notes, String owner) {
    }

    // ------------------------------------------------------------------ reference data

    public List<Stage> stages(long organizationId) {
        return jdbc.query("""
                SELECT * FROM crm_stage WHERE organization_id = ? ORDER BY position
                """, (rs, n) -> mapStage(rs), organizationId);
    }

    public Optional<Stage> stage(long organizationId, String code) {
        List<Stage> rows = jdbc.query("""
                SELECT * FROM crm_stage WHERE organization_id = ? AND code = ?
                """, (rs, n) -> mapStage(rs), organizationId, code);
        return rows.stream().findFirst();
    }

    public Optional<Stage> stageById(long stageId) {
        List<Stage> rows = jdbc.query("SELECT * FROM crm_stage WHERE id = ?",
                (rs, n) -> mapStage(rs), stageId);
        return rows.stream().findFirst();
    }

    private Stage mapStage(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stage(id(rs.getObject("id")), rs.getString("code"), rs.getString("name"),
                rs.getInt("position"), rs.getBigDecimal("probability"), rs.getBoolean("is_won"),
                rs.getBoolean("is_lost"));
    }

    public List<Territory> territories(long organizationId) {
        return jdbc.query("""
                SELECT * FROM crm_territory WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new Territory(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name")), organizationId);
    }

    public List<Campaign> campaigns(long organizationId) {
        return jdbc.query("""
                SELECT * FROM crm_campaign WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new Campaign(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"), rs.getString("channel"), rs.getBigDecimal("budget"),
                        rs.getString("status")), organizationId);
    }

    // ------------------------------------------------------------------ contacts

    public long createContact(long organizationId, String firstName, String lastName,
                              String email, String phone, String company) {
        jdbc.update("""
                INSERT INTO crm_contact (organization_id, first_name, last_name, email, phone, company)
                VALUES (?, ?, ?, ?, ?, ?)
                """, organizationId, firstName, lastName, email, phone, company);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Contact> contacts(long organizationId) {
        return jdbc.query("""
                SELECT * FROM crm_contact WHERE organization_id = ? ORDER BY last_name, first_name
                """, (rs, n) -> new Contact(id(rs.getObject("id")), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("company"),
                        rs.getObject("party_id") == null ? null : id(rs.getObject("party_id"))),
                organizationId);
    }

    // ------------------------------------------------------------------ leads

    public long createLead(long organizationId, String code, String company, String contactName,
                           String email, String phone, String source, Long territoryId,
                           Long campaignId) {
        jdbc.update("""
                INSERT INTO crm_lead (organization_id, code, company, contact_name, email, phone,
                    source, territory_id, campaign_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, code, company, contactName, email, phone, source,
                territoryId, campaignId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Lead> lead(long organizationId, long leadId) {
        List<Lead> rows = jdbc.query("""
                SELECT * FROM crm_lead WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapLead(rs), organizationId, leadId);
        return rows.stream().findFirst();
    }

    public List<Lead> leads(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM crm_lead WHERE organization_id = ? AND status LIKE ? ORDER BY id DESC
                """, (rs, n) -> mapLead(rs), organizationId, filter);
    }

    private Lead mapLead(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Lead(id(rs.getObject("id")), rs.getString("code"), rs.getString("company"),
                rs.getString("contact_name"), rs.getString("email"), rs.getString("phone"),
                rs.getString("source"),
                rs.getObject("territory_id") == null ? null : id(rs.getObject("territory_id")),
                rs.getObject("campaign_id") == null ? null : id(rs.getObject("campaign_id")),
                rs.getString("status"),
                rs.getObject("party_id") == null ? null : id(rs.getObject("party_id")),
                rs.getString("lost_reason"));
    }

    public void updateLeadStatus(long organizationId, long leadId, String status,
                                 String lostReason, Long partyId) {
        jdbc.update("""
                UPDATE crm_lead SET status = ?, lost_reason = ?, party_id = IFNULL(?, party_id)
                WHERE organization_id = ? AND id = ?
                """, status, lostReason, partyId, organizationId, leadId);
    }

    // ------------------------------------------------------------------ opportunities

    public long createOpportunity(long organizationId, String code, String name, Long leadId,
                                  Long partyId, long stageId, BigDecimal amount, String currency,
                                  LocalDate expectedClose, String owner) {
        jdbc.update("""
                INSERT INTO crm_opportunity (organization_id, code, name, lead_id, party_id,
                    stage_id, amount, currency, expected_close, owner)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, code, name, leadId, partyId, stageId, amount, currency,
                expectedClose == null ? null : Date.valueOf(expectedClose), owner);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Opportunity> opportunity(long organizationId, long opportunityId) {
        List<Opportunity> rows = jdbc.query("""
                SELECT * FROM crm_opportunity WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapOpportunity(rs), organizationId, opportunityId);
        return rows.stream().findFirst();
    }

    public List<Opportunity> opportunities(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM crm_opportunity WHERE organization_id = ? AND status LIKE ?
                ORDER BY id DESC
                """, (rs, n) -> mapOpportunity(rs), organizationId, filter);
    }

    private Opportunity mapOpportunity(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date close = rs.getDate("expected_close");
        return new Opportunity(id(rs.getObject("id")), rs.getString("code"), rs.getString("name"),
                rs.getObject("lead_id") == null ? null : id(rs.getObject("lead_id")),
                rs.getObject("party_id") == null ? null : id(rs.getObject("party_id")),
                id(rs.getObject("stage_id")), rs.getBigDecimal("amount"), rs.getString("currency"),
                close == null ? null : close.toLocalDate(), rs.getString("owner"),
                rs.getString("status"), rs.getString("lost_reason"),
                rs.getObject("sales_order_id") == null ? null : id(rs.getObject("sales_order_id")));
    }

    public void moveOpportunity(long organizationId, long opportunityId, long stageId,
                                String status, String lostReason) {
        jdbc.update("""
                UPDATE crm_opportunity SET stage_id = ?, status = ?, lost_reason = ?
                WHERE organization_id = ? AND id = ?
                """, stageId, status, lostReason, organizationId, opportunityId);
    }

    public void handOff(long organizationId, long opportunityId, long salesOrderId) {
        jdbc.update("""
                UPDATE crm_opportunity SET sales_order_id = ?
                WHERE organization_id = ? AND id = ? AND sales_order_id IS NULL
                """, salesOrderId, organizationId, opportunityId);
    }

    /** The weighted pipeline rows of the open opportunities. */
    public List<java.util.Map<String, Object>> pipelineRows(long organizationId) {
        return jdbc.queryForList("""
                SELECT o.amount, s.probability FROM crm_opportunity o
                JOIN crm_stage s ON s.id = o.stage_id
                WHERE o.organization_id = ? AND o.status = 'OPEN'
                """, organizationId);
    }

    // ------------------------------------------------------------------ activities

    public long createActivity(long organizationId, Long opportunityId, Long leadId,
                               Long contactId, String type, String subject, LocalDate dueDate,
                               String notes, String owner) {
        jdbc.update("""
                INSERT INTO crm_activity (organization_id, opportunity_id, lead_id, contact_id,
                    activity_type, subject, due_date, notes, owner)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, opportunityId, leadId, contactId, type, subject,
                dueDate == null ? null : Date.valueOf(dueDate), notes, owner);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Activity> activities(long organizationId, boolean openOnly) {
        return jdbc.query("""
                SELECT * FROM crm_activity WHERE organization_id = ?
                  AND (? = FALSE OR done = FALSE)
                ORDER BY done, due_date
                """, (rs, n) -> new Activity(id(rs.getObject("id")),
                        rs.getObject("opportunity_id") == null ? null : id(rs.getObject("opportunity_id")),
                        rs.getObject("lead_id") == null ? null : id(rs.getObject("lead_id")),
                        rs.getObject("contact_id") == null ? null : id(rs.getObject("contact_id")),
                        rs.getString("activity_type"), rs.getString("subject"),
                        rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                        rs.getBoolean("done"), rs.getString("notes"), rs.getString("owner")),
                organizationId, openOnly);
    }

    public void completeActivity(long organizationId, long activityId) {
        jdbc.update("""
                UPDATE crm_activity SET done = TRUE WHERE organization_id = ? AND id = ?
                """, organizationId, activityId);
    }
}
