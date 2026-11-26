package com.ultibiz.core.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The Projects persistence: projects, WBS, milestones and assignments. */
@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Project(long id, long organizationId, long siteId, String code, String name,
                          Long customerId, String manager, String status, LocalDate startDate,
                          LocalDate endDate, BigDecimal budgetAmount, String currency,
                          BigDecimal billingRate) {
    }

    public record Wbs(long id, long projectId, String code, String name, Long parentId,
                      BigDecimal budgetHours) {
    }

    public record Milestone(long id, long projectId, String code, String name, LocalDate dueDate,
                            BigDecimal amount, String status, Long journalId) {
    }

    public record Assignment(long id, long projectId, long employeeId, String role,
                             BigDecimal hoursPerWeek, LocalDate startDate, LocalDate endDate) {
    }

    // ------------------------------------------------------------------ projects

    public long createProject(long organizationId, long siteId, String code, String name,
                              Long customerId, String manager, LocalDate startDate,
                              LocalDate endDate, BigDecimal budgetAmount, String currency,
                              BigDecimal billingRate) {
        jdbc.update("""
                INSERT INTO prj_project (organization_id, site_id, code, name, customer_id,
                    manager, start_date, end_date, budget_amount, currency, billing_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, siteId, code, name, customerId, manager,
                Date.valueOf(startDate), endDate == null ? null : Date.valueOf(endDate),
                budgetAmount, currency, billingRate);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Project> project(long organizationId, long projectId) {
        List<Project> rows = jdbc.query("""
                SELECT * FROM prj_project
                WHERE organization_id = ? AND id = ? AND deleted_at IS NULL
                """, (rs, n) -> mapProject(rs), organizationId, projectId);
        return rows.stream().findFirst();
    }

    public Optional<Project> projectByCode(long organizationId, String code) {
        List<Project> rows = jdbc.query("""
                SELECT * FROM prj_project
                WHERE organization_id = ? AND code = ? AND deleted_at IS NULL
                """, (rs, n) -> mapProject(rs), organizationId, code);
        return rows.stream().findFirst();
    }

    public List<Project> projects(long organizationId, String status) {
        String filter = status == null || status.isBlank() ? "%" : status;
        return jdbc.query("""
                SELECT * FROM prj_project
                WHERE organization_id = ? AND status LIKE ? AND deleted_at IS NULL
                ORDER BY code
                """, (rs, n) -> mapProject(rs), organizationId, filter);
    }

    private Project mapProject(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date end = rs.getDate("end_date");
        return new Project(id(rs.getObject("id")), id(rs.getObject("organization_id")),
                id(rs.getObject("site_id")), rs.getString("code"), rs.getString("name"),
                rs.getObject("customer_id") == null ? null : id(rs.getObject("customer_id")),
                rs.getString("manager"), rs.getString("status"),
                rs.getDate("start_date").toLocalDate(), end == null ? null : end.toLocalDate(),
                rs.getBigDecimal("budget_amount"), rs.getString("currency"),
                rs.getBigDecimal("billing_rate"));
    }

    public void updateProjectStatus(long organizationId, long projectId, String status) {
        jdbc.update("""
                UPDATE prj_project SET status = ? WHERE organization_id = ? AND id = ?
                """, status, organizationId, projectId);
    }

    // ------------------------------------------------------------------ WBS

    public long addWbs(long organizationId, long projectId, String code, String name,
                       Long parentId, BigDecimal budgetHours) {
        jdbc.update("""
                INSERT INTO prj_wbs (organization_id, project_id, code, name, parent_id, budget_hours)
                VALUES (?, ?, ?, ?, ?, ?)
                """, organizationId, projectId, code, name, parentId, budgetHours);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Wbs> wbs(long projectId) {
        return jdbc.query("""
                SELECT * FROM prj_wbs WHERE project_id = ? ORDER BY code
                """, (rs, n) -> new Wbs(id(rs.getObject("id")), id(rs.getObject("project_id")),
                        rs.getString("code"), rs.getString("name"),
                        rs.getObject("parent_id") == null ? null : id(rs.getObject("parent_id")),
                        rs.getBigDecimal("budget_hours")), projectId);
    }

    // ------------------------------------------------------------------ milestones

    public long addMilestone(long organizationId, long projectId, String code, String name,
                             LocalDate dueDate, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO prj_milestone (organization_id, project_id, code, name, due_date, amount)
                VALUES (?, ?, ?, ?, ?, ?)
                """, organizationId, projectId, code, name, Date.valueOf(dueDate), amount);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Milestone> milestone(long organizationId, long milestoneId) {
        List<Milestone> rows = jdbc.query("""
                SELECT * FROM prj_milestone WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapMilestone(rs), organizationId, milestoneId);
        return rows.stream().findFirst();
    }

    public List<Milestone> milestones(long projectId) {
        return jdbc.query("""
                SELECT * FROM prj_milestone WHERE project_id = ? ORDER BY due_date
                """, (rs, n) -> mapMilestone(rs), projectId);
    }

    private Milestone mapMilestone(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Milestone(id(rs.getObject("id")), id(rs.getObject("project_id")),
                rs.getString("code"), rs.getString("name"), rs.getDate("due_date").toLocalDate(),
                rs.getBigDecimal("amount"), rs.getString("status"),
                rs.getObject("journal_id") == null ? null : id(rs.getObject("journal_id")));
    }

    public void reachMilestone(long organizationId, long milestoneId) {
        jdbc.update("""
                UPDATE prj_milestone SET status = 'REACHED', reached_at = CURRENT_TIMESTAMP(6)
                WHERE organization_id = ? AND id = ? AND status = 'PENDING'
                """, organizationId, milestoneId);
    }

    public void invoiceMilestone(long organizationId, long milestoneId, long journalId) {
        jdbc.update("""
                UPDATE prj_milestone SET status = 'INVOICED', journal_id = ?
                WHERE organization_id = ? AND id = ? AND status = 'REACHED'
                """, journalId, organizationId, milestoneId);
    }

    // ------------------------------------------------------------------ assignments

    public long assign(long organizationId, long projectId, long employeeId, String role,
                       BigDecimal hoursPerWeek, LocalDate startDate, LocalDate endDate) {
        jdbc.update("""
                INSERT INTO prj_assignment (organization_id, project_id, employee_id, role,
                    hours_per_week, start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, projectId, employeeId, role, hoursPerWeek,
                Date.valueOf(startDate), endDate == null ? null : Date.valueOf(endDate));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Assignment> assignments(long projectId) {
        return jdbc.query("""
                SELECT * FROM prj_assignment WHERE project_id = ? ORDER BY start_date
                """, (rs, n) -> mapAssignment(rs), projectId);
    }

    public List<Assignment> assignmentsOfEmployee(long employeeId) {
        return jdbc.query("""
                SELECT * FROM prj_assignment WHERE employee_id = ?
                """, (rs, n) -> mapAssignment(rs), employeeId);
    }

    private Assignment mapAssignment(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date end = rs.getDate("end_date");
        return new Assignment(id(rs.getObject("id")), id(rs.getObject("project_id")),
                id(rs.getObject("employee_id")), rs.getString("role"),
                rs.getBigDecimal("hours_per_week"), rs.getDate("start_date").toLocalDate(),
                end == null ? null : end.toLocalDate());
    }

    public void endAssignment(long organizationId, long assignmentId, LocalDate endDate) {
        jdbc.update("""
                UPDATE prj_assignment SET end_date = ?
                WHERE organization_id = ? AND id = ?
                """, Date.valueOf(endDate), organizationId, assignmentId);
    }
}
