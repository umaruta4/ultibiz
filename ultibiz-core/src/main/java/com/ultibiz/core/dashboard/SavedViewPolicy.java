package com.ultibiz.core.dashboard;

/** The saved view rules: a view needs a code, a title and a module. */
public final class SavedViewPolicy {

    private SavedViewPolicy() {
    }

    public static void check(String code, String title, String module) {
        if (code == null || code.isBlank()) {
            throw new DashboardException("A saved view needs a code");
        }
        if (title == null || title.isBlank()) {
            throw new DashboardException("A saved view needs a title");
        }
        if (module == null || module.isBlank()) {
            throw new DashboardException("A saved view needs a module");
        }
    }
}
