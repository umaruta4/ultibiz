package com.ultibiz.core.domain;

/** A rack inside a warehouse. */
public record Rack(long id, long warehouseId, String code, String name) {
}
