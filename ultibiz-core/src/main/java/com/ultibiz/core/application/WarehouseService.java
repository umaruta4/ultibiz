package com.ultibiz.core.application;

import com.ultibiz.core.domain.Dock;
import com.ultibiz.core.domain.Rack;
import com.ultibiz.core.domain.StorageLocation;
import com.ultibiz.core.domain.Warehouse;
import com.ultibiz.core.infrastructure.WarehouseRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Warehouse structure: warehouses, racks, bins and docks. */
@Service
public class WarehouseService {

    private final WarehouseRepository warehouses;

    public WarehouseService(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    public PageResult<Warehouse> search(SiteScope scope, String search, boolean activeOnly, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        long total = warehouses.count(scope, search, activeOnly);
        List<Warehouse> items = warehouses.list(scope, search, activeOnly, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public List<Rack> racks(long warehouseId) {
        return warehouses.racks(warehouseId);
    }

    public List<StorageLocation> locations(long warehouseId) {
        return warehouses.locations(warehouseId);
    }

    public List<Dock> docks(long warehouseId) {
        return warehouses.docks(warehouseId);
    }
}
