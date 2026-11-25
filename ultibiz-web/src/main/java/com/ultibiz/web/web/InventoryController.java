package com.ultibiz.web.web;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.StockService;
import com.ultibiz.core.application.WarehouseService;
import com.ultibiz.core.domain.StockBalance;
import com.ultibiz.web.security.SiteScopeProvider;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Stock overview: balances per product and warehouse. */
@Controller
@RequestMapping("/inventory")
public class InventoryController {

    private final StockService stock;
    private final SiteScopeProvider scopeProvider;

    public InventoryController(StockService stock, SiteScopeProvider scopeProvider) {
        this.stock = stock;
        this.scopeProvider = scopeProvider;
    }

    @GetMapping
    public String balances(@RequestParam(required = false) Long productId, Model model) {
        SiteScope scope = scopeProvider.current();
        List<StockBalance> balances = stock.balances(scope, productId);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockBalance balance : balances) {
            totalQty = totalQty.add(balance.quantity());
            totalValue = totalValue.add(balance.totalValue());
        }
        model.addAttribute("balances", balances);
        model.addAttribute("productId", productId);
        model.addAttribute("totalQty", totalQty);
        model.addAttribute("totalValue", totalValue);
        return "inventory/balances";
    }
}
