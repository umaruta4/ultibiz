package com.ultibiz.core.application;

import java.util.List;

/**
 * One page of results plus the total row count, so the UI can render paging.
 */
public record PageResult<T>(List<T> items, int page, int size, long total) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int pageCount() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < total;
    }
}
