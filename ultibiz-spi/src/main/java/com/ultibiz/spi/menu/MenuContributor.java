package com.ultibiz.spi.menu;

import java.util.List;

/**
 * Contribution point: a plugin implements this to add entries to the main
 * navigation. Implementations are discovered via {@code ServiceLoader}.
 */
public interface MenuContributor {

    /**
     * @return navigation entries this plugin contributes; never {@code null}
     */
    List<MenuEntry> menuEntries();
}
