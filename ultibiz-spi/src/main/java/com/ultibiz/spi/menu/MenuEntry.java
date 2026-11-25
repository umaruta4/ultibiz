package com.ultibiz.spi.menu;

/**
 * A navigation entry contributed by a plugin. Rendered by the web shell in the
 * sidebar; ordering follows {@link #order()} then plugin id.
 *
 * @param titleKey i18n message key for the label
 * @param path     application-relative path, e.g. {@code "/accounting/journals"}
 * @param iconKey  icon identifier understood by the web shell (Bootstrap icon name)
 * @param order    sort order, lower first
 */
public record MenuEntry(String titleKey, String path, String iconKey, int order) {

    public MenuEntry {
        if (titleKey == null || titleKey.isBlank()) throw new IllegalArgumentException("titleKey is required");
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path must start with '/'");
    }
}
