package com.ultibiz.core.domain;

/** A supply / receiving / delivery / dispatch dock inside a warehouse. */
public record Dock(long id, long warehouseId, String code, String name, String dockType) {
}
