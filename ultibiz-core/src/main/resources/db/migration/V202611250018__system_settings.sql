-- The system settings of the deployment, edited by the administrator in the
-- interface (Administration -> System settings). The environment variables stay
-- the fallback: a value in this table wins over the variable.
CREATE TABLE IF NOT EXISTS system_setting (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  setting_key VARCHAR(80) NOT NULL,
  value_text TEXT NULL,
  secret TINYINT(1) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  updated_by VARCHAR(80) NULL,
  UNIQUE KEY uk_system_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
