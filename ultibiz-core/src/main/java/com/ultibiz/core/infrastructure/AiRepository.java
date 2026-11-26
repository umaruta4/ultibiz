package com.ultibiz.core.infrastructure;

import com.ultibiz.core.ai.AiProviderConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** The AI configuration, the conversations, the tool calls and the alerts. */
@Repository
public class AiRepository {

    private final JdbcTemplate jdbc;

    public AiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- configuration ---------------------------------------------------------

    public AiProviderConfig config(long organizationId) {
        List<AiProviderConfig> rows = jdbc.query("""
                SELECT id, organization_id, provider, base_url, model, api_key_encrypted, temperature,
                       timeout_ms, max_tokens, allow_external, system_prompt, is_active
                FROM ai_provider_config WHERE organization_id = ?
                """, (rs, n) -> new AiProviderConfig(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("provider"),
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getString("api_key_encrypted"),
                rs.getBigDecimal("temperature").doubleValue(),
                rs.getInt("timeout_ms"),
                rs.getInt("max_tokens"),
                rs.getBoolean("allow_external"),
                rs.getString("system_prompt"),
                rs.getBoolean("is_active")), organizationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveConfig(long organizationId, String provider, String baseUrl, String model,
                           String encryptedKey, double temperature, int timeoutMs, int maxTokens,
                           boolean allowExternal, String systemPrompt) {
        jdbc.update("""
                INSERT INTO ai_provider_config (organization_id, provider, base_url, model,
                    api_key_encrypted, temperature, timeout_ms, max_tokens, allow_external, system_prompt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE provider = VALUES(provider), base_url = VALUES(base_url),
                    model = VALUES(model), api_key_encrypted = VALUES(api_key_encrypted),
                    temperature = VALUES(temperature), timeout_ms = VALUES(timeout_ms),
                    max_tokens = VALUES(max_tokens), allow_external = VALUES(allow_external),
                    system_prompt = VALUES(system_prompt)
                """, organizationId, provider, baseUrl, model, encryptedKey, temperature, timeoutMs,
                maxTokens, allowExternal, systemPrompt);
    }

    // -- conversations -----------------------------------------------------------

    /** One conversation of a user. */
    public record Conversation(long id, long organizationId, String userId, String title,
                               String contextRoute, Instant createdAt) {
    }

    /** One message of a conversation. */
    public record Message(long id, long conversationId, String role, String content, String provider,
                          String model, int promptTokens, int completionTokens, double cost,
                          int latencyMs, Instant createdAt) {
    }

    public long conversation(long organizationId, String userId, String title, String contextRoute) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ai_conversation (organization_id, user_id, title, context_route)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setString(2, userId);
            ps.setString(3, title);
            ps.setString(4, contextRoute);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Conversation> conversations(long organizationId, String userId, int limit) {
        return jdbc.query("""
                SELECT id, organization_id, user_id, title, context_route, created_at
                FROM ai_conversation WHERE organization_id = ? AND user_id = ?
                ORDER BY id DESC LIMIT ?
                """, (rs, n) -> new Conversation(rs.getLong("id"), rs.getLong("organization_id"),
                rs.getString("user_id"), rs.getString("title"), rs.getString("context_route"),
                rs.getTimestamp("created_at").toInstant()), organizationId, userId, limit);
    }

    public Conversation conversation(long organizationId, long id) {
        List<Conversation> rows = jdbc.query("""
                SELECT id, organization_id, user_id, title, context_route, created_at
                FROM ai_conversation WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new Conversation(rs.getLong("id"), rs.getLong("organization_id"),
                rs.getString("user_id"), rs.getString("title"), rs.getString("context_route"),
                rs.getTimestamp("created_at").toInstant()), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long message(long conversationId, String role, String content, String provider, String model,
                        int promptTokens, int completionTokens, double cost, int latencyMs) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ai_message (conversation_id, role, content, provider, model,
                        prompt_tokens, completion_tokens, cost, latency_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, provider);
            ps.setString(5, model);
            ps.setInt(6, promptTokens);
            ps.setInt(7, completionTokens);
            ps.setBigDecimal(8, new java.math.BigDecimal(cost));
            ps.setInt(9, latencyMs);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Message> messages(long conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, role, content, provider, model, prompt_tokens,
                       completion_tokens, cost, latency_ms, created_at
                FROM ai_message WHERE conversation_id = ? ORDER BY id
                """, (rs, n) -> new Message(rs.getLong("id"), rs.getLong("conversation_id"),
                rs.getString("role"), rs.getString("content"), rs.getString("provider"),
                rs.getString("model"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                rs.getDouble("cost"), rs.getInt("latency_ms"),
                rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    // -- tool calls ---------------------------------------------------------------

    /** One tool call of a conversation. */
    public record ToolCall(long id, long conversationId, Long messageId, String toolName, String kind,
                            String arguments, String status, String diff, String result,
                            String confirmedBy, Instant createdAt) {
    }

    public long toolCall(long conversationId, Long messageId, String toolName, String kind,
                         String arguments, String status, String diff, String result) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ai_tool_call (conversation_id, message_id, tool_name, kind, arguments,
                        status, diff, result)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, conversationId);
            ps.setObject(2, messageId);
            ps.setString(3, toolName);
            ps.setString(4, kind);
            ps.setString(5, arguments);
            ps.setString(6, status);
            ps.setString(7, diff);
            ps.setString(8, result);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public ToolCall toolCall(long organizationId, long id) {
        List<ToolCall> rows = jdbc.query("""
                SELECT c.id, c.conversation_id, c.message_id, c.tool_name, c.kind, c.arguments,
                       c.status, c.diff, c.result, c.confirmed_by, c.created_at
                FROM ai_tool_call c JOIN ai_conversation v ON v.id = c.conversation_id
                WHERE v.organization_id = ? AND c.id = ?
                """, (rs, n) -> new ToolCall(rs.getLong("id"), rs.getLong("conversation_id"),
                nullableLong(rs, "message_id"), rs.getString("tool_name"), rs.getString("kind"),
                rs.getString("arguments"), rs.getString("status"), rs.getString("diff"),
                rs.getString("result"), rs.getString("confirmed_by"),
                rs.getTimestamp("created_at").toInstant()), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ToolCall> toolCalls(long conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, message_id, tool_name, kind, arguments, status, diff, result,
                       confirmed_by, created_at
                FROM ai_tool_call WHERE conversation_id = ? ORDER BY id
                """, (rs, n) -> new ToolCall(rs.getLong("id"), rs.getLong("conversation_id"),
                nullableLong(rs, "message_id"), rs.getString("tool_name"), rs.getString("kind"),
                rs.getString("arguments"), rs.getString("status"), rs.getString("diff"),
                rs.getString("result"), rs.getString("confirmed_by"),
                rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    public void confirmToolCall(long id, String status, String result, String actor) {
        jdbc.update("""
                UPDATE ai_tool_call SET status = ?, result = ?, confirmed_by = ?,
                    confirmed_at = CURRENT_TIMESTAMP(6) WHERE id = ?
                """, status, result, actor, id);
    }

    // -- alerts ---------------------------------------------------------------------

    /** One finding of the anomaly rules. */
    public record Alert(long id, String ruleCode, String severity, String title, String evidence,
                        double confidence, String suggestedAction, String entity, String entityId,
                        String status, Instant createdAt) {
    }

    public void upsertAlert(long organizationId, String ruleCode, String severity, String title,
                             String evidence, double confidence, String suggestedAction, String entity,
                             String entityId) {
        jdbc.update("""
                INSERT INTO ai_alert (organization_id, rule_code, severity, title, evidence, confidence,
                    suggested_action, entity, entity_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE severity = VALUES(severity), title = VALUES(title),
                    evidence = VALUES(evidence), confidence = VALUES(confidence),
                    suggested_action = VALUES(suggested_action)
                """, organizationId, ruleCode, severity, title, evidence, confidence, suggestedAction,
                entity, entityId);
    }

    public List<Alert> alerts(long organizationId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, rule_code, severity, title, evidence, confidence, suggested_action, entity,
                       entity_id, status, created_at
                FROM ai_alert WHERE organization_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new Alert(rs.getLong("id"),
                rs.getString("rule_code"), rs.getString("severity"), rs.getString("title"),
                rs.getString("evidence"), rs.getDouble("confidence"),
                rs.getString("suggested_action"), rs.getString("entity"), rs.getString("entity_id"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                params.toArray());
    }

    public void acknowledgeAlert(long organizationId, long id, String status, String actor) {
        jdbc.update("""
                UPDATE ai_alert SET status = ?, acknowledged_by = ?,
                    acknowledged_at = CURRENT_TIMESTAMP(6) WHERE organization_id = ? AND id = ?
                """, status, actor, organizationId, id);
    }

    // -- usage ---------------------------------------------------------------------

    /** The usage of the organisation: calls, tokens and cost. */
    public Map<String, Object> usage(long organizationId) {
        Map<String, Object> usage = new LinkedHashMap<>();
        Number calls = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_message m JOIN ai_conversation v ON v.id = m.conversation_id
                WHERE v.organization_id = ? AND m.role = 'assistant'
                """, Number.class, organizationId);
        Number promptTokens = jdbc.queryForObject("""
                SELECT COALESCE(SUM(m.prompt_tokens), 0) FROM ai_message m
                JOIN ai_conversation v ON v.id = m.conversation_id
                WHERE v.organization_id = ?
                """, Number.class, organizationId);
        Number completionTokens = jdbc.queryForObject("""
                SELECT COALESCE(SUM(m.completion_tokens), 0) FROM ai_message m
                JOIN ai_conversation v ON v.id = m.conversation_id
                WHERE v.organization_id = ?
                """, Number.class, organizationId);
        Number cost = jdbc.queryForObject("""
                SELECT COALESCE(SUM(m.cost), 0) FROM ai_message m
                JOIN ai_conversation v ON v.id = m.conversation_id
                WHERE v.organization_id = ?
                """, Number.class, organizationId);
        usage.put("calls", calls == null ? 0 : calls.longValue());
        usage.put("promptTokens", promptTokens == null ? 0 : promptTokens.longValue());
        usage.put("completionTokens", completionTokens == null ? 0 : completionTokens.longValue());
        usage.put("cost", cost == null ? 0.0 : cost.doubleValue());
        return usage;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
