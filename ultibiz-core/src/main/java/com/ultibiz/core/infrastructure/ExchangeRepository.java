package com.ultibiz.core.infrastructure;

import com.ultibiz.core.exchange.ExchangeMessage;
import com.ultibiz.core.exchange.ExchangePartner;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Partners, inbox/outbox messages, acknowledgements and the scheduler lock. */
@Repository
public class ExchangeRepository {

    private static final String MESSAGE_COLUMNS = """
            id, organization_id, partner_id, direction, protocol, document_type, document_number,
            idempotency_key, payload, payload_format, canonical, status, error, validation_report,
            attempts, max_attempts, next_retry_at, applied_document_type, applied_document_id,
            correlation_id, created_at
            """;

    private final JdbcTemplate jdbc;

    public ExchangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- partners ----------------------------------------------------------

    public List<ExchangePartner> partners(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, code, name, protocol, direction, endpoint_url, auth_type,
                       auth_reference, peppol_participant_id, edi_interchange_id, edi_qualifier,
                       mapping_config, is_active
                FROM exchange_partner WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> mapPartner(rs), organizationId);
    }

    public ExchangePartner partnerByCode(long organizationId, String code) {
        List<ExchangePartner> rows = jdbc.query("""
                SELECT id, organization_id, code, name, protocol, direction, endpoint_url, auth_type,
                       auth_reference, peppol_participant_id, edi_interchange_id, edi_qualifier,
                       mapping_config, is_active
                FROM exchange_partner WHERE organization_id = ? AND code = ?
                """, (rs, n) -> mapPartner(rs), organizationId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The partners with this code in every organisation. The inbound webhook
     * of a partner is not a user session: the organisation of the message
     * comes from the partner itself.
     */
    public List<ExchangePartner> partnersByCode(String code) {
        return jdbc.query("""
                SELECT id, organization_id, code, name, protocol, direction, endpoint_url, auth_type,
                       auth_reference, peppol_participant_id, edi_interchange_id, edi_qualifier,
                       mapping_config, is_active
                FROM exchange_partner WHERE code = ? ORDER BY organization_id
                """, (rs, n) -> mapPartner(rs), code);
    }

    public ExchangePartner partnerById(long organizationId, long id) {
        List<ExchangePartner> rows = jdbc.query("""
                SELECT id, organization_id, code, name, protocol, direction, endpoint_url, auth_type,
                       auth_reference, peppol_participant_id, edi_interchange_id, edi_qualifier,
                       mapping_config, is_active
                FROM exchange_partner WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapPartner(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertPartner(long organizationId, String code, String name, String protocol,
                              String direction, String endpointUrl, String authType, String authReference,
                              String peppolParticipantId, String ediInterchangeId, String ediQualifier,
                              String mappingConfig) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO exchange_partner (organization_id, code, name, protocol, direction,
                        endpoint_url, auth_type, auth_reference, peppol_participant_id,
                        edi_interchange_id, edi_qualifier, mapping_config)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, protocol);
            ps.setString(5, direction);
            ps.setString(6, endpointUrl);
            ps.setString(7, authType);
            ps.setString(8, authReference);
            ps.setString(9, peppolParticipantId);
            ps.setString(10, ediInterchangeId);
            ps.setString(11, ediQualifier);
            ps.setString(12, mappingConfig);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void setPartnerActive(long organizationId, long id, boolean active) {
        jdbc.update("UPDATE exchange_partner SET is_active = ? WHERE organization_id = ? AND id = ?",
                active, organizationId, id);
    }

    public void updatePartnerMapping(long organizationId, long id, String mappingConfig) {
        jdbc.update("UPDATE exchange_partner SET mapping_config = ? WHERE organization_id = ? AND id = ?",
                mappingConfig, organizationId, id);
    }

    // -- messages ----------------------------------------------------------

    /**
     * Inserts a received message. The unique idempotency key makes a duplicate
     * delivery a no-op: the caller catches {@link DuplicateKeyException} and
     * loads the existing row instead.
     */
    public long insertMessage(long organizationId, long partnerId, String direction, String protocol,
                              String documentType, String documentNumber, String idempotencyKey,
                              String payload, String payloadFormat, String status, String correlationId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO exchange_message (organization_id, partner_id, direction, protocol,
                        document_type, document_number, idempotency_key, payload, payload_format,
                        status, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, partnerId);
            ps.setString(3, direction);
            ps.setString(4, protocol);
            ps.setString(5, documentType);
            ps.setString(6, documentNumber);
            ps.setString(7, idempotencyKey);
            ps.setString(8, payload);
            ps.setString(9, payloadFormat);
            ps.setString(10, status);
            ps.setString(11, correlationId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public ExchangeMessage messageById(long organizationId, long id) {
        List<ExchangeMessage> rows = jdbc.query("SELECT " + MESSAGE_COLUMNS
                        + " FROM exchange_message WHERE organization_id = ? AND id = ?",
                (rs, n) -> mapMessage(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ExchangeMessage messageByIdempotencyKey(long organizationId, long partnerId, String direction,
                                                    String idempotencyKey) {
        List<ExchangeMessage> rows = jdbc.query("SELECT " + MESSAGE_COLUMNS
                        + " FROM exchange_message WHERE organization_id = ? AND partner_id = ?"
                        + " AND direction = ? AND idempotency_key = ?",
                (rs, n) -> mapMessage(rs), organizationId, partnerId, direction, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ExchangeMessage> search(long organizationId, String status, String direction,
                                        Long partnerId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS
                + " FROM exchange_message WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = ?");
            params.add(direction);
        }
        if (partnerId != null) {
            sql.append(" AND partner_id = ?");
            params.add(partnerId);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> mapMessage(rs), params.toArray());
    }

    public long count(long organizationId, String status, String direction, Long partnerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM exchange_message WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = ?");
            params.add(direction);
        }
        if (partnerId != null) {
            sql.append(" AND partner_id = ?");
            params.add(partnerId);
        }
        Number n = jdbc.queryForObject(sql.toString(), Number.class, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    public void updateStatus(long messageId, String status, String error, String validationReport) {
        jdbc.update("""
                UPDATE exchange_message SET status = ?, error = ?, validation_report = ? WHERE id = ?
                """, status, error, validationReport, messageId);
    }

    public void markParsed(long messageId, String documentType, String documentNumber, String canonical) {
        jdbc.update("""
                UPDATE exchange_message SET status = ?, document_type = ?, document_number = ?,
                    canonical = ? WHERE id = ?
                """, ExchangeMessage.PARSED, documentType, documentNumber, canonical, messageId);
    }

    public void markApplied(long messageId, String appliedType, Long appliedId) {
        jdbc.update("""
                UPDATE exchange_message SET status = ?, applied_document_type = ?, applied_document_id = ?
                WHERE id = ?
                """, ExchangeMessage.APPLIED, appliedType, appliedId, messageId);
    }

    public void markFailed(long messageId, int attempts, Instant nextRetryAt, String error) {
        jdbc.update("""
                UPDATE exchange_message SET status = ?, attempts = ?, next_retry_at = ?, error = ?
                WHERE id = ?
                """, ExchangeMessage.FAILED, attempts, Timestamp.from(nextRetryAt), error, messageId);
    }

    public void markDead(long messageId, int attempts, String error) {
        jdbc.update("UPDATE exchange_message SET status = ?, attempts = ?, error = ? WHERE id = ?",
                ExchangeMessage.DEAD, attempts, error, messageId);
    }

    public void resetForReplay(long messageId, String status) {
        jdbc.update("""
                UPDATE exchange_message SET status = ?, attempts = 0, error = NULL,
                    next_retry_at = NULL, validation_report = NULL WHERE id = ?
                """, status, messageId);
    }

    /** The retryable messages whose backoff has expired. */
    public List<ExchangeMessage> retryable(long organizationId, Instant now, int limit) {
        return jdbc.query("SELECT " + MESSAGE_COLUMNS
                        + " FROM exchange_message WHERE organization_id = ? AND status = ?"
                        + " AND next_retry_at IS NOT NULL AND next_retry_at <= ?"
                        + " ORDER BY next_retry_at LIMIT ?",
                (rs, n) -> mapMessage(rs), organizationId, ExchangeMessage.FAILED,
                Timestamp.from(now), limit);
    }

    /** Queue depth per status, for the monitoring dashboard. */
    public List<StatusCount> statusCounts(long organizationId) {
        return jdbc.query("""
                SELECT status, COUNT(*) AS n FROM exchange_message
                WHERE organization_id = ? GROUP BY status ORDER BY status
                """, (rs, n) -> new StatusCount(rs.getString("status"), rs.getLong("n")),
                organizationId);
    }

    // -- acknowledgements ----------------------------------------------------

    public long insertAck(long messageId, String ackType, boolean accepted, String payload) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO exchange_ack (message_id, ack_type, accepted, payload)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, messageId);
            ps.setString(2, ackType);
            ps.setBoolean(3, accepted);
            ps.setString(4, payload);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    // -- scheduler lock -------------------------------------------------------

    /**
     * Acquires a MariaDB named lock (server-global, so two replicas never run
     * the exchange scheduler at the same time). The lock is connection-scoped;
     * the caller must keep the connection for the duration of the batch and
     * release the lock afterwards.
     */
    public boolean acquireSchedulerLock(String name, int timeoutSeconds) {
        Number result = jdbc.queryForObject("SELECT GET_LOCK(?, ?)", Number.class, name, timeoutSeconds);
        return result != null && result.intValue() == 1;
    }

    public void releaseSchedulerLock(String name) {
        jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Number.class, name);
    }

    // -- mapping ---------------------------------------------------------------

    private static ExchangePartner mapPartner(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExchangePartner(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("protocol"),
                rs.getString("direction"),
                rs.getString("endpoint_url"),
                rs.getString("auth_type"),
                rs.getString("auth_reference"),
                rs.getString("peppol_participant_id"),
                rs.getString("edi_interchange_id"),
                rs.getString("edi_qualifier"),
                rs.getString("mapping_config"),
                rs.getBoolean("is_active"));
    }

    private static ExchangeMessage mapMessage(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp nextRetry = rs.getTimestamp("next_retry_at");
        Object appliedId = rs.getObject("applied_document_id");
        return new ExchangeMessage(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("partner_id"),
                rs.getString("direction"),
                rs.getString("protocol"),
                rs.getString("document_type"),
                rs.getString("document_number"),
                rs.getString("idempotency_key"),
                rs.getString("payload"),
                rs.getString("payload_format"),
                rs.getString("canonical"),
                rs.getString("status"),
                rs.getString("error"),
                rs.getString("validation_report"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                nextRetry == null ? null : nextRetry.toInstant(),
                rs.getString("applied_document_type"),
                appliedId == null ? null : ((Number) appliedId).longValue(),
                rs.getString("correlation_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** The number of messages in one status. */
    public record StatusCount(String status, long count) {
    }
}
