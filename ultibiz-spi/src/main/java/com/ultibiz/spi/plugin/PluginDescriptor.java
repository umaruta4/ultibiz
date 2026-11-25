package com.ultibiz.spi.plugin;

import java.util.List;
import java.util.Set;

/**
 * Identity and metadata of an UltiBiz plugin. Every plugin ships a
 * {@code META-INF/ultibiz/plugin.properties} describing these values, and the
 * core refuses to enable a plugin whose declared API range does not cover the
 * running core API version.
 *
 * @param id            stable plugin id, e.g. {@code "accounting"}; owns the
 *                      table prefix {@code ub_accounting_*}
 * @param version       plugin version (semantic)
 * @param apiVersion    core API version range the plugin was built against,
 *                      e.g. {@code ">=4.2 <5.0"}
 * @param nameKey       i18n message key for the display name
 * @param descriptionKey i18n message key for the description
 * @param capabilities  capability tokens this plugin requests (granted by an
 *                      administrator), e.g. {@code "accounting.post"}
 * @param requires      plugin ids that must be enabled first
 * @param requiresRestart whether enabling/disabling/upgrading this plugin
 *                      needs a controlled restart (schema-bearing plugins)
 */
public record PluginDescriptor(
        String id,
        String version,
        String apiVersion,
        String nameKey,
        String descriptionKey,
        Set<String> capabilities,
        List<String> requires,
        boolean requiresRestart) {

    public PluginDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("plugin id is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("plugin version is required");
        if (apiVersion == null || apiVersion.isBlank()) throw new IllegalArgumentException("apiVersion is required");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }
}
