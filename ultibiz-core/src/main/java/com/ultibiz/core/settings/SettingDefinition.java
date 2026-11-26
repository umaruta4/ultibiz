package com.ultibiz.core.settings;

import java.util.List;

/**
 * One item of the configuration that the administrator can edit in the
 * interface. The environment variable stays the fallback — the value in the
 * database wins.
 */
public record SettingDefinition(String key, String label, String group, Type type,
                                String environmentVariable, String defaultValue,
                                boolean restartRequired, String hint, List<String> options) {

    public enum Type { TEXT, PASSWORD, NUMBER, BOOLEAN, SELECT }

    public SettingDefinition {
        if (type == Type.PASSWORD) {
            // the secrets are never shown again: the form only says "set"
        }
    }

    public boolean secret() {
        return type == Type.PASSWORD;
    }

    public static SettingDefinition text(String key, String label, String group, String env,
                                          String defaultValue, boolean restart, String hint) {
        return new SettingDefinition(key, label, group, Type.TEXT, env, defaultValue, restart,
                hint, List.of());
    }

    public static SettingDefinition password(String key, String label, String group, String env,
                                              boolean restart, String hint) {
        return new SettingDefinition(key, label, group, Type.PASSWORD, env, null, restart, hint,
                List.of());
    }

    public static SettingDefinition number(String key, String label, String group, String env,
                                            String defaultValue, boolean restart, String hint) {
        return new SettingDefinition(key, label, group, Type.NUMBER, env, defaultValue, restart,
                hint, List.of());
    }

    public static SettingDefinition bool(String key, String label, String group, String env,
                                          String defaultValue, boolean restart, String hint) {
        return new SettingDefinition(key, label, group, Type.BOOLEAN, env, defaultValue, restart,
                hint, List.of("true", "false"));
    }

    public static SettingDefinition select(String key, String label, String group, String env,
                                            String defaultValue, boolean restart, String hint,
                                            List<String> options) {
        return new SettingDefinition(key, label, group, Type.SELECT, env, defaultValue, restart,
                hint, options);
    }
}
