package com.ultibiz.core.application;

import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.ProductRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
import com.ultibiz.core.infrastructure.SalesOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Material requirements planning: net requirements per product from the open
 * sales orders, the stock on hand, the open purchase orders and the safety
 * stock, with the lead time as the suggested order date.
 */
@Service
public class MrpService {

    private final ProductRepository products;
    private final SalesOrderRepository salesOrders;
    private final PurchaseOrderRepository purchaseOrders;
    private final StockService stock;

    public MrpService(ProductRepository products, SalesOrderRepository salesOrders,
                      PurchaseOrderRepository purchaseOrders, StockService stock) {
        this.products = products;
        this.salesOrders = salesOrders;
        this.purchaseOrders = purchaseOrders;
        this.stock = stock;
    }

    /**
     * The purchase suggestions for the organization.
     *
     * <p>net requirement = demand + safety stock - on hand - on order,
     * rounded up to the minimum order quantity of the product.
     */
    public List<Suggestion> suggestions(SiteScope scope, LocalDate today) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        LocalDate base = today == null ? LocalDate.now() : today;
        Map<Long, BigDecimal> demand = new HashMap<>();
        for (SalesOrderRepository.Demand d : salesOrders.openDemand(scope.organizationId())) {
            demand.merge(d.productId(), d.quantity(), BigDecimal::add);
        }
        Map<Long, BigDecimal> onOrder = new HashMap<>();
        for (PurchaseOrderRepository.Demand d : purchaseOrders.openSupply(scope.organizationId())) {
            onOrder.merge(d.productId(), d.quantity(), BigDecimal::add);
        }

        List<Suggestion> result = new ArrayList<>();
        for (Product product : products.list(scope, null, null, true, 1000, 0)) {
            if (product.phantom()) {
                continue;
            }
            BigDecimal gross = demand.getOrDefault(product.id(), BigDecimal.ZERO);
            if (gross.signum() <= 0) {
                continue;
            }
            BigDecimal onHand = stock.onHand(scope, product.id());
            BigDecimal incoming = onOrder.getOrDefault(product.id(), BigDecimal.ZERO);
            BigDecimal net = gross.add(product.safetyStock()).subtract(onHand).subtract(incoming);
            if (net.signum() <= 0) {
                continue;
            }
            BigDecimal minimum = product.minimumOrderQuantity();
            if (minimum != null && minimum.signum() > 0) {
                net = net.divide(minimum, 0, RoundingMode.CEILING).multiply(minimum);
            }
            net = net.setScale(3, RoundingMode.HALF_UP);
            LocalDate suggestedDate = base.plusDays(product.leadTimeDays());
            result.add(new Suggestion(product.id(), product.code(), product.name(), gross, onHand, incoming,
                    product.safetyStock(), net, product.leadTimeDays(), suggestedDate));
        }
        result.sort((a, b) -> a.suggestedDate().compareTo(b.suggestedDate()));
        return result;
    }

    /** One purchase suggestion. */
    public record Suggestion(long productId, String productCode, String productName, BigDecimal demand,
                             BigDecimal onHand, BigDecimal onOrder, BigDecimal safetyStock, BigDecimal netRequirement,
                             int leadTimeDays, LocalDate suggestedDate) {
    }
}
