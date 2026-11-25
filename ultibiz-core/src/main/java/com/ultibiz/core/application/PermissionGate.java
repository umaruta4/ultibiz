package com.ultibiz.core.application;

/**
 * Checks a permission code for the current actor. Implemented in the web layer
 * from the security context; the core never depends on Spring Security.
 */
public interface PermissionGate {

    boolean allowed(String permissionCode);
}
