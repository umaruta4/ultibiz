package com.ultibiz.core.tenant;

/**
 * The tenant of the current request: the name of the database the queries
 * should go to. The value is held in a ThreadLocal, set by the web filter
 * after the login and removed at the end of the request.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The database of the tenant (null = the control database). */
    public static String current() {
        return CURRENT.get();
    }

    /** Puts the tenant of this request on the thread. */
    public static void set(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(databaseName);
        }
    }

    /** The tenant of the next call (null = the control database). */
    public static void clear() {
        CURRENT.remove();
    }

    /** Runs the action in the tenant and gives the previous one back. */
    public static <T> T callInTenant(String databaseName, java.util.concurrent.Callable<T> action) {
        String previous = current();
        set(databaseName);
        try {
            return action.call();
        } catch (Exception ex) {
            throw new IllegalStateException("The tenant action failed", ex);
        } finally {
            set(previous);
        }
    }
}
