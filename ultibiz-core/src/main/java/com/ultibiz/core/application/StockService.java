package com.ultibiz.core.application;

import com.ultibiz.core.domain.StockBalance;
import com.ultibiz.core.domain.StockMovement;
import com.ultibiz.core.infrastructure.StockRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock postings.
 *
 * <p>The balance row is locked before it is changed, so two movements of the
 * same product cannot interleave. Receipts use a moving average cost:
 * {@code average = (oldQty * oldAvg + inQty * unitCost) / newQty}; issues keep
 * the average and only reduce the quantity.
 */
@Service
public class StockService {

    private final StockRepository stock;
    private final TransactionTemplate tx;

    public StockService(StockRepository stock, TransactionTemplate tx) {
        this.stock = stock;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Posts a stock movement. A positive quantity is a receipt, a negative one
     * an issue.
     *
     * @throws OrderException when the issue would drive the stock negative
     */
    public StockMovement post(SiteScope scope, String actor, long siteId, long warehouseId, Long locationId,
                              long productId, BigDecimal quantity, BigDecimal unitCost, LocalDate date,
                              String sourceType, Long sourceId, Integer sourceLine) {
        if (quantity == null || quantity.signum() == 0) {
            throw new OrderException("A stock movement needs a non-zero quantity");
        }
        BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;
        StockMovement[] result = new StockMovement[1];
        tx.executeWithoutResult(status -> {
            StockBalance balance = stock.lockBalance(scope.organizationId(), warehouseId, locationId, productId);
            BigDecimal currentQty = balance == null ? BigDecimal.ZERO : balance.quantity();
            BigDecimal currentAvg = balance == null ? BigDecimal.ZERO : balance.averageCost();
            long balanceId = balance == null ? 0L : balance.id();

            BigDecimal newQty = currentQty.add(quantity);
            if (newQty.signum() < 0) {
                throw new OrderException("Not enough stock: " + currentQty.toPlainString()
                        + " available, " + quantity.abs().toPlainString() + " requested");
            }
            BigDecimal newAvg = currentAvg;
            if (quantity.signum() > 0 && newQty.signum() > 0) {
                newAvg = currentQty.multiply(currentAvg).add(quantity.multiply(cost))
                        .divide(newQty, 4, RoundingMode.HALF_UP);
            }
            BigDecimal stockValue = newQty.multiply(newAvg).setScale(2, RoundingMode.HALF_UP);

            if (balance == null) {
                stock.insertBalance(scope.organizationId(), siteId, warehouseId, locationId, productId,
                        newQty, newAvg, stockValue);
            } else {
                stock.updateBalance(balanceId, newQty, newAvg, stockValue);
            }
            BigDecimal movementValue = quantity.multiply(cost).setScale(2, RoundingMode.HALF_UP);
            long movementId = stock.insertMovement(scope.organizationId(), siteId, warehouseId, locationId,
                    productId, date, quantity, cost, movementValue, newQty, sourceType, sourceId, sourceLine, actor);
            result[0] = new StockMovement(movementId, scope.organizationId(), siteId, warehouseId, locationId,
                    productId, date, quantity, cost, movementValue, newQty, sourceType, sourceId, sourceLine, actor);
        });
        return result[0];
    }

    /** Current stock of a product per warehouse (locations are rolled up). */
    public List<StockBalance> balances(SiteScope scope, Long productId) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return stock.balances(scope.organizationId(), productId);
    }

    /** Stock of a product across every warehouse (used for material checks). */
    public BigDecimal onHand(SiteScope scope, long productId) {
        BigDecimal total = BigDecimal.ZERO;
        for (StockBalance balance : stock.balances(scope.organizationId(), productId)) {
            total = total.add(balance.quantity());
        }
        return total;
    }

    public List<StockMovement> movements(SiteScope scope, long productId, int limit) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return stock.movements(scope.organizationId(), productId, limit);
    }
}
