package com.ultibiz.core.domain;

/**
 * Lifecycle of a fiscal period. {@code REOPENED} behaves like {@code OPEN} but
 * keeps the history of the closure, so a reopened period stays auditable.
 */
public enum PeriodStatus {
    OPEN,
    CLOSED,
    REOPENED
}
