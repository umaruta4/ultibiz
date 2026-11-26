package com.ultibiz.core.infrastructure;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The system settings of the deployment. They are read from the **control**
 * database — with the tenant routing the settings of the deployment must not
 * come from the database of a tenant.
 */
@Repository
public class SystemSettingRepository {

    private final JdbcTemplate jdbc;

    public SystemSettingRepository(@Qualifier("controlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored value (null when the setting is not in the database). */
    public String findValue(String key) {
        List<String> values = jdbc.queryForList(
                "SELECT value_text FROM system_setting WHERE setting_key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    public boolean hasSecret(String key) {
        Number count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_setting WHERE setting_key = ? AND secret = 1"
                + " AND value_text IS NOT NULL AND value_text <> ''", Number.class, key);
        return count != null && count.longValue() > 0;
    }

    /** Writes (or updates) a setting; the actor is recorded with the change. */
    public void save(String key, String value, boolean secret, String actor) {
        jdbc.update("INSERT INTO system_setting (setting_key, value_text, secret, updated_by)"
                + " VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE value_text = VALUES(value_text),"
                + " secret = VALUES(secret), updated_by = VALUES(updated_by)",
                key, value, secret, actor);
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM system_setting WHERE setting_key = ?", key);
    }
}
