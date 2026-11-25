package com.ultibiz.core.infrastructure;

/**
 * Small helpers to read loosely typed JDBC values (jOOQ returns Object) without
 * scattering casts through the repositories.
 */
final class RowValues {

    private RowValues() {
    }

    static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    static Long asNullableLong(Object value) {
        return value == null ? null : asLong(value);
    }

    static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Integer boolFlag(boolean value) {
        return value ? 1 : 0;
    }
}
