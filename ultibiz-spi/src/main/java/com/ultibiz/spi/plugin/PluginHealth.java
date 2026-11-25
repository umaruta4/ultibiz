package com.ultibiz.spi.plugin;

import java.time.Instant;

/**
 * Runtime lifecycle state of an installed plugin.
 */
public record PluginHealth(
        String pluginId,
        State state,
        String message,
        Instant checkedAt) {

    public enum State { INSTALLED, ENABLED, DISABLED, UNINSTALLED, FAILED }

    public static PluginHealth enabled(String pluginId) {
        return new PluginHealth(pluginId, State.ENABLED, "ok", Instant.now());
    }

    public static PluginHealth failed(String pluginId, String message) {
        return new PluginHealth(pluginId, State.FAILED, message, Instant.now());
    }
}
