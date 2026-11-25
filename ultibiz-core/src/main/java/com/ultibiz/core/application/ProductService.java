package com.ultibiz.core.application;

import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.ProductRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Product master use cases. Every mutation is audited and every read is
 * filtered by the caller's site scope.
 */
@Service
public class ProductService {

    private static final Set<String> TYPES = Set.of("GOODS", "SERVICE", "KIT");

    private final ProductRepository products;
    private final AuditService audit;

    public ProductService(ProductRepository products, AuditService audit) {
        this.products = products;
        this.audit = audit;
    }

    public PageResult<Product> search(SiteScope scope, String search, String productType,
                                      boolean activeOnly, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        long total = products.count(scope, search, productType, activeOnly);
        List<Product> items = products.list(scope, search, productType, activeOnly, safeSize, safePage * safeSize);
        return new PageResult<>(items, safePage, safeSize, total);
    }

    /** One product inside the caller's scope, if visible. */
    public java.util.Optional<Product> findById(SiteScope scope, long id) {
        return products.findById(scope, id);
    }

    public List<Product> all(SiteScope scope, String search, String productType, boolean activeOnly) {
        return products.list(scope, search, productType, activeOnly, 10000, 0);
    }

    public Product create(SiteScope scope, String actor, ProductDraft draft) {
        validate(draft);
        if (products.findByCode(scope, draft.code()).isPresent()) {
            throw new MasterDataException("Product code already exists: " + draft.code());
        }
        long id = products.insert(scope, draft);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRODUCT_CREATE", "product",
                String.valueOf(id), "{\"code\":\"" + draft.code() + "\"}", null));
        return products.findById(scope, id).orElseThrow(() -> new MasterDataException("Product not found after insert"));
    }

    public boolean update(SiteScope scope, String actor, long id, ProductDraft draft, long expectedVersion) {
        validate(draft);
        boolean updated = products.update(scope, id, draft, expectedVersion);
        if (!updated) {
            throw new MasterDataException("Product was changed by someone else (row_version mismatch) or does not exist");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRODUCT_UPDATE", "product",
                String.valueOf(id), "{\"code\":\"" + draft.code() + "\"}", null));
        return true;
    }

    public boolean delete(SiteScope scope, String actor, long id) {
        boolean deleted = products.softDelete(scope, id);
        if (!deleted) {
            throw new MasterDataException("Product not found or already deleted");
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "PRODUCT_DELETE", "product",
                String.valueOf(id), null, null));
        return true;
    }

    private void validate(ProductDraft draft) {
        if (draft == null) {
            throw new MasterDataException("No product data supplied");
        }
        if (draft.code() == null || draft.code().isBlank()) {
            throw new MasterDataException("Product code is required");
        }
        if (draft.name() == null || draft.name().isBlank()) {
            throw new MasterDataException("Product name is required");
        }
        if (draft.productType() == null || !TYPES.contains(draft.productType().toUpperCase())) {
            throw new MasterDataException("Unknown product type: " + draft.productType());
        }
        if (draft.uomCode() == null || draft.uomCode().isBlank()) {
            throw new MasterDataException("Unit of measure is required");
        }
    }
}
