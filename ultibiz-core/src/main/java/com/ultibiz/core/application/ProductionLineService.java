package com.ultibiz.core.application;

import com.ultibiz.core.domain.ProductionLine;
import com.ultibiz.core.domain.WorkCenter;
import com.ultibiz.core.infrastructure.ProductionLineRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Production lines and their work centers. */
@Service
public class ProductionLineService {

    private final ProductionLineRepository lines;

    public ProductionLineService(ProductionLineRepository lines) {
        this.lines = lines;
    }

    public PageResult<ProductionLine> search(SiteScope scope, String search, boolean activeOnly,
                                             int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        long total = lines.count(scope, search, activeOnly);
        List<ProductionLine> items = lines.list(scope, search, activeOnly, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    public List<WorkCenter> workCenters(SiteScope scope) {
        return lines.workCenters(scope);
    }
}
